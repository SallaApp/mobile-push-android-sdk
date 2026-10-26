package com.salla.push.internal

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.salla.push.BuildConfig
import com.salla.push.SallaPushClickEvent
import com.salla.push.SallaPushClickResult
import com.salla.push.SallaPushConfig
import com.salla.push.SallaPushFirebaseMessagingService
import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLoginFailure
import com.salla.push.SallaPushNotification
import com.salla.push.SallaPushPayloadSource
import com.salla.push.SallaPushWillDisplayEvent
import com.salla.push.internal.api.ApiBaseUrl
import com.salla.push.internal.api.Routes
import com.salla.push.internal.click.ClickDispatcher
import com.salla.push.internal.click.QueuedClick
import com.salla.push.internal.device.AndroidDeviceInfo
import com.salla.push.internal.display.DisplayResult
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.engine.ConsentState
import com.salla.push.internal.engine.EVENT_DISMISSED
import com.salla.push.internal.engine.EVENT_DISPLAYED
import com.salla.push.internal.engine.EVENT_OPENED
import com.salla.push.internal.engine.EVENT_RECEIVED
import com.salla.push.internal.engine.Engine
import com.salla.push.internal.engine.EngineEnv
import com.salla.push.internal.engine.EngineHost
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.engine.EngineMode
import com.salla.push.internal.engine.EngineRegistry
import com.salla.push.internal.engine.DrainScanner
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.engine.IdentityRules
import com.salla.push.internal.engine.PendingClick
import com.salla.push.internal.engine.Snapshot
import com.salla.push.internal.engine.WorkerRun
import com.salla.push.internal.engine.addTags
import com.salla.push.internal.engine.isValidFcmToken
import com.salla.push.internal.engine.login
import com.salla.push.internal.engine.logout
import com.salla.push.internal.engine.onActivityResumed
import com.salla.push.internal.engine.onBackground
import com.salla.push.internal.engine.onConnectivityRegained
import com.salla.push.internal.engine.onDeviceSettingsChanged
import com.salla.push.internal.engine.onForeground
import com.salla.push.internal.engine.onToken
import com.salla.push.internal.engine.reconcileAfterWithdrawal
import com.salla.push.internal.engine.recordEvent
import com.salla.push.internal.engine.refreshPermissionNow
import com.salla.push.internal.engine.removeTags
import com.salla.push.internal.engine.resumeAfterConsent
import com.salla.push.internal.engine.setLanguage
import com.salla.push.internal.engine.setOptedOut
import com.salla.push.internal.engine.withdrawConsent
import com.salla.push.internal.lifecycle.AppLifecycle
import com.salla.push.internal.lifecycle.ForegroundListener
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.LogSanitizer
import com.salla.push.internal.log.LogcatWriter
import com.salla.push.internal.log.Logger
import com.salla.push.internal.log.Meta
import com.salla.push.internal.log.SecretString
import com.salla.push.internal.payload.MessageId
import com.salla.push.internal.payload.ParsedPayload
import com.salla.push.internal.payload.PayloadParser
import com.salla.push.internal.permission.NotificationPermission
import com.salla.push.internal.permission.PermissionRequests
import com.salla.push.internal.storage.ConsentRead
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.storage.LastInit
import com.salla.push.internal.storage.Validation
import com.salla.push.internal.util.Cancellable
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.EntryGuard
import com.salla.push.internal.util.Jitter
import com.salla.push.internal.util.MainThread
import com.salla.push.internal.util.AndroidMainThread
import com.salla.push.internal.util.AppCallbacks
import com.salla.push.internal.util.InternalFailure
import com.salla.push.internal.util.RandomJitter
import com.salla.push.internal.util.SystemClock
import com.salla.push.internal.util.TaskRunner
import com.salla.push.internal.util.ThreadRunner
import com.salla.push.internal.util.asciiLowercase
import com.salla.push.internal.util.cleanField
import com.salla.push.internal.util.nowSeconds
import com.salla.push.internal.util.printableAscii
import com.salla.push.internal.util.stringOrNull
import com.salla.push.internal.util.truncatedUtf16
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide dependencies that need no Context. Threads are created lazily, on first use. */
internal class CoreEnv(
    val clock: Clock,
    val jitter: Jitter,
    val logger: Logger,
    val engineRunner: TaskRunner,
    val netRunner: TaskRunner,
    val displayRunner: TaskRunner,
    val mainThread: MainThread,
    val platformFactory: (Context, CoreEnv, () -> String?) -> CorePlatform,
) {
    companion object {
        fun production(): CoreEnv {
            val logger = Logger(SystemClock, LogcatWriter)
            val report = { thread: String ->
                { failure: Throwable ->
                    logger.log(LogCode.INTERNAL_ERROR) {
                        "SallaPush hit an internal error on its $thread thread (${InternalFailure.describe(failure)}); that task was skipped."
                    }
                }
            }
            return CoreEnv(
                clock = SystemClock,
                jitter = RandomJitter,
                logger = logger,
                engineRunner = ThreadRunner("SallaPush-engine", report("SallaPush-engine")),
                netRunner = ThreadRunner("SallaPush-net", report("SallaPush-net")),
                displayRunner = ThreadRunner("SallaPush-display", report("SallaPush-display")),
                mainThread = AndroidMainThread,
                platformFactory = { context, env, appVersion -> CorePlatform.production(context, env, appVersion) },
            )
        }
    }
}

/** The process-wide core, created lazily; its construction cannot throw. */
internal object Sdk {

    private val lock = Any()

    @Volatile
    private var installed: SdkCore? = null

    val core: SdkCore
        get() = installed ?: synchronized(lock) {
            installed ?: SdkCore(CoreEnv.production()).also { installed = it }
        }

    /** Replaces the core (dependency injection for tests and embedders); the previous core is left as is. */
    fun install(core: SdkCore) {
        synchronized(lock) { installed = core }
    }
}

/** What the current initialisation configured. */
internal class CoreConfig(
    val clearTagsOnLogout: Boolean,
    val openLaunchUrls: Boolean,
    val appVersion: String?,
    val autoClearBadge: Boolean,
    val autoUpdateBadge: Boolean,
) {
    companion object {
        val DEFAULT = CoreConfig(
            clearTagsOnLogout = true,
            openLaunchUrls = false,
            appVersion = null,
            autoClearBadge = BadgeDefaults.AUTO_CLEAR_BADGE,
            autoUpdateBadge = BadgeDefaults.AUTO_UPDATE_BADGE,
        )
    }
}

/**
 * The SDK behind the public facade. Public calls validate cheap arguments on the caller thread, log, and post to the
 * engine thread; everything that touches state, files or the network happens there or on the net thread. Before the
 * main engine runs (not initialised, or waiting for consent) calls go to the bounded [PreInitBuffer].
 */
internal class SdkCore(val env: CoreEnv) : EngineHost, ForegroundListener {

    val logger: Logger get() = env.logger
    val registry = EngineRegistry()
    val permissionRequests = PermissionRequests(env.mainThread)
    val observers = Observers(env.mainThread, env.logger)
    val clicks = ClickDispatcher(env.mainThread, env.clock, env.logger) { persistClicks(it) }

    /** Calls the engine has not applied yet, shown by the public getters at once ([view]). */
    private val pendingWrites = PendingWrites()

    private val platformLock = Any()
    private val manifestLock = Any()

    @Volatile
    private var platform: CorePlatform? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var appId: String? = null
        private set

    @Volatile
    private var disabled = false

    @Volatile
    private var disabledReason = DisabledReason.INVALID_APP_ID

    @Volatile
    private var mainEngine: Engine? = null

    @Volatile
    private var config: CoreConfig = CoreConfig.DEFAULT

    /** initWithContext applied the app's configuration in this process ([config] holds it). */
    @Volatile
    private var configured = false

    /** Before [configured]: the autoUpdateBadge of last_init.json, read once by the first notification shown. */
    @Volatile
    private var savedAutoUpdateBadge: Boolean? = null
    private val savedBadgeLock = Any()

    @Volatile
    var displayCompatPayloads: Boolean = true

    @Volatile
    private var forwardedSomething = false

    @Volatile
    private var lifecycle: AppLifecycle? = null

    @Volatile
    private var manifest: ManifestConfig? = null

    private val unknownVersionWarned = AtomicBoolean(false)
    private val disabledWarned = AtomicBoolean(false)
    private val configApplied = AtomicBoolean(false)

    /** optIn() could not prompt yet (not initialised, or not in the foreground): the next foreground prompts once. */
    private val pendingOptInPrompt = AtomicBoolean(false)

    // Engine thread only.
    private val buffer = PreInitBuffer()
    private val consent = ConsentState()
    private var bootstrapped = false
    private var pending: PendingInit? = null
    private var resumeTask: Cancellable? = null
    private var fcmHintScheduled = false

    /**
     * The per-app files may predate a withdrawal: consent.json read "withdrawn" at launch, or a withdrawal was carried
     * out at initWithContext. The withdrawal writes the minimised state once; consent given again still reconciles the
     * files, in case that write failed or the process died before it.
     */
    private var staleAfterWithdrawal = false

    /** The app gave consent in this process (setConsentGiven(true)). */
    private var appGaveConsent = false

    /** The app called setConsentGiven in this process: consent.given is its answer, whatever consent.json holds. */
    private var appAnsweredConsent = false
    private var consentReadFailureLogged = false

    /**
     * Consent that was given (stored in consent.json, or earlier in this process) was withdrawn while no engine ran:
     * the withdrawal is carried out as soon as consent is required and the app id is known.
     */
    private var withdrawalPending = false

    /**
     * A consent answer not written yet: given before consent.json could be read, or its write failed. Written at the
     * next consent call, initWithContext or foreground, once consent.json was read.
     */
    private var pendingConsentWrite: Boolean? = null
    private var consentWriteFailureLogged = false

    /**
     * The state observers last received, which is what the getters show (with the calls not applied yet): a getter
     * never changes without the observer callback that reports it. Changes are always delivered from it, whichever
     * engine is the app's. While consent is withheld it keeps the last state, as on iOS.
     */
    @Volatile
    private var delivered: Snapshot = Snapshot.EMPTY

    /**
     * The engine a consent withdrawal paused (engine thread only): until consent is given again or the app id or API
     * host changes, it is the app's engine for the getters and observers, with the withdrawal's minimisation (no route
     * key, no tags).
     */
    private var withdrawnEngine: Engine? = null

    /** The process's first main engine published the state it started from: later publications are changes. */
    private var baselineRestored = false

    /** The last FCM token seen in this process (memory only): a new main engine after an app id switch starts with it. */
    private var lastToken: String? = null

    /** last_init.json as read once in this process, then as last written. */
    private var lastInitRead = false
    private var lastInitOnDisk: LastInit? = null

    /** The last answer of the Firebase token read at engine start; it shapes the token.missing advice. */
    @Volatile
    private var firebaseOutcome: FirebaseToken? = null

    private class PendingInit(val key: EngineKey, val baseUrl: String)

    /** Why SallaPush ignores the app's calls in this process. */
    private enum class DisabledReason {
        INVALID_APP_ID,
        NOT_MAIN_PROCESS,
    }

    // ── Initialisation ──

    fun initialize(context: Context, appId: String, sdkConfig: SallaPushConfig?) {
        if (disabled) {
            warnDisabled("initWithContext")
            return
        }
        val first = !isInitialized
        if (!Validation.isAppId(appId)) {
            // The level is resolved even though SallaPush stays disabled, so this error reaches Logcat.
            if (first) sdkConfig?.logLevel?.let { logger.setConfigLevel(it) }
            val running = this.appId
            logger.log(LogCode.INIT_INVALID_APP_ID) {
                val outcome = if (first || running == null) {
                    ". SallaPush stays disabled."
                } else {
                    "; this call was ignored, and SallaPush keeps running with app $running."
                }
                val shown = printableAscii(truncatedUtf16(appId, 64))
                "App id \"$shown\" is invalid (expected 1-64 chars A-Z, a-z, 0-9, _, -)$outcome"
            }
            if (first) {
                disabledReason = DisabledReason.INVALID_APP_ID
                disabled = true
                pendingWrites.clear()
                val platform = ensurePlatform(context)
                submit {
                    buffer.clear()
                    resolveManifest(platform)
                }
            }
            return
        }
        val platform = ensurePlatform(context)
        registerLifecycle(platform)
        // The first initialisation applies its options at once (a tap opened right after already sees them); a later
        // one applies them on the engine thread, and only when it changes the app id or API host.
        val appliedHere = configApplied.compareAndSet(false, true)
        if (appliedHere) applyConfig(sdkConfig)
        isInitialized = true
        this.appId = appId
        submit { onInitialize(platform, appId, sdkConfig, appliedHere) }
    }

    /**
     * The platform for the app's Context; built once, without I/O. Building it also settles the console level from
     * the manifest on the engine thread, so log lines of a process that never initialises (a push handled first, a
     * forwarded token) still reach Logcat.
     */
    fun ensurePlatform(context: Context): CorePlatform {
        platform?.let { return it }
        val created = synchronized(platformLock) {
            platform?.let { return it }
            val appContext = context.applicationContext ?: context
            env.platformFactory(appContext, env) { config.appVersion }.also { platform = it }
        }
        submit { resolveManifest(created) }
        return created
    }

    /**
     * Registers the foreground tracker on the caller's thread (no I/O). The process importance that seeds
     * a late initialisation is an IPC, read on the engine thread.
     */
    private fun registerLifecycle(platform: CorePlatform) {
        if (lifecycle != null) return
        val tracker = synchronized(platformLock) {
            if (lifecycle != null) return
            val application = platform.context as? Application ?: return
            AppLifecycle(env.mainThread, this).also {
                it.register(application)
                lifecycle = it
            }
        }
        if (!env.engineRunner.execute(Runnable { tracker.seed(platform.processIsForeground()) })) tracker.seed(false)
    }

    private fun applyConfig(sdkConfig: SallaPushConfig?) {
        logger.setConfigLevel(sdkConfig?.logLevel)
        config = CoreConfig(
            clearTagsOnLogout = sdkConfig?.clearTagsOnLogout ?: true,
            openLaunchUrls = sdkConfig?.openLaunchUrls ?: false,
            appVersion = cleanField(sdkConfig?.appVersion, AndroidDeviceInfo.MAX_VERSION),
            autoClearBadge = sdkConfig?.autoClearBadge ?: BadgeDefaults.AUTO_CLEAR_BADGE,
            autoUpdateBadge = sdkConfig?.autoUpdateBadge ?: BadgeDefaults.AUTO_UPDATE_BADGE,
        )
        configured = true
    }

    private fun onInitialize(platform: CorePlatform, appId: String, sdkConfig: SallaPushConfig?, appliedHere: Boolean) {
        val wasBootstrapped = bootstrapped
        if (!bootstrap(platform)) return
        // Bootstrap reads consent.json the first time; a later initWithContext reads it again if that failed.
        val consentReadNow = wasBootstrapped && loadConsentIfNeeded(platform)
        retryConsentWrite()
        // SallaPush's own server; only tests point the SDK at another one.
        val baseUrl = sdkConfig?.apiBaseUrl?.let { ApiBaseUrl.normalize(it) } ?: Routes.DEFAULT_BASE_URL
        val hostTag = HostTag.of(baseUrl) ?: return
        val key = EngineKey(appId, hostTag)
        val previous = pending
        if (previous?.key == key) {
            // The same app id and API host again: a no-op. Its options, log level and consent flag are
            // ignored, and the line says so (as on iOS).
            logger.log(LogCode.INIT_STARTED, mapOf(Meta.APP_ID to appId)) {
                "SallaPush is already initialized with app $appId; this call changes nothing."
            }
            if (consentReadNow) reevaluateConsent()
            return
        }
        if (!appliedHere) applyConfig(sdkConfig)
        if (sdkConfig?.requiresUserConsent == true) consent.requiredByConfig = true
        logger.log(LogCode.INIT_STARTED, mapOf(Meta.APP_ID to appId)) {
            "SallaPush ${BuildConfig.SDK_VERSION} initializing (app $appId, log ${levelName(logger.consoleLevel)})"
        }
        if (previous != null) {
            logger.log(LogCode.INIT_APP_ID_CHANGED, mapOf(Meta.APP_ID to appId)) {
                val change = if (previous.key.appId == appId) {
                    "app $appId on another server; the previous server"
                } else {
                    "app $appId (was ${previous.key.appId}); the previous app"
                }
                "Re-initialized with $change finishes its pending logout first."
            }
        }
        val current = mainEngine
        if (current != null) {
            mainEngine = null
            current.task { current.enterDrainMode() }
        }
        // The engine a withdrawal paused belongs to the previous app id or host: it is not the app's engine any more
        // (the getters keep the state observers last received until the new engine publishes its own).
        if (withdrawnEngine?.key != key) withdrawnEngine = null
        pending = PendingInit(key, baseUrl)
        if (!consent.satisfied) {
            platform.deviceSettings.stop()
            if (withdrawalPending) startWithdrawalEngine(platform, key, baseUrl) else logConsentWaiting()
            return
        }
        startMainEngine(platform, key, baseUrl)
    }

    /** Once per process, on the engine thread: manifest meta-data, process check and persisted consent. */
    private fun bootstrap(platform: CorePlatform): Boolean {
        if (disabled) return false
        if (bootstrapped) return true
        bootstrapped = true
        resolveManifest(platform)
        val mainProcess = try {
            platform.processInfo.isMainProcess()
        } catch (e: Exception) {
            true
        }
        if (!mainProcess) {
            disabledReason = DisabledReason.NOT_MAIN_PROCESS
            disabled = true
            // Not initialised here, whatever initWithContext was called with: SallaPush only shows notifications.
            isInitialized = false
            buffer.clear()
            pendingWrites.clear()
            logger.log(LogCode.INIT_NOT_MAIN_PROCESS) { "Not the main process; SallaPush only shows notifications here." }
            return false
        }
        loadConsentIfNeeded(platform)
        return true
    }

    /**
     * Reads consent.json until one read succeeds, as on iOS; true when this call read it. A corrupt file holds no
     * answer: it counts as not given until the app sets it again. A file that cannot be read is never taken as an
     * answer: an app that requires consent waits, and the file is read again at the next foreground, consent call or
     * initWithContext. Nothing is written to it before it was read, so an answer the app gave meanwhile is compared with
     * the stored one first: withdrawing a consent the file still holds is a withdrawal. Both failures are logged once,
     * so an app that finds SallaPush waiting can tell why.
     */
    private fun loadConsentIfNeeded(platform: CorePlatform): Boolean {
        if (consent.loaded) return false
        val stored = when (val read = platform.globalStore.loadConsent()) {
            is ConsentRead.Answer -> read.given
            ConsentRead.Absent -> null
            ConsentRead.Corrupt -> {
                logger.log(LogCode.STORAGE_STATE_RESET) {
                    "SallaPush consent (consent.json) was corrupt; it counts as not given until the app sets it again."
                }
                null
            }
            is ConsentRead.Unreadable -> {
                if (!consentReadFailureLogged) {
                    consentReadFailureLogged = true
                    logger.log(LogCode.STORAGE_FAILED) {
                        "Could not read SallaPush consent (consent.json: ${LogSanitizer.clean(read.reason)}); it is read " +
                            "again at the next foreground, consent call or initWithContext."
                    }
                }
                return false
            }
        }
        consent.loaded = true
        if (stored == false) staleAfterWithdrawal = true
        if (!appAnsweredConsent) {
            consent.given = stored
        } else if (consent.given == false && stored == true && !appGaveConsent) {
            // Withdrawn before the file was read. An app that gave consent in this process and withdrew it again had
            // that withdrawal flagged by the call.
            withdrawalPending = true
        }
        pendingConsentWrite?.let { persistConsent(platform, it) }
        return true
    }

    /**
     * The manifest meta-data, read once per process (a PackageManager call: on the engine thread, or on the thread that
     * displays a push, never on an app thread that called a public API), and the console level it settles: whichever
     * path reads it first applies com.salla.push.LOG_LEVEL.
     */
    private fun resolveManifest(platform: CorePlatform): ManifestConfig {
        manifest?.let { return it }
        synchronized(manifestLock) {
            manifest?.let { return it }
            val metaData = try {
                platform.manifestReader()
            } catch (e: Exception) {
                ManifestConfig.EMPTY
            }
            manifest = metaData
            logger.setManifestLevel(metaData.logLevel)
            metaData.invalidLogLevel?.let { value ->
                logger.log(LogCode.API_INVALID_ARGUMENT) {
                    "The ${ManifestConfig.KEY_LOG_LEVEL} meta-data \"${printableAscii(truncatedUtf16(value, MAX_SHOWN_VALUE))}\" is not a log " +
                        "level (accepted: off, error, warn, info, debug, verbose); it is ignored."
                }
            }
            return metaData
        }
    }

    /**
     * Starts the main engine. An engine paused by a consent withdrawal in this process resumes with its in-memory
     * state; a new engine whose files may predate a withdrawal reconciles with the server first. The state it starts
     * from is published before the calls kept meanwhile are applied: for the process's first main engine that is the
     * state an earlier launch left (no change for observers), and every call after it is a real change.
     */
    private fun startMainEngine(platform: CorePlatform, key: EngineKey, baseUrl: String) {
        val paused = registry.get(key)?.takeIf { it.withdrawal != null && !it.stopped }
        val engine = paused ?: registry.obtain(key) { createEngine(platform, key, baseUrl, EngineMode.MAIN) }
        mainEngine = engine
        withdrawnEngine = null
        platform.deviceSettings.start { deviceSettingsChanged() }
        val reconcile = paused == null && staleAfterWithdrawal && consent.given == true
        // A paused engine already holds the withdrawal's effects in memory; a reconciled one has them applied.
        if (reconcile || paused != null) staleAfterWithdrawal = false
        for (other in registry.all()) {
            if (other !== engine && other.withdrawal != null) {
                // Paused by the withdrawal for an app id the app no longer uses: it finishes its logouts, then stops.
                other.task {
                    other.resumeAfterConsent()
                    other.enterDrainMode()
                }
            }
        }
        val replayed = ArrayList(buffer.calls)
        engine.task {
            if (paused != null) engine.resumeAfterConsent() else engine.start()
            if (reconcile) engine.reconcileAfterWithdrawal()
            engine.promoteToMain()
            // No change at the process's first start; after an app id switch or a withdrawal in this process, observers
            // hear what changed since the state they last received.
            engine.publishBaseline()
            (buffer.token ?: lastToken)?.let { engine.onToken(it) }
            if (lastToken == null) lastToken = engine.device.push.token
            for (call in replayed) apply(engine, call)
            for (event in buffer.events) engine.recordEvent(event.notificationId, event.type, event.atSeconds, event.actionId)
            for (event in buffer.opens) engine.recordEvent(event.notificationId, event.type, event.atSeconds, event.actionId)
            reportDroppedCalls()
            buffer.clear()
            if (lifecycle?.isForeground == true) engine.onForeground()
        }
        // The task published the snapshot that contains the replayed calls.
        for (call in replayed) pendingWrites.remove(call)
        saveLastInit(platform, LastInit(key.appId, baseUrl, levelName(logger.consoleLevel), config.autoUpdateBadge))
        fetchFirebaseToken(platform)
        startDrainEngines(platform, key)
        scheduleForwardingHint(platform)
    }

    /**
     * Writes last_init.json only when it changes: it is read once per process, so a steady-state launch costs no
     * synced write on the engine thread before its first tasks.
     */
    private fun saveLastInit(platform: CorePlatform, value: LastInit) {
        if (!lastInitRead) {
            lastInitRead = true
            lastInitOnDisk = platform.globalStore.loadLastInit()
        }
        if (lastInitOnDisk == value) return
        if (platform.globalStore.saveLastInit(value)) lastInitOnDisk = value
    }

    private fun createEngine(platform: CorePlatform, key: EngineKey, baseUrl: String, mode: EngineMode): Engine {
        val engineEnv = EngineEnv(
            clock = env.clock,
            jitter = env.jitter,
            runner = env.engineRunner,
            network = platform.network,
            files = platform.files,
            secretBox = platform.secretBox,
            logger = logger,
            device = platform.device,
            permission = platform.permission,
            work = platform.work,
        )
        return Engine(key, baseUrl, mode, engineEnv, this)
    }

    private fun startDrainEngines(platform: CorePlatform, current: EngineKey) {
        val exclude = HashSet(registry.keys())
        exclude.add(current)
        for (candidate in DrainScanner.scan(platform.files, exclude)) {
            val engine = registry.obtain(candidate.key) { createEngine(platform, candidate.key, candidate.apiBaseUrl, EngineMode.DRAIN_ONLY) }
            if (engine.started) continue
            logger.log(LogCode.INIT_DRAIN_STARTED, mapOf(Meta.APP_ID to candidate.key.appId)) {
                "Finishing a pending logout for app ${candidate.key.appId} from an earlier session."
            }
            engine.task { engine.start() }
        }
    }

    private fun fetchFirebaseToken(platform: CorePlatform) {
        if (!consent.satisfied) return
        platform.firebase.fetch { result ->
            firebaseOutcome = result
            when (result) {
                is FirebaseToken.Token -> {
                    val token = result.value.trim()
                    if (isValidFcmToken(token)) routeToken(token)
                }
                is FirebaseToken.FirebaseMissing -> logger.log(LogCode.TOKEN_FIREBASE_MISSING) {
                    "Firebase is not initialized; add google-services.json. SallaPush waits for onNewToken."
                }
                is FirebaseToken.AutoInitDisabled -> logger.log(LogCode.TOKEN_AUTO_INIT_DISABLED) {
                    "Firebase auto-init is disabled; SallaPush waits for onNewToken."
                }
                is FirebaseToken.Failed -> logger.log(LogCode.TOKEN_FETCH_FAILED) {
                    "Could not read the FCM token (${result.reason}); waiting for onNewToken."
                }
            }
        }
    }

    /** The SDK's service, or an app service extending it (it forwards through super). */
    private fun isSdkService(className: String): Boolean {
        val sdkService = SallaPushFirebaseMessagingService::class.java
        if (className == sdkService.name) return true
        return try {
            sdkService.isAssignableFrom(Class.forName(className, false, sdkService.classLoader))
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * INFO fcm.forwarding_hint once per process when FCM is resolved to another service and nothing was forwarded in
     * this process yet. A correctly forwarding app sees it on launches without a token change or a message, so it is
     * worded as a check, not as an error.
     */
    private fun scheduleForwardingHint(platform: CorePlatform) {
        if (fcmHintScheduled) return
        fcmHintScheduled = true
        env.engineRunner.schedule(FORWARDING_HINT_DELAY_MILLIS, Runnable {
            if (forwardedSomething) return@Runnable
            val service = platform.fcmServiceName() ?: return@Runnable
            if (isSdkService(service)) return@Runnable
            logger.log(LogCode.FCM_FORWARDING_HINT) {
                "FCM messages go to $service. If it does not forward them to SallaPush yet, call " +
                    "SallaPush.Notifications.handleFirebaseMessage(context, message) first in onMessageReceived, and " +
                    "SallaPush.Notifications.onNewToken(context, token) in onNewToken."
            }
        })
    }

    // ── Consent ──

    fun setConsentRequired(required: Boolean) {
        submit {
            consent.requiredByCall = required
            val platform = platform
            if (platform != null && bootstrapped) loadConsentIfNeeded(platform)
            retryConsentWrite()
            reevaluateConsent()
        }
    }

    fun setConsentGiven(given: Boolean) {
        submit {
            // consent.json is read first, so withdrawing a consent it still holds is a withdrawal.
            val platform = platform
            if (platform != null && bootstrapped) loadConsentIfNeeded(platform)
            val before = consent.given
            consent.given = given
            appAnsweredConsent = true
            if (given) {
                appGaveConsent = true
                withdrawalPending = false
            } else if (before == true) {
                // Consent that was given is withdrawn: carried out as soon as consent is required.
                withdrawalPending = true
            }
            if (platform != null && bootstrapped && consent.loaded) {
                persistConsent(platform, given)
            } else {
                // Written once consent.json was read: at bootstrap, or when it can be read again.
                pendingConsentWrite = given
            }
            if (given && before != true) logger.log(LogCode.CONSENT_GIVEN) { "Consent given; SallaPush starts." }
            reevaluateConsent()
        }
    }

    /**
     * Writes consent.json. When that fails, a withdrawal removes the old file, so a later launch waits for consent
     * instead of reading an old "given"; the answer is written again at the next consent call, initWithContext or
     * foreground. storage.failed is logged once until a write succeeds.
     */
    private fun persistConsent(platform: CorePlatform, given: Boolean) {
        if (platform.globalStore.saveConsent(given)) {
            pendingConsentWrite = null
            consentWriteFailureLogged = false
            return
        }
        pendingConsentWrite = given
        val removed = !given && platform.globalStore.removeConsent()
        if (consentWriteFailureLogged) return
        consentWriteFailureLogged = true
        logger.log(LogCode.STORAGE_FAILED) {
            "Could not write SallaPush consent (consent.json)" +
                (if (removed) "; the old answer was removed, so the next launch waits for consent" else "") +
                "; it is written again at the next consent call, initWithContext or foreground."
        }
    }

    private fun retryConsentWrite() {
        val given = pendingConsentWrite ?: return
        val platform = platform ?: return
        if (bootstrapped && consent.loaded) persistConsent(platform, given)
    }

    private fun reevaluateConsent() {
        if (disabled) return
        val engine = mainEngine
        if (consent.satisfied) {
            if (engine != null) {
                // Consent given while the engine runs on files that may predate a withdrawal read at launch.
                val reconcile = staleAfterWithdrawal && consent.given == true
                if (reconcile) staleAfterWithdrawal = false
                engine.task {
                    if (reconcile) engine.reconcileAfterWithdrawal()
                    engine.refreshPermissionNow()
                }
                return
            }
            val init = pending ?: return
            val platform = platform ?: return
            if (!bootstrapped) return
            startMainEngine(platform, init.key, init.baseUrl)
            mainEngine?.let { started -> started.task { started.refreshPermissionNow() } }
            return
        }
        if (engine != null) {
            mainEngine = null
            platform?.deviceSettings?.stop()
            if (consent.given == false) {
                // The engine stays registered and paused: consent given again in this process resumes it. Until then it
                // is still the app's engine: the getters keep its state, which loses the external id and the tags now.
                withdrawalPending = false
                withdrawnEngine = engine
                engine.task { engine.withdrawConsent() }
            } else {
                // Stopped while consent is pending; the getters keep the state it last published (as on iOS).
                logConsentWaiting()
                engine.stop()
            }
        } else {
            val init = pending
            val platform = platform
            if (withdrawalPending && init != null && platform != null) {
                // consent.json, read only now, holds the consent the app withdrew while initWithContext waited for it.
                startWithdrawalEngine(platform, init.key, init.baseUrl)
            } else {
                // No main engine: initWithContext is still to come, or it waited for a consent that was not given
                // since. A withdrawal of consent given before is carried out by initWithContext ([startWithdrawalEngine]).
                logConsentWaiting()
            }
        }
        for (other in registry.all()) {
            if (other !== engine && other.mode.isDrain) other.stop()
        }
    }

    /**
     * Consent that was given (at an earlier launch, or earlier in this process) was withdrawn while no engine ran:
     * the engine of the app id and API host being initialised starts only to carry the withdrawal out on
     * this app's files: its unique works are cancelled, the minimised state is written once, and one PUT
     * {opted_in: false} and, when the device may be linked, one unlink are sent, retried in this process only. It then
     * stays paused, writing nothing, until consent is given again.
     */
    private fun startWithdrawalEngine(platform: CorePlatform, key: EngineKey, baseUrl: String) {
        withdrawalPending = false
        staleAfterWithdrawal = true
        val engine = registry.obtain(key) { createEngine(platform, key, baseUrl, EngineMode.WITHDRAWN) }
        // It is the app's engine while consent is withdrawn: the getters show the state it loaded (the baseline), then
        // what the withdrawal leaves of it (no external id, no tags), as on iOS.
        withdrawnEngine = engine
        engine.task {
            engine.start()
            engine.publishBaseline()
            engine.withdrawConsent()
        }
    }

    private fun logConsentWaiting() {
        if (consent.waitingLogged) return
        consent.waitingLogged = true
        logger.log(LogCode.CONSENT_WAITING) { "Waiting for setConsentGiven(true); nothing is sent or stored until then." }
    }

    // ── Identity, tags, subscription ──

    /**
     * Both arguments are trimmed and checked on the caller thread (as on iOS): an invalid one makes the call a no-op with
     * a WARN, reported to no observer. The token's format is the push service's to check.
     */
    fun login(rawExternalId: String, rawIdentityToken: String) {
        if (warnDisabled("login")) return
        val externalId = rawExternalId.trim()
        if (!IdentityRules.isExternalId(externalId)) {
            EntryGuard.invalidArgument("login", "the external id must be 1-128 characters")
            return
        }
        val token = rawIdentityToken.trim()
        if (!IdentityRules.isIdentityToken(token)) {
            EntryGuard.invalidArgument("login", "the identity token must be 1-4096 characters")
            return
        }
        send(BufferedCall.Login(externalId, SecretString(token)))
    }

    fun logout() {
        if (warnDisabled("logout")) return
        send(BufferedCall.Logout())
    }

    /** addTag ([api] "addTag") and addTags: entries with a null or empty key are ignored, each call says so once. */
    fun addTags(tags: Map<*, *>, api: String) {
        if (warnDisabled(api)) return
        val copy = LinkedHashMap<String, String>()
        var emptyKeys = 0
        for ((key, value) in tags) {
            when {
                key !is String || value !is String ->
                    logger.log(LogCode.API_INVALID_ARGUMENT) { "$api ignored an entry with a null key or value." }
                key.isBlank() -> emptyKeys += 1
                else -> copy[key] = value
            }
        }
        reportEmptyKeys(api, emptyKeys, tags.size)
        if (copy.isEmpty()) return
        send(BufferedCall.AddTags(copy))
    }

    /** removeTag ([api] "removeTag") and removeTags: null or empty keys are ignored, each call says so once. */
    fun removeTags(keys: Collection<*>, api: String) {
        if (warnDisabled(api)) return
        val copy = ArrayList<String>()
        var emptyKeys = 0
        for (key in keys) {
            when {
                key !is String -> logger.log(LogCode.API_INVALID_ARGUMENT) { "$api ignored a null key." }
                key.isBlank() -> emptyKeys += 1
                else -> copy.add(key)
            }
        }
        reportEmptyKeys(api, emptyKeys, keys.size)
        if (copy.isEmpty()) return
        send(BufferedCall.RemoveTags(copy))
    }

    /**
     * WARN api.invalid_argument for the [empty] entries of a tag call of [total] whose key is empty or blank (as on
     * iOS, and as addTag says): they never reach the tag rules, so no tags.invalid_key line names them. The others go on.
     */
    private fun reportEmptyKeys(api: String, empty: Int, total: Int) {
        if (empty == 0) return
        logger.log(LogCode.API_INVALID_ARGUMENT, mapOf(Meta.COUNT to empty.toString())) {
            when {
                empty == total && empty == 1 -> "$api ignored: the key is empty."
                empty == total -> "$api ignored: the keys are empty."
                empty == 1 -> "$api ignored 1 entry: the key is empty."
                else -> "$api ignored $empty entries: the keys are empty."
            }
        }
    }

    /**
     * The app's language is sent as the app gives it (as on iOS): trimmed and cut to 16 UTF-16 units on
     * whole code points. Null or blank means the device language, whose language part is sent ("ar-SA" → "ar").
     */
    fun setLanguage(language: String?) {
        if (warnDisabled("setLanguage")) return
        val normalized = cleanField(language, AndroidDeviceInfo.MAX_LANGUAGE)
        send(BufferedCall.Language(normalized))
    }

    fun setOptedOut(optedOut: Boolean) {
        if (warnDisabled(if (optedOut) "optOut" else "optIn")) return
        send(BufferedCall.OptedOut(optedOut))
    }

    /**
     * Clears the opt-out, and prompts for the permission when it can still be requested (OneSignal v5 parity). Before
     * initWithContext, or while the app is not in the foreground, the prompt is remembered and shown once at the next
     * foreground, unless the permission was granted or permanently denied by then (INFO permission.prompt_deferred).
     * Below Android 13 there is no prompt to show.
     */
    fun optIn() {
        setOptedOut(false)
        if (disabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val platform = platform
        val tracker = lifecycle
        if (!isInitialized || platform == null || tracker == null) {
            deferOptInPrompt("optIn() was called before initWithContext")
            return
        }
        if (!permissionRequests.canRequest(platform.context)) return
        if (tracker.isForeground) {
            requestPermissionNow(platform, tracker, fallbackToSettings = false, callback = null)
        } else {
            deferOptInPrompt("optIn() was called while the app is not in the foreground")
        }
    }

    /** Remembers the prompt for the next foreground; said once per deferral, however many optIn() calls ask for it. */
    private fun deferOptInPrompt(why: String) {
        if (pendingOptInPrompt.getAndSet(true)) return
        logger.log(LogCode.PERMISSION_PROMPT_DEFERRED) {
            "$why; the notification permission prompt is shown at the next foreground, unless the permission is " +
                "granted or permanently denied by then."
        }
    }

    /** Shows the prompt optIn() remembered, now that the app is in the foreground. Main thread. */
    private fun promptIfPending() {
        if (!pendingOptInPrompt.get() || disabled) return
        val platform = platform ?: return
        val tracker = lifecycle ?: return
        if (!tracker.isForeground || !pendingOptInPrompt.compareAndSet(true, false)) return
        if (!permissionRequests.canRequest(platform.context)) return
        requestPermissionNow(platform, tracker, fallbackToSettings = false, callback = null)
    }

    /** The state observers last received from the app's engine; kept while consent is withheld. */
    fun snapshot(): Snapshot = delivered

    /**
     * Notifications.permission: the main engine's last read while it runs (it re-reads at every resume), else a read
     * now, since no engine re-reads it while consent is withheld or before initWithContext.
     */
    fun permissionNow(): Boolean {
        val shown = delivered
        if (mainEngine != null && shown.loaded) return shown.permission
        return platform?.context?.let { NotificationPermission.allowed(it) } ?: false
    }

    /** What the public getters show: [snapshot] with the calls the engine has not applied yet ([PendingWrites]). */
    fun view(): Snapshot = pendingWrites.view({ snapshot() }, config.clearTagsOnLogout)

    /** Records [call] for the getters on the caller's thread, then hands it to the engine thread. */
    private fun send(call: BufferedCall) {
        pendingWrites.add(call)
        if (!env.engineRunner.execute(Runnable { route(call) })) pendingWrites.remove(call)
    }

    private fun route(call: BufferedCall) {
        if (disabled) {
            pendingWrites.remove(call)
            return
        }
        val engine = mainEngine
        if (engine != null && engine.started && !engine.stopped) {
            engine.task { apply(engine, call) }
            // The task published the snapshot that contains the call.
            pendingWrites.remove(call)
            return
        }
        // Calls the buffer drops to make room are reported once, with their number, when an engine takes the buffer.
        for (removed in buffer.add(call)) pendingWrites.remove(removed)
    }

    /** WARN init.buffer_full once per replay of the buffer, with the total it dropped (as on iOS). Engine thread. */
    private fun reportDroppedCalls() {
        val calls = buffer.droppedCalls
        val events = buffer.droppedEvents
        val total = calls + events
        if (total == 0) return
        val parts = ArrayList<String>(2)
        if (calls > 0) parts.add("$calls tag/language/opt-in call${if (calls == 1) "" else "s"}")
        if (events > 0) parts.add("$events notification event${if (events == 1) "" else "s"}")
        val verb = if (total == 1) "was" else "were"
        logger.log(LogCode.INIT_BUFFER_FULL, mapOf(Meta.COUNT to total.toString())) {
            "Too many calls before SallaPush started; ${parts.joinToString(" and ")} $verb dropped " +
                "(login/logout calls are kept)."
        }
    }

    private fun apply(engine: Engine, call: BufferedCall) {
        when (call) {
            is BufferedCall.Login -> engine.login(call.externalId, call.token)
            is BufferedCall.Logout -> engine.logout()
            is BufferedCall.AddTags -> engine.addTags(call.tags)
            is BufferedCall.RemoveTags -> engine.removeTags(call.keys)
            is BufferedCall.Language -> engine.setLanguage(call.language)
            is BufferedCall.OptedOut -> engine.setOptedOut(call.optedOut)
        }
    }

    private fun routeToken(token: String) {
        if (disabled) return
        lastToken = token
        val engine = mainEngine
        if (engine != null && engine.started && !engine.stopped) engine.task { engine.onToken(token) } else buffer.token = token
    }

    /** Records an event from any thread; it is buffered until the engine runs. */
    fun recordEvent(notificationId: String, type: String, atSeconds: Double, actionId: String?) {
        if (!MessageId.isValid(notificationId)) return
        submit {
            if (disabled) return@submit
            val engine = mainEngine
            if (engine != null && engine.started && !engine.stopped) {
                engine.task { engine.recordEvent(notificationId, type, atSeconds, actionId) }
            } else {
                buffer.addEvent(BufferedEvent(notificationId, type, atSeconds, actionId))
            }
        }
    }

    // ── FCM ──

    fun onNewToken(context: Context, token: String) {
        forwardedSomething = true
        if (warnDisabled("onNewToken")) return
        ensurePlatform(context)
        val trimmed = token.trim()
        if (!isValidFcmToken(trimmed)) {
            logger.log(LogCode.TOKEN_INVALID) { "The push token passed to SallaPush is empty or malformed; ignored." }
            return
        }
        submit { routeToken(trimmed) }
    }

    fun isSallaPushMessage(data: Map<*, *>?): Boolean = PayloadParser.isSallaPush(data)

    /**
     * Foreign → false at once. SallaPush → record received, apply the opt-out, run the foreground
     * listeners (inline on the main looper, else posted with a 500 ms wait), display, record displayed, true.
     * Works before initialisation: display needs no app id and events wait in memory.
     */
    fun handleFirebaseMessage(context: Context, data: Map<*, *>?): Boolean {
        forwardedSomething = true
        val parsed = PayloadParser.parse(data)
        if (parsed == null) {
            logger.log(LogCode.NOTIF_FOREIGN) { "Ignoring a notification from another SDK." }
            return false
        }
        val platform = ensurePlatform(context)
        if (parsed.unreadableFields.isNotEmpty()) {
            logger.log(LogCode.NOTIF_PARSE_FAILED) {
                "A SallaPush notification could not be fully parsed (${parsed.unreadableFields.joinToString(", ")}); the readable fields are used."
            }
        }
        if (parsed.unknownVersion && unknownVersionWarned.compareAndSet(false, true)) {
            logger.log(LogCode.NOTIF_UNKNOWN_VERSION) {
                "A SallaPush notification uses payload version ${printableAscii(truncatedUtf16(parsed.rawPayload["sp_v"] ?: "", 8))}; parsed with version 1 rules. Update the SDK."
            }
        }
        val notification = createNotification(parsed, null)
        notification.notificationId?.let { recordEvent(it, EVENT_RECEIVED, env.clock.nowSeconds(), null) }
        if (parsed.source == SallaPushPayloadSource.COMPAT && !displayCompatPayloads) return true
        val onMain = env.mainThread.isCurrent()
        if (isOptedOut(allowWait = !onMain)) {
            logger.log(LogCode.NOTIF_SUPPRESSED) { "Notification not shown (opted out)." }
            return true
        }
        val nonce = UUID.randomUUID().toString()
        val show: () -> Unit = { env.displayRunner.execute(Runnable { displayNow(platform, notification, nonce) }) }
        if (runForegroundListeners(notification, onMain, show)) return true
        if (onMain) {
            env.displayRunner.execute(Runnable { displayNow(platform, notification, nonce) })
        } else {
            displayNow(platform, notification, nonce)
        }
        return true
    }

    private fun displayNow(platform: CorePlatform, notification: SallaPushNotification, nonce: String) {
        val metaData = resolveManifest(platform)
        val result = platform.displayer.show(platform.context, notification, nonce, metaData, channelBadges(platform))
        if (result == DisplayResult.SHOWN) {
            notification.notificationId?.let { recordEvent(it, EVENT_DISPLAYED, env.clock.nowSeconds(), null) }
        }
    }

    /**
     * Whether a channel SallaPush creates for a notification shows badges (autoUpdateBadge): the configuration once
     * initWithContext applied it in this process; before that, in a process FCM started for a push, the setting the app
     * last started SallaPush with (last_init.json, read once, on the thread that displays the push), else the default.
     */
    private fun channelBadges(platform: CorePlatform): Boolean {
        if (configured) return config.autoUpdateBadge
        savedAutoUpdateBadge?.let { return it }
        synchronized(savedBadgeLock) {
            savedAutoUpdateBadge?.let { return it }
            val saved = try {
                platform.globalStore.loadLastInit()?.autoUpdateBadge
            } catch (e: Exception) {
                null
            } ?: BadgeDefaults.AUTO_UPDATE_BADGE
            savedAutoUpdateBadge = saved
            return saved
        }
    }

    /**
     * The user's opt-out as the getters show it (as on iOS): the app engine's state, also while consent is withheld,
     * with an optOut() or optIn() the engine has not applied yet. Off the main thread, a state still loading is awaited.
     */
    private fun isOptedOut(allowWait: Boolean): Boolean {
        if (allowWait && isInitialized && !delivered.loaded) awaitEngineThread(ENGINE_WAIT_MILLIS)
        return view().userOptedOut
    }

    /** Waits until every task queued on the engine thread so far has run (bounded). */
    private fun awaitEngineThread(timeoutMillis: Long) {
        val latch = CountDownLatch(1)
        if (!env.engineRunner.execute(Runnable { latch.countDown() })) return
        try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * True when a foreground listener prevented the display in time; [show] is what a later display() runs. The display
     * handle exists before the listeners run, so display() works from inside a listener too (as on iOS).
     */
    private fun runForegroundListeners(
        notification: SallaPushNotification,
        onMain: Boolean,
        show: () -> Unit,
    ): Boolean {
        if (observers.foreground.isEmpty() || lifecycle?.isForeground != true) return false
        val event = SallaPushWillDisplayEvent(notification)
        val handle = FacadeSupport.DisplayHandle(env.clock.nowMillis(), event, show)
        notification.displayHandle = handle
        if (onMain) {
            invokeForegroundListeners(event)
        } else {
            val latch = CountDownLatch(1)
            env.mainThread.post(Runnable {
                try {
                    invokeForegroundListeners(event)
                } finally {
                    latch.countDown()
                }
            })
            try {
                latch.await(FOREGROUND_LISTENER_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return handle.decide()
    }

    private fun invokeForegroundListeners(event: SallaPushWillDisplayEvent) {
        for (listener in observers.foreground) {
            try {
                listener.onWillDisplay(event)
            } catch (t: Throwable) {
                logger.log(LogCode.API_CALLBACK_THREW) {
                    "The listener passed to Notifications.addForegroundLifecycleListener threw ${AppCallbacks.failure(t)}; the other listeners still ran."
                }
            }
        }
    }

    fun trackOpened(additionalData: JSONObject?): Boolean {
        val pushRef = additionalData?.stringOrNull("push_ref") ?: return false
        if (!MessageId.isValid(pushRef)) return false
        recordEvent(pushRef, EVENT_OPENED, env.clock.nowSeconds(), null)
        return true
    }

    fun createNotification(parsed: ParsedPayload, androidNotificationId: Int?): SallaPushNotification = SallaPushNotification(
        notificationId = parsed.notificationId,
        androidNotificationId = androidNotificationId ?: stableId(parsed.collapseId ?: parsed.notificationId ?: UUID.randomUUID().toString()),
        title = parsed.title,
        subtitle = parsed.subtitle,
        body = parsed.body,
        imageUrl = parsed.imageUrl,
        launchUrl = parsed.launchUrl,
        additionalData = java.util.Collections.unmodifiableMap(LinkedHashMap(parsed.additionalData)),
        collapseId = parsed.collapseId,
        channelId = parsed.channelId,
        sound = parsed.sound,
        source = parsed.source,
        rawPayload = java.util.Collections.unmodifiableMap(LinkedHashMap(parsed.rawPayload)),
    )

    // ── Notification taps and dismissals ──

    /**
     * The open activity's work: records `opened`, dispatches the click, and returns the intents to start, in order of
     * preference: the https launch URL when configured to open it, then the app's launch intent, which the activity
     * starts when nothing can open the URL.
     */
    fun onNotificationOpened(context: Context, payload: Map<String, String>, androidNotificationId: Int, nonce: String): List<Intent> {
        val platform = ensurePlatform(context)
        val parsed = PayloadParser.parse(payload)
        var launchUrl: String? = null
        var notificationId: String? = null
        if (parsed != null) {
            val notification = createNotification(parsed, androidNotificationId)
            notificationId = notification.notificationId
            launchUrl = notification.launchUrl
            notificationId?.let { recordEvent(it, EVENT_OPENED, env.clock.nowSeconds(), null) }
            val click = QueuedClick(
                SallaPushClickEvent(notification, SallaPushClickResult(null, notification.launchUrl)),
                nonce,
                env.clock.nowMillis(),
                parsed.rawPayload,
                androidNotificationId,
            )
            env.mainThread.post(Runnable { clicks.dispatch(click) })
        }
        val intents = ArrayList<Intent>(2)
        if (config.openLaunchUrls && launchUrl != null && isHttps(launchUrl)) urlIntent(launchUrl)?.let { intents.add(it) }
        appLaunchIntent(platform.context, notificationId, payload)?.let { intents.add(it) }
        return intents
    }

    private fun urlIntent(launchUrl: String): Intent? = try {
        Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (e: Exception) {
        null
    }

    private fun appLaunchIntent(context: Context, notificationId: String?, payload: Map<String, String>): Intent? {
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        } catch (e: Exception) {
            null
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        notificationId?.let { intent.putExtra(EXTRA_LAUNCH_NOTIFICATION_ID, it) }
        intent.putExtra(EXTRA_LAUNCH_PAYLOAD, NotificationDisplayer.payloadBundle(payload))
        return intent
    }

    private fun isHttps(url: String): Boolean = try {
        asciiLowercase(Uri.parse(url).scheme ?: "") == "https"
    } catch (e: Exception) {
        false
    }

    fun onNotificationDismissed(context: Context, notificationId: String?) {
        ensurePlatform(context)
        if (notificationId != null) recordEvent(notificationId, EVENT_DISMISSED, env.clock.nowSeconds(), null)
    }

    private fun persistClicks(clicks: List<QueuedClick>) {
        val pendingClicks = clicks.map { PendingClick(it.nonce, it.atMillis / 1000.0, it.androidNotificationId, it.payload) }
        submit {
            val engine = mainEngine ?: return@submit
            engine.task {
                engine.device.pendingClicks.clear()
                engine.device.pendingClicks.addAll(pendingClicks)
                engine.stateDirty = true
            }
        }
    }

    fun clearAllNotifications() {
        val platform = platform ?: return
        env.displayRunner.execute(Runnable { platform.displayer.clearAll(platform.context) })
    }

    fun removeNotification(androidNotificationId: Int) {
        val platform = platform ?: return
        env.displayRunner.execute(Runnable { platform.displayer.remove(platform.context, androidNotificationId) })
    }

    // ── Permission ──

    fun canRequestPermission(): Boolean {
        val platform = platform ?: return false
        return permissionRequests.canRequest(platform.context)
    }

    fun requestPermission(fallbackToSettings: Boolean, callback: ((Boolean) -> Unit)?) {
        val platform = platform
        if (!isInitialized || platform == null) {
            logger.log(LogCode.API_INVALID_ARGUMENT) { "requestPermission ignored: call initWithContext first." }
            if (callback != null) env.mainThread.post(Runnable { safeCallback(callback, false) })
            return
        }
        val tracker = lifecycle
        if (tracker != null && tracker.state == AppLifecycle.State.UNKNOWN) {
            // Initialised in this main-thread turn: the decision waits until the foreground state is known.
            tracker.runWhenKnown(Runnable { requestPermissionNow(platform, tracker, fallbackToSettings, callback) })
            return
        }
        requestPermissionNow(platform, tracker, fallbackToSettings, callback)
    }

    private fun requestPermissionNow(platform: CorePlatform, tracker: AppLifecycle?, fallbackToSettings: Boolean, callback: ((Boolean) -> Unit)?) {
        val foreground = tracker?.isForeground == true
        if (!foreground && permissionRequests.canRequest(platform.context)) {
            logger.log(LogCode.API_INVALID_ARGUMENT) { "requestPermission ignored: the app is not in the foreground." }
        }
        permissionRequests.request(platform.context, fallbackToSettings, foreground) { granted ->
            afterPermissionRead { if (callback != null) safeCallback(callback, granted) }
        }
    }

    /**
     * Runs [deliver] on the main thread once the engine has re-read the permission and published it: inside the app's
     * callback (and after the suspend form resumes), Notifications.permission and pushSubscription.optedIn agree with
     * the answer.
     */
    private fun afterPermissionRead(deliver: () -> Unit) {
        val posted = env.engineRunner.execute(Runnable {
            val engine = mainEngine
            if (engine != null && engine.started && !engine.stopped) engine.task { engine.refreshPermissionNow() }
            env.mainThread.post(Runnable { deliver() })
        })
        if (!posted) env.mainThread.post(Runnable { deliver() })
    }

    private fun safeCallback(callback: (Boolean) -> Unit, granted: Boolean) {
        try {
            callback(granted)
        } catch (t: Throwable) {
            logger.log(LogCode.API_CALLBACK_THREW) {
                "The callback passed to Notifications.requestPermission threw ${AppCallbacks.failure(t)}; SallaPush continued."
            }
        }
    }

    fun onPermissionRequestFinished(requestId: Long, granted: Boolean) {
        permissionRequests.complete(requestId, granted)
    }

    fun refreshPermission() {
        submit {
            val engine = mainEngine ?: return@submit
            engine.task { engine.refreshPermissionNow() }
        }
    }

    // ── WorkManager ──

    /**
     * Starts one flush for a WorkManager run and returns at once (on WorkManager's main-thread call), creating the
     * engine when the app has not initialised. [done] runs once with the outcome (on the engine thread, or at once when
     * that thread cannot run); the run waits at most [maxWaitMillis], then ends with RETRY. The returned handle releases
     * the run when WorkManager stops it.
     * [consentRequired]: the work was enqueued while consent was required, so this run sends only when consent is known
     * to be given.
     */
    fun startWorkerFlush(
        context: Context,
        appIdInput: String?,
        baseUrlInput: String?,
        consentRequired: Boolean,
        maxWaitMillis: Long,
        done: (FlushOutcome) -> Unit,
    ): Cancellable {
        val run = WorkerRun(done)
        val platform = ensurePlatform(context)
        val accepted = env.engineRunner.execute(Runnable {
            try {
                attachWorkerRun(platform, appIdInput, baseUrlInput, consentRequired, maxWaitMillis, run)
            } catch (t: Throwable) {
                run.finish(FlushOutcome.SUCCESS)
            }
        })
        if (!accepted) run.finish(FlushOutcome.SUCCESS)
        return Cancellable {
            run.stopped = true
            // The engine thread owns the attached runs; a run that cannot be released there ends anyway.
            if (!env.engineRunner.execute(Runnable { releaseWorkerRun(run, FlushOutcome.SUCCESS) })) run.finish(FlushOutcome.SUCCESS)
        }
    }

    /** Engine thread: leaves the engine and ends the run with [outcome] (once). */
    private fun releaseWorkerRun(run: WorkerRun, outcome: FlushOutcome) {
        run.engine?.detachWorker(run)
        run.finish(outcome)
    }

    private fun attachWorkerRun(
        platform: CorePlatform,
        appIdInput: String?,
        baseUrlInput: String?,
        consentRequired: Boolean,
        maxWaitMillis: Long,
        run: WorkerRun,
    ) {
        // Consent is required per process and never persisted: a run in a new process that the app did not initialise
        // knows it only from its work. Then a missing consent.json (a withdrawal whose write failed removes it) or a
        // "withdrawn" one sends nothing.
        if (run.stopped || !bootstrap(platform) || consent.given == false || !consent.satisfied || (consentRequired && consent.given != true)) {
            run.finish(FlushOutcome.SUCCESS)
            return
        }
        if (!isInitialized) {
            ManifestConfig.parseLogLevel(platform.globalStore.loadLastInit()?.logLevel)?.let { logger.setConfigLevel(it) }
        }
        val target = workerTarget(platform, appIdInput, baseUrlInput)
        if (target == null) {
            run.finish(FlushOutcome.SUCCESS)
            return
        }
        val main = mainEngine
        val mode = when {
            main == null -> EngineMode.WORKER
            main.key == target.key -> EngineMode.MAIN
            else -> EngineMode.DRAIN_ONLY
        }
        val engine = registry.obtain(target.key) { createEngine(platform, target.key, target.baseUrl, mode) }
        if (engine.stopped || engine.withdrawal != null) {
            run.finish(FlushOutcome.SUCCESS)
            return
        }
        run.deadline = env.engineRunner.schedule(maxWaitMillis, Runnable { releaseWorkerRun(run, FlushOutcome.RETRY) })
        engine.task {
            engine.start()
            engine.flushOnce(run)
        }
    }

    private fun workerTarget(platform: CorePlatform, appIdInput: String?, baseUrlInput: String?): PendingInit? {
        val fromInput = if (appIdInput != null && Validation.isAppId(appIdInput)) {
            ApiBaseUrl.normalize(baseUrlInput)?.let { base -> HostTag.of(base)?.let { PendingInit(EngineKey(appIdInput, it), base) } }
        } else {
            null
        }
        if (fromInput != null) return fromInput
        val last = platform.globalStore.loadLastInit() ?: return null
        if (!Validation.isAppId(last.appId)) return null
        val base = ApiBaseUrl.normalize(last.apiBaseUrl) ?: return null
        val hostTag = HostTag.of(base) ?: return null
        return PendingInit(EngineKey(last.appId, hostTag), base)
    }

    // ── EngineHost ──

    override fun consentRequired(): Boolean = consent.required

    /**
     * token.missing names the likely cause: what Firebase answered when SallaPush read the token, else how FCM is
     * wired in this app (the service FCM resolves to is a PackageManager read: engine thread).
     */
    override fun tokenMissingAdvice(): String {
        when (val outcome = firebaseOutcome) {
            is FirebaseToken.FirebaseMissing ->
                return "Firebase is not initialized in this app (google-services.json missing?), so FCM issues no token."
            is FirebaseToken.AutoInitDisabled ->
                return "Firebase auto-init is disabled, so FCM issues no token until the app enables it; then forward " +
                    "the token with SallaPush.Notifications.onNewToken(context, token)."
            is FirebaseToken.Failed ->
                return "Reading the FCM token failed (${outcome.reason}); SallaPush waits for SallaPush.Notifications.onNewToken(context, token)."
            else -> Unit
        }
        val service = platform?.let { platform ->
            try {
                platform.fcmServiceName()
            } catch (e: Exception) {
                null
            }
        }
        return when {
            service == null ->
                "Forward FCM tokens with SallaPush.Notifications.onNewToken(context, token) from your FirebaseMessagingService."
            isSdkService(service) ->
                "FCM delivers tokens to $service, which forwards them to SallaPush, and none arrived: check google-services.json " +
                    "and that Google Play services are available on this device."
            else ->
                "FCM delivers tokens to $service: call SallaPush.Notifications.onNewToken(context, token) in its onNewToken."
        }
    }

    override fun clearTagsOnLogout(): Boolean = config.clearTagsOnLogout

    override fun isForeground(): Boolean = lifecycle?.isForeground == true

    /**
     * Engine thread. Only the app's engine is heard ([isAppEngine]). Observers get every change from the state they
     * last received, whichever engine produced it, and the getters show that state from then on. The process's first
     * baseline is the state an earlier launch left (as on iOS): user observers hear nothing, and
     * subscription observers get the replay form once. Later baselines (an app id switch, a resume after a withdrawal)
     * are real changes.
     */
    override fun onSnapshotChanged(engine: Engine, current: Snapshot, baseline: Boolean) {
        if (!isAppEngine(engine)) return
        val previous = delivered
        delivered = current
        if (baseline && !baselineRestored) {
            baselineRestored = true
            observers.restored(current)
            return
        }
        observers.snapshotChanged(previous, current)
    }

    override fun onLoginFailure(engine: Engine, failure: SallaPushLoginFailure) {
        if (isAppEngine(engine)) observers.loginFailed(failure)
    }

    override fun onPermissionChanged(engine: Engine, granted: Boolean) {
        if (isAppEngine(engine)) observers.permissionChanged(granted)
    }

    /**
     * Whether [engine] is the app's engine, whose state the getters show and observers hear: the main engine, or the
     * one a consent withdrawal paused (it stays the app's until consent is given again, as on iOS). Engine thread.
     */
    private fun isAppEngine(engine: Engine): Boolean = engine === mainEngine || engine === withdrawnEngine

    override fun onPendingClicksLoaded(engine: Engine, clicks: List<PendingClick>) {
        val restored = ArrayList<QueuedClick>()
        for (click in clicks) {
            val parsed = PayloadParser.parse(click.payload) ?: continue
            val notification = createNotification(parsed, click.androidNotificationId)
            restored.add(
                QueuedClick(
                    SallaPushClickEvent(notification, SallaPushClickResult(null, notification.launchUrl)),
                    click.nonce,
                    (click.at * 1000.0).toLong(),
                    click.payload,
                    click.androidNotificationId,
                ),
            )
        }
        env.mainThread.post(Runnable { this.clicks.restore(restored) })
    }

    override fun onNetworkFailure(engine: Engine) {
        val platform = platform ?: return
        platform.connectivity.start { submit { onConnectivityRegained(platform) } }
    }

    override fun onNetworkFailuresCleared(engine: Engine) {
        if (registry.all().none { it.hasNetworkFailedWork() }) platform?.connectivity?.stop()
    }

    private fun onConnectivityRegained(platform: CorePlatform) {
        for (engine in registry.all()) engine.task { engine.onConnectivityRegained() }
        if (registry.all().none { it.hasNetworkFailedWork() }) platform.connectivity.stop()
    }

    override fun onStopped(engine: Engine) {
        registry.remove(engine)
        if (mainEngine === engine) {
            mainEngine = null
            platform?.deviceSettings?.stop()
        }
        if (withdrawnEngine === engine) withdrawnEngine = null
        if (registry.all().none { it.hasNetworkFailedWork() }) platform?.connectivity?.stop()
    }

    /** The time zone or locale changed (the receiver runs on the main thread): the main engine sends the new values. */
    private fun deviceSettingsChanged() {
        submit {
            val engine = mainEngine ?: return@submit
            engine.task { engine.onDeviceSettingsChanged() }
        }
    }

    // ── ForegroundListener (main thread) ──

    override fun onForeground(afterScreenOff: Boolean) {
        promptIfPending()
        submit {
            val platform = platform
            if (platform != null && bootstrapped && loadConsentIfNeeded(platform)) reevaluateConsent()
            retryConsentWrite()
            if (!afterScreenOff) clearBadgeOnOpen()
            val engine = mainEngine ?: return@submit
            engine.task { engine.onForeground() }
        }
    }

    /**
     * autoClearBadge, when the app comes to the foreground (not after an unlock with the app on screen): SallaPush's
     * notifications that show on the badge leave the shade, on the display thread, as clearAllNotifications() does.
     * Nothing while SallaPush isn't initialised or consent is withheld. Engine thread.
     */
    private fun clearBadgeOnOpen() {
        if (disabled || !isInitialized || !config.autoClearBadge || !consent.satisfied) return
        val platform = platform ?: return
        env.displayRunner.execute(Runnable {
            val removed = platform.displayer.clearBadged(platform.context)
            if (removed > 0) {
                logger.log(LogCode.BADGE_CLEARED, mapOf(Meta.COUNT to removed.toString())) {
                    "Cleared the badge: removed $removed SallaPush notification${if (removed == 1) "" else "s"} from the " +
                        "shade as the app came to the foreground (autoClearBadge)."
                }
            }
        })
    }

    override fun onBackground() {
        submit {
            val engine = mainEngine ?: return@submit
            engine.task { engine.onBackground() }
        }
    }

    override fun onActivityResumed() {
        promptIfPending()
        submit {
            resumeTask?.cancel()
            resumeTask = env.engineRunner.schedule(RESUME_DEBOUNCE_MILLIS, Runnable {
                resumeTask = null
                val engine = mainEngine ?: return@Runnable
                engine.task { engine.onActivityResumed() }
            })
        }
    }

    // ── Helpers ──

    private fun submit(block: () -> Unit) {
        env.engineRunner.execute(Runnable { block() })
    }

    /**
     * True when the SDK is disabled for this process and every call is a no-op, said once: a WARN for an invalid app id
     * (a configuration error), a DEBUG line in a process other than the app's main one (a normal state).
     */
    private fun warnDisabled(api: String): Boolean {
        if (!disabled) return false
        if (disabledWarned.compareAndSet(false, true)) {
            if (disabledReason == DisabledReason.NOT_MAIN_PROCESS) {
                logger.log(LogCode.INIT_NOT_MAIN_PROCESS) { "$api ignored: this is not the app's main process; SallaPush only shows notifications here." }
            } else {
                logger.log(LogCode.API_INVALID_ARGUMENT) { "$api ignored: SallaPush is disabled because initWithContext received an invalid app id." }
            }
        }
        return true
    }

    private fun levelName(level: SallaPushLogLevel): String = asciiLowercase(level.name)

    companion object {
        const val EXTRA_LAUNCH_NOTIFICATION_ID = "sallapush_notification_id"
        const val EXTRA_LAUNCH_PAYLOAD = "sallapush_payload"
        private const val ENGINE_WAIT_MILLIS = 1_500L
        private const val FOREGROUND_LISTENER_WAIT_MILLIS = 500L
        private const val RESUME_DEBOUNCE_MILLIS = 500L
        private const val FORWARDING_HINT_DELAY_MILLIS = 10_000L
        private const val MAX_SHOWN_VALUE = 32

        /** A stable 32-bit id (FNV-1a over UTF-8) for NotificationManager.notify. */
        fun stableId(value: String): Int {
            var hash = FNV_OFFSET
            for (byte in value.encodeToByteArray()) {
                hash = hash xor (byte.toInt() and 0xff)
                hash *= FNV_PRIME
            }
            return hash
        }

        private const val FNV_OFFSET = -0x7ee3623b
        private const val FNV_PRIME = 0x01000193
    }
}
