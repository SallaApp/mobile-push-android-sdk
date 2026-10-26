package com.salla.push.internal.engine

import com.salla.push.SallaPushLoginFailure
import com.salla.push.internal.api.ApiClient
import com.salla.push.internal.api.ApiFailure
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.api.Network
import com.salla.push.internal.device.DeviceInfoSource
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import com.salla.push.internal.log.Meta
import com.salla.push.internal.storage.CredentialStore
import com.salla.push.internal.storage.CredentialsRead
import com.salla.push.internal.storage.EventStore
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.storage.IdentityStore
import com.salla.push.internal.storage.Loaded
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.internal.storage.SecretBox
import com.salla.push.internal.storage.StateStore
import com.salla.push.internal.util.Cancellable
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.InternalFailure
import com.salla.push.internal.util.Jitter
import com.salla.push.internal.util.Once
import com.salla.push.internal.util.TaskRunner
import com.salla.push.internal.util.nowSeconds
import com.salla.push.internal.work.WorkScheduler
import java.io.File

internal enum class EngineMode {
    /** The engine of the app id and API host the app initialised. */
    MAIN,

    /** Created by a WorkManager run before the app initialised in this process. */
    WORKER,

    /**
     * Started while consent is withheld, only to carry out the withdrawal of a consent given before (at an earlier
     * launch, or earlier in this process); it becomes the main engine when consent is given again.
     */
    WITHDRAWN,

    /** The previous main engine after an app id or host switch: finishes its logouts, then stops. */
    DRAINING,

    /** Created at launch for another app id/host folder that still has a pending logout. */
    DRAIN_ONLY,
    ;

    val isDrain: Boolean
        get() = this == DRAINING || this == DRAIN_ONLY
}

internal data class EngineKey(val appId: String, val hostTag: String) {
    override fun toString(): String = "$appId/$hostTag"
}

/**
 * What the public getters read: immutable, published after every engine task. The last three fields let the getters
 * apply calls the engine has not processed yet exactly as it will ([com.salla.push.internal.PendingWrites]).
 */
internal data class Snapshot(
    val loaded: Boolean,
    val deviceId: String?,
    val token: String?,
    val optedIn: Boolean,
    val userOptedOut: Boolean,
    val externalId: String?,
    val isLoggedIn: Boolean,
    val tags: Map<String, String>,
    val permission: Boolean,
    /** A logout now is free: tags stay. */
    val logoutIsFree: Boolean = false,
    /**
     * A logout that is not free unlinks the device (it leaves the device "not linked" only once answered). One that only
     * clears the tags leaves the logout after it free.
     */
    val logoutSends: Boolean = false,
    /**
     * While the identity is unknown (a first launch, a reinstall, a lost identity file, or the state not loaded yet):
     * the tags a logout() keeps, those this launch set. Null once the app's identity is known.
     */
    val launchTags: Map<String, String>? = null,
) {
    companion object {
        val EMPTY = Snapshot(false, null, null, false, false, null, false, emptyMap(), false, launchTags = emptyMap())
    }
}

/** How a WorkManager run ends. */
internal enum class FlushOutcome {
    /** Nothing is due, or the engine enqueued a follow-up run for the time the remaining work is due. */
    SUCCESS,

    /** Work remains after a short transient backoff: WorkManager runs the same work again after its own backoff. */
    RETRY,
}

/** When the engine next has something to send; [transient] marks a short retry backoff after a failure. */
internal class NextDue(val at: Double, val transient: Boolean)

/**
 * One WorkManager run waiting for an engine to settle. No thread waits for it: it is answered once, through
 * [onOutcome], when the engine is idle, or released early when WorkManager stops it or its bounded wait ends.
 */
internal class WorkerRun(private val onOutcome: (FlushOutcome) -> Unit) {

    private val once = Once()

    /** WorkManager stopped the run (any thread): it no longer attaches to an engine. */
    @Volatile
    var stopped = false

    /** The engine the run is attached to (engine thread). */
    @Volatile
    var engine: Engine? = null

    /** The run's bounded wait; cancelled when the run ends. */
    @Volatile
    var deadline: Cancellable? = null

    /** Ends the run with [outcome]; later calls do nothing. */
    fun finish(outcome: FlushOutcome) {
        if (!once.claim()) return
        deadline?.cancel()
        try {
            onOutcome(outcome)
        } catch (ignored: Throwable) {
            // Completing WorkManager's future cannot fail in a way SallaPush could act on.
        }
    }
}

/** What an engine needs from the process-wide SDK core. Called on the engine thread. */
internal interface EngineHost {
    fun clearTagsOnLogout(): Boolean

    fun isForeground(): Boolean

    /**
     * [engine] published [current]; the host ignores every engine but the one whose state the app sees. [baseline]
     * marks the state it starts from as that engine (the files it loaded, or the state it resumes): the first of the
     * process is no change for observers.
     */
    fun onSnapshotChanged(engine: Engine, current: Snapshot, baseline: Boolean)

    fun onLoginFailure(engine: Engine, failure: SallaPushLoginFailure)

    fun onPermissionChanged(engine: Engine, granted: Boolean)

    fun onPendingClicksLoaded(engine: Engine, clicks: List<PendingClick>)

    fun onNetworkFailure(engine: Engine)

    /** No op of this engine is waiting on a network failure any more. */
    fun onNetworkFailuresCleared(engine: Engine)

    fun onStopped(engine: Engine)

    /** Whether consent is required in this process: a WorkManager run then sends only after an explicit "given". */
    fun consentRequired(): Boolean

    /** What token.missing tells the app to check, from how FCM is wired in this app and the last Firebase answer. */
    fun tokenMissingAdvice(): String
}

/** The engine's dependencies; fakes replace them in tests. */
internal class EngineEnv(
    val clock: Clock,
    val jitter: Jitter,
    val runner: TaskRunner,
    val network: Network,
    val files: SdkFiles,
    val secretBox: SecretBox,
    val logger: Logger,
    val device: DeviceInfoSource,
    val permission: () -> Boolean,
    val work: WorkScheduler,
)

internal class EngineStores(
    val identity: IdentityStore,
    val state: StateStore,
    val events: EventStore,
    val credentials: CredentialStore,
)

/**
 * One engine per (app id, API host) per process. Owns all mutable SDK state for that key and its persistence, on the
 * single SallaPush-engine thread: every method must run on that thread (via [run] or the runner). Network calls go
 * through [Network], whose results come back on the same thread, so the engine never blocks on I/O beyond small files.
 */
internal class Engine(
    val key: EngineKey,
    val apiBaseUrl: String,
    initialMode: EngineMode,
    val env: EngineEnv,
    val host: EngineHost,
) {

    var mode: EngineMode = initialMode
        private set

    val api = ApiClient(env.network, env.clock, apiBaseUrl)
    val logger: Logger get() = env.logger
    val appId: String get() = key.appId

    var identity = IdentityState()
    var device = DeviceState()
    var events = EventsState()
    var credentials: Credentials? = null

    /** Null until every file was read: a file that could not be read is never replaced by an empty state. */
    var stores: EngineStores? = null

    /**
     * The identity token of the app's last login(), with the customer it is for. Memory only: never written or logged.
     * It links that customer, and links them again after a re-registration in this process.
     */
    var identityToken: IdentityToken? = null

    /** Incremented by every login and logout: the identity epoch a tag change is made in ([PendingTag]). */
    var epoch = 0L

    /**
     * The tags the app set in this launch while the identity is unknown (a first launch, a reinstall, a lost identity
     * file), at most 100: the launch logout() keeps those the getters still show (as on iOS). Memory only;
     * read only while the identity is unknown.
     */
    val launchTags = LinkedHashMap<String, String>()
    var permissionGranted = false
    var started = false
    var stopped = false
    var generation = 0
    var storageUnavailable = false

    /** False when events.json could not be read: it is then never written in this process. */
    var eventsWritable = true

    /** Credentials a registration answered while consent was withdrawn: kept in memory, written when consent is back. */
    var credentialsUnsaved = false

    /** Started while the app was not in the foreground: only identity ops and events are sent until it is. */
    var backgroundLaunch = false
    var inFlight: String? = null
    var nextOpId = 1L
    val processAttempts = HashMap<Long, Int>()
    val exhausted = HashSet<Long>()
    var eventsRetry = Retry()
    var eventsFlushRequested = false
    var eventsFirstPendingAt: Double? = null
    /** When the previous device-level re-registration ran or is held to run (its valve); this process only. */
    var lastReregisterAt: Double? = null

    /** The due time of the flush work this engine last enqueued in this process; null when it knows of none. */
    var flushWorkDueAt: Double? = null

    /** A [postEventsWork] task is queued and has not run yet: a burst of events posts one. */
    private var eventsWorkPosted = false

    /**
     * The due time of the flush work [scheduleEventsWork] enqueued, while [flushWorkDueAt] still holds that time: any
     * other enqueue, a run's end or a withdrawal rewrites [flushWorkDueAt], and the work is then no longer this path's to
     * cancel ([cancelIdleEventsWork]). Memory only.
     */
    var eventsWorkDueAt: Double? = null

    /** The device fields of the last registration or update the server accepted in this process (for update.sent). */
    var lastSentFields: SentFields? = null

    /** When the last tags request went out: in the foreground tags are sent at most every 10 s. */
    var lastTagsSentAt: Double? = null

    /** The taps persisted in state.json went to the click dispatcher (once: when this engine became the main one). */
    var pendingClicksRestored = false

    /**
     * A login that may have reached the server left the op log without a usable answer: a request of its link failed in
     * transit, timed out or got an unreadable 2xx, and a later call replaced it or push-service refused it. The next
     * logout then unlinks and is confirmed 60 s later. Memory only.
     */
    var unknownOutcomeLogin = false
    var tagSplit: ArrayDeque<String>? = null

    /** Set while consent is withdrawn: the final data-minimising requests run, then the engine stays idle (paused). */
    var withdrawal: Withdrawal? = null
    var queueFullLogged = false

    var redirectLogged = false
    private var networkFailurePending = false
    var dormantLogged = false
    var routesClosedLogged = false
    var tokenMissingLogged = false

    /** Files whose last write failed; storage.failed is logged once per file until a write succeeds again. */
    private val failedWrites = HashSet<String>()

    var wakeTask: Cancellable? = null
    var drainDeadline: Cancellable? = null
    var tokenMissingTimer: Cancellable? = null

    var identityDirty = false
    var stateDirty = false
    var eventsDirty = false

    @Volatile
    var snapshot: Snapshot = Snapshot.EMPTY
        private set

    /** The WorkManager runs waiting for this engine to settle; none of them holds a thread. */
    private val workerRuns = ArrayList<WorkerRun>()

    /** A WorkManager run is waiting for this engine: it flushes what the app left pending, in any app state. */
    val workerAttached: Boolean
        get() = workerRuns.isNotEmpty()

    fun now(): Double = env.clock.nowSeconds()

    /**
     * Runs [block] as one engine task: state changes are persisted, the snapshot published, then the queue pumped. The
     * engine guard: an unexpected throwable is logged as ERROR internal.error, and the engine goes on.
     */
    fun task(block: () -> Unit) {
        if (stopped) return
        try {
            block()
        } catch (t: Throwable) {
            logger.log(LogCode.INTERNAL_ERROR) { "SallaPush hit an internal error in an engine step (${InternalFailure.describe(t)}); that step was skipped." }
        }
        if (stopped) return
        try {
            persistDirty()
            publishSnapshot()
            pump()
            if (!stopped) {
                persistDirty()
                publishSnapshot()
                if (networkFailurePending && !hasNetworkFailedWork()) {
                    networkFailurePending = false
                    host.onNetworkFailuresCleared(this)
                }
            }
        } catch (t: Throwable) {
            logger.log(LogCode.INTERNAL_ERROR) {
                "SallaPush hit an internal error while saving, publishing or sending its work (${InternalFailure.describe(t)}); that step was skipped."
            }
        }
    }

    // ── Lifecycle ──

    fun start() {
        if (started || stopped) return
        started = true
        backgroundLaunch = !mode.isDrain && !host.isForeground()
        load()
        restoreReregistrationHold()
        permissionGranted = readPermission()
        nextOpId = computeNextOpId()
        if (mode.isDrain) armDrainDeadline()
        ensureRegisterOp()
    }

    /**
     * Becomes the app's main engine (a worker may have created it before the app initialised, or consent may have been
     * withdrawn before it started). The caller then publishes the baseline ([publishBaseline]).
     */
    fun promoteToMain() {
        if (mode == EngineMode.MAIN) return
        mode = EngineMode.MAIN
        drainDeadline?.cancel()
        drainDeadline = null
        backgroundLaunch = !host.isForeground()
        ensureRegisterOp()
        restorePendingClicks()
    }

    /**
     * Hands the taps persisted in state.json to the click dispatcher, once, when this engine is (or becomes) the main
     * engine: an engine a WorkManager run created first restores them when the app initialises.
     */
    private fun restorePendingClicks() {
        if (pendingClicksRestored || mode != EngineMode.MAIN || stores == null) return
        pendingClicksRestored = true
        if (device.pendingClicks.isNotEmpty()) host.onPendingClicksLoaded(this, ArrayList(device.pendingClicks))
    }

    /** App id or host switch: run only pending logout/unlink ops, then stop (at most [DRAIN_SECONDS]). */
    fun enterDrainMode() {
        if (mode.isDrain || stopped) return
        mode = EngineMode.DRAINING
        device.ops.removeAll { it.kind == DeviceOpKind.REGISTER }
        armDrainDeadline()
    }

    private fun armDrainDeadline() {
        drainDeadline?.cancel()
        val generationNow = generation
        drainDeadline = env.runner.schedule((DRAIN_SECONDS * 1000).toLong(), Runnable {
            if (generationNow == generation) stop()
        })
    }

    /** Cancels every timer of this engine (the drain deadline excepted). */
    fun cancelTimers() {
        wakeTask?.cancel()
        tokenMissingTimer?.cancel()
        wakeTask = null
        tokenMissingTimer = null
    }

    /**
     * The stop protocol: mark stopped and bump the generation (late callbacks become no-ops), cancel every timer,
     * persist, and detach. A request still in flight completes on the net thread and is ignored. While consent is
     * withdrawn nothing is written, and a store that could not be read is never written.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        generation += 1
        cancelTimers()
        drainDeadline?.cancel()
        drainDeadline = null
        if (withdrawal == null) {
            identityDirty = true
            stateDirty = true
            eventsDirty = true
            try {
                persistDirty()
            } catch (ignored: Throwable) {
                // Stopping must not fail; the last successful write stays on disk.
            }
        }
        inFlight = null
        answerWaiters(FlushOutcome.SUCCESS)
        host.onStopped(this)
    }

    private fun load() {
        val directory = env.files.appDirectory(key.appId, key.hostTag)
        if (directory == null) {
            storageFailure("storage directory unavailable")
            return
        }
        val origin = HostTag.origin(apiBaseUrl) ?: apiBaseUrl
        val identityStore = IdentityStore(env.files, directory, appId, apiBaseUrl)
        val stateStore = StateStore(env.files, directory, appId)
        val eventStore = EventStore(env.files, directory)
        val credentialStore = CredentialStore(env.files, env.secretBox, File(directory, CREDENTIALS_FILE), appId, origin)
        when (val loaded = identityStore.load()) {
            is Loaded.Ok -> identity = loaded.value
            is Loaded.Missing -> Unit
            is Loaded.Corrupt -> {
                identityDirty = true
                logger.log(LogCode.STORAGE_STATE_RESET) {
                    "SallaPush identity state was corrupt; it was reset (the app's next login or logout sets it again)."
                }
            }
            is Loaded.Unavailable -> {
                storageFailure(loaded.reason)
                return
            }
        }
        when (val loaded = stateStore.load()) {
            is Loaded.Ok -> device = loaded.value
            is Loaded.Missing -> Unit
            is Loaded.Corrupt -> {
                stateDirty = true
                logger.log(LogCode.STORAGE_STATE_RESET) { "SallaPush state was corrupt; it was reset (pending logouts were kept)." }
            }
            is Loaded.Unavailable -> {
                storageFailure(loaded.reason)
                return
            }
        }
        when (val loaded = eventStore.load()) {
            is Loaded.Ok -> events = loaded.value
            is Loaded.Missing -> Unit
            is Loaded.Corrupt -> {
                eventsDirty = true
                logger.log(LogCode.STORAGE_STATE_RESET) { "SallaPush pending events were corrupt; they were reset." }
            }
            is Loaded.Unavailable -> {
                eventsWritable = false
                logger.log(LogCode.STORAGE_FAILED) {
                    "Could not read SallaPush pending events (${loaded.reason}); new events are kept in memory only during this launch."
                }
            }
        }
        when (val read = credentialStore.load()) {
            is CredentialsRead.Found -> credentials = read.credentials
            is CredentialsRead.None -> credentials = null
            is CredentialsRead.Reset -> {
                credentials = null
                logger.log(LogCode.STORAGE_CRYPTO_RESET) { "Stored credentials could not be decrypted; registering again." }
            }
            is CredentialsRead.Unavailable -> {
                storageFailure(read.reason)
                return
            }
        }
        stores = EngineStores(identityStore, stateStore, eventStore, credentialStore)
        normalizeLoaded()
        restorePendingClicks()
    }

    private fun storageFailure(reason: String) {
        storageUnavailable = true
        logger.log(LogCode.STORAGE_FAILED) { "Could not read SallaPush state ($reason); this launch runs without network registration." }
    }

    private fun normalizeLoaded() {
        val now = now()
        val horizon = now + MAX_FUTURE_SECONDS
        for (op in identity.ops) clampRetry(op.retry, now, horizon)
        for (op in device.ops) clampRetry(op.retry, now, horizon)
        val registration = device.registration
        registration.dormantUntil?.let { if (it > horizon) registration.dormantUntil = horizon }
        // A dormancy without a reason is one of beta.1's that push-service never answers (DormantReason): it ends here.
        if (registration.status == RegStatus.DORMANT && registration.dormantReason == null) {
            registration.status = RegStatus.NONE
            registration.dormantUntil = null
            stateDirty = true
        }
        if (credentials == null) {
            if (registration.status == RegStatus.REGISTERED) {
                registration.status = RegStatus.NONE
                registration.registeredToken = null
                registration.sentFingerprint = null
                stateDirty = true
            }
        } else if (registration.status == RegStatus.NONE) {
            registration.status = RegStatus.REGISTERED
            registration.sentFingerprint = null
            stateDirty = true
        }
        for (tag in device.tags.pendingAdd.values) if (tag.epoch != 0L) tag.epoch = 0
        for (entry in device.tags.pendingRemove.entries) if (entry.value != 0L) entry.setValue(0L)
        device.pendingClicks.removeAll { now - it.at > CLICK_TTL_SECONDS || it.at > horizon }
        if (events.pending.isNotEmpty()) eventsFirstPendingAt = now
    }

    private fun clampRetry(retry: Retry, now: Double, horizon: Double) {
        if (retry.nextAt > horizon) retry.nextAt = now
        retry.dormantUntil?.let { if (it > horizon) retry.dormantUntil = now + DAY_SECONDS }
        if (retry.lastAttemptAt > horizon) retry.lastAttemptAt = now
    }

    private fun computeNextOpId(): Long {
        var max = 0L
        for (op in identity.ops) if (op.id > max) max = op.id
        for (op in device.ops) if (op.id > max) max = op.id
        return max + 1
    }

    fun allocateOpId(): Long = nextOpId++

    // ── Persistence and snapshot ──

    /**
     * Writes the dirty files. Nothing is written while consent is withdrawn, or to a store that could not be read.
     * state.json goes before identity.json: a logout or a switching login drops the tags in state.json and appends its op
     * to identity.json in one task. If the process dies between the two writes, the dropped tags are on disk without the
     * op, which is safe. The reverse would leave an op whose tags were not dropped, and the previous customer's tags
     * would follow it.
     */
    fun persistDirty() {
        if (withdrawal != null) return
        val stores = stores ?: return
        if (stateDirty) {
            stateDirty = false
            if (!recordWrite(StateStore.FILE_NAME, stores.state.save(device))) stateDirty = true
        }
        if (identityDirty) {
            identityDirty = false
            if (!recordWrite(IdentityStore.FILE_NAME, stores.identity.save(identity))) identityDirty = true
        }
        if (eventsDirty && eventsWritable) {
            eventsDirty = false
            if (!recordWrite(EventStore.FILE_NAME, stores.events.save(events))) eventsDirty = true
        }
    }

    /**
     * The consent withdrawal's one write (the one minimised state a withdrawal saves before its
     * requests; as iOS's persistCopies): state.json and events.json as in memory, and [identityCopy] as identity.json,
     * in [persistDirty]'s order. The in-memory state stays dirty, so consent given again writes it over these copies.
     */
    fun persistWithdrawalState(identityCopy: IdentityState) {
        val stores = stores ?: return
        recordWrite(StateStore.FILE_NAME, stores.state.save(device))
        recordWrite(IdentityStore.FILE_NAME, stores.identity.save(identityCopy))
        if (eventsWritable) recordWrite(EventStore.FILE_NAME, stores.events.save(events))
        stateDirty = true
        identityDirty = true
        eventsDirty = true
    }

    /** Writes credentials.bin; when that fails the credentials stay in memory and the next launch registers again. */
    fun saveCredentials(credentials: Credentials) {
        val stores = stores ?: return
        recordWrite(CREDENTIALS_FILE, stores.credentials.save(credentials), "the device credentials are kept in memory, and the next launch registers again")
    }

    /**
     * Logs storage.failed once per file until a write of that file succeeds again; returns [succeeded]. [outcome] says
     * what happens instead of the write.
     */
    fun recordWrite(file: String, succeeded: Boolean, outcome: String = "the change is kept in memory and written again on the next change"): Boolean {
        if (succeeded) {
            failedWrites.remove(file)
        } else if (failedWrites.add(file)) {
            logger.log(LogCode.STORAGE_FAILED) { "Could not write SallaPush state ($file); $outcome." }
        }
        return succeeded
    }

    /**
     * Publishes the getters' state when it changed. The host keeps it, and tells observers what changed, only for the
     * engine whose state the app sees: the main engine, or the one a consent withdrawal paused.
     */
    fun publishSnapshot() {
        val next = currentSnapshot()
        if (snapshot == next) return
        snapshot = next
        host.onSnapshotChanged(this, next, baseline = false)
    }

    /**
     * Publishes the state this engine starts from as the app's engine, changed or not: for the process's first one it
     * is the state an earlier launch left, which observers do not hear as a change.
     */
    fun publishBaseline() {
        val next = currentSnapshot()
        snapshot = next
        host.onSnapshotChanged(this, next, baseline = true)
    }

    private fun currentSnapshot(): Snapshot = Snapshot(
        loaded = started && !storageUnavailable,
        deviceId = credentials?.deviceId,
        token = device.push.token,
        optedIn = optedIn(),
        userOptedOut = device.push.userOptedOut,
        externalId = visibleExternalId(),
        isLoggedIn = identity.desired == Desired.CUSTOMER,
        tags = effectiveTags(),
        permission = permissionGranted,
        logoutIsFree = logoutIsFree(),
        logoutSends = logoutUnlinks(),
        launchTags = if (identity.desired == Desired.UNKNOWN) keptLaunchTags() else null,
    )

    fun optedIn(): Boolean = permissionGranted && !device.push.userOptedOut && device.push.token != null

    /**
     * The external id of the app's last login() (as on iOS): null for a guest, right after logout(), and
     * while consent is withdrawn.
     */
    fun visibleExternalId(): String? =
        if (identity.desired == Desired.CUSTOMER && withdrawal == null) identity.desiredExternalId else null

    fun effectiveTags(): Map<String, String> {
        val tags = device.tags
        if (tags.mirror.isEmpty() && tags.pendingAdd.isEmpty()) return emptyMap()
        val result = LinkedHashMap(tags.mirror)
        for (key in tags.pendingRemove.keys) result.remove(key)
        for ((key, tag) in tags.pendingAdd) result[key] = tag.value
        return result
    }

    fun readPermission(): Boolean {
        return try {
            env.permission()
        } catch (e: Exception) {
            permissionGranted
        }
    }

    // ── Selection ──

    /**
     * Sends the next ready request, if any. Register gates every device op (drain engines excepted); among ready ops
     * the order is identity (strict FIFO), update, tags, events. Tags never go out while an identity op is pending.
     * Every path either sends one request or settles (wake-up armed, worker waiters answered).
     */
    fun pump() {
        if (stopped) return
        if (storageUnavailable || !started) {
            answerWaiters(FlushOutcome.SUCCESS)
            return
        }
        if (inFlight != null) return
        wakeTask?.cancel()
        wakeTask = null
        val now = now()
        withdrawal?.let {
            pumpWithdrawal(it, now)
            return
        }
        clearExpiredDormancy(now)
        pruneIdentityOps()
        val creds = credentials
        if (creds == null) {
            if (mode.isDrain) {
                stop()
                return
            }
            if (registerReady(now) && sendRegister()) return
            settleIdle(now)
            return
        }
        if (deviceRoutesDormant(now)) {
            settleIdle(now)
            return
        }
        var head = identityHead()
        while (head != null && identityReady(head, now)) {
            if (sendIdentity(head, creds)) return
            // A logout left with nothing to send was removed: the op behind it may go now.
            if (identity.ops.contains(head)) break
            head = identityHead()
        }
        if (mode.isDrain) {
            if (identity.ops.none { it.kind.isLogoutLike }) {
                stop()
            } else {
                settleIdle(now)
            }
            return
        }
        val deviceOpsAllowed = device.registration.status == RegStatus.REGISTERED
        if (deviceOpsAllowed && sendsDeviceOps()) {
            if (updateReady(now) && sendUpdate(creds)) return
            if (identity.ops.isEmpty() && tagsReady(now) && sendTags(creds)) return
        }
        if (deviceOpsAllowed && eventsReady(now) && sendEvents(creds)) return
        settleIdle(now)
    }

    /** The identity op that may go next: the head of the log, or in drain mode the first logout-like one. */
    private fun identityHead(): IdentityOp? =
        if (mode.isDrain) identity.ops.firstOrNull { it.kind.isLogoutLike } else identity.ops.firstOrNull()

    /**
     * Update, tags and registration without an identity op go out in the foreground, and during a WorkManager run:
     * that run continues work the app already asked for. A process started by FCM or a broadcast sends only identity
     * ops and events until the app comes to the foreground.
     */
    fun sendsDeviceOps(): Boolean = !backgroundLaunch || workerAttached

    /** Nothing is ready: schedule the next wake-up, answer worker flushes, and take back an events work left idle. */
    private fun settleIdle(now: Double) {
        val next = nextDue(now)
        if (next != null) scheduleWake(next.at)
        answerWorkers(now, next)
        cancelIdleEventsWork(now)
    }

    /**
     * Ends a WorkManager run. Nothing left (or only ops that wait for the next launch, foreground or network change)
     * → success. A short transient backoff → retry. Anything else, dormant waits included → success, with a follow-up
     * run enqueued for the exact time the work is due: WorkManager's own backoff never wakes the app for nothing.
     */
    private fun answerWorkers(now: Double, next: NextDue?) {
        if (workerRuns.isEmpty()) return
        if (next == null) {
            answerWaiters(FlushOutcome.SUCCESS)
            flushWorkDueAt = null
            return
        }
        val wait = next.at - now
        if (next.transient && wait <= WORKER_RETRY_WINDOW_SECONDS) {
            answerWaiters(FlushOutcome.RETRY)
            return
        }
        answerWaiters(FlushOutcome.SUCCESS)
        // The run that is ending is still RUNNING: only REPLACE enqueues a run after it.
        env.work.scheduleFlush(key.appId, key.hostTag, apiBaseUrl, Math.ceil(wait).toLong(), replace = true, consentRequired = host.consentRequired())
        flushWorkDueAt = next.at
    }

    /** Ends every attached WorkManager run with [outcome]. */
    fun answerWaiters(outcome: FlushOutcome) {
        if (workerRuns.isEmpty()) return
        val runs = ArrayList(workerRuns)
        workerRuns.clear()
        for (run in runs) run.finish(outcome)
    }

    /**
     * Releases [run] without an answer: WorkManager stopped it (cancelled, replaced, constraints lost, its own time
     * limit), or its bounded wait ended. The engine's own schedule is unchanged, so [workerAttached] is never stale.
     */
    fun detachWorker(run: WorkerRun) {
        workerRuns.remove(run)
    }

    /**
     * The earliest time something becomes sendable; null when nothing waits on a time (ops exhausted, or nothing).
     * With [durable], the time a WorkManager run would first have something to send: [now] when something is ready
     * already, device ops whatever the app state (a run sends them), and ops this process gave up on after six
     * attempts too (a new process tries them again).
     */
    fun nextDue(now: Double, durable: Boolean = false): NextDue? {
        var best: NextDue? = null
        fun consider(at: Double?, transient: Boolean) {
            if (at == null || (!durable && at <= now)) return
            val time = maxOf(at, now)
            val current = best
            if (current == null || time < current.at || (time == current.at && !transient)) best = NextDue(time, transient)
        }
        fun considerRetry(retry: Retry, opKey: Long) {
            if (!durable && opKey in exhausted) return
            val dormantUntil = retry.dormantUntil
            if (dormantUntil != null && dormantUntil > retry.nextAt) {
                consider(dormantUntil, transient = false)
            } else {
                consider(retry.nextAt, transient = retry.attempts > 0)
            }
        }
        val registration = device.registration
        // Routes closed: nothing goes out before the dormant wait ends, whatever else is due.
        if (deviceRoutesDormant(now)) {
            consider(registration.dormantUntil, transient = false)
            return best
        }
        if (registration.status == RegStatus.DORMANT) consider(registration.dormantUntil, transient = false)
        if (credentials == null) {
            if (needsRegistration() && !registrationDormant(now)) {
                val op = registerOp()
                if (op != null) considerRetry(op.retry, op.id) else if (durable) consider(now, transient = false)
            }
            return best
        }
        identityHead()?.let { considerRetry(it.retry, it.id) }
        if (!mode.isDrain && registration.status == RegStatus.REGISTERED) {
            if (durable || sendsDeviceOps()) {
                device.ops.firstOrNull { it.kind == DeviceOpKind.UPDATE }?.let { considerRetry(it.retry, it.id) }
                if (identity.ops.isEmpty()) device.ops.firstOrNull { it.kind == DeviceOpKind.TAGS }?.let { considerRetry(it.retry, it.id) }
            }
            if (events.pending.isNotEmpty()) {
                considerRetry(eventsRetry, EVENTS_KEY)
                if (durable && (eventsFlushRequested || events.pending.size >= EVENTS_BATCH_SIZE)) consider(now, transient = false)
                eventsFirstPendingAt?.let { consider(it + EVENTS_DEBOUNCE_SECONDS, transient = false) }
            }
        }
        return best
    }

    fun scheduleWake(at: Double) {
        wakeTask?.cancel()
        // Rounded up: a wake that fired early would find nothing due and schedule itself again at once.
        val delayMillis = Math.ceil((at - now()) * 1000.0).toLong().coerceAtLeast(1L)
        val generationNow = generation
        wakeTask = env.runner.schedule(delayMillis, Runnable {
            if (generationNow == generation) task { }
        })
    }

    fun opReady(retry: Retry, opKey: Long, now: Double): Boolean {
        if (opKey in exhausted) return false
        if (retry.nextAt > now) return false
        val dormantUntil = retry.dormantUntil ?: return true
        return dormantUntil <= now
    }

    // ── Retries and triggers ──

    /**
     * Books a failed attempt: backoff (or Retry-After), and after 6 transient failures in this process, wait for a
     * trigger. A 429 waits its Retry-After and never counts toward those 6 (as on iOS).
     */
    fun applyFailure(retry: Retry, opKey: Long, failure: ApiFailure) {
        val now = now()
        retry.attempts += 1
        retry.lastAttemptAt = now
        retry.lastFailure = when (failure.failureClass) {
            FailureClass.NETWORK -> FailureKind.NETWORK
            FailureClass.RATE_LIMITED -> FailureKind.RATE_LIMITED
            else -> FailureKind.SERVER
        }
        val delay = when {
            failure.failureClass == FailureClass.RATE_LIMITED -> failure.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_SECONDS
            failure.retryAfterSeconds != null -> failure.retryAfterSeconds
            else -> Backoff.delaySeconds(retry.attempts, env.jitter)
        }
        retry.nextAt = now + delay
        val metadata = failureMetadata(failure, retry.attempts, delay)
        if (failure.failureClass == FailureClass.REDIRECT && !redirectLogged) {
            redirectLogged = true
            logger.log(LogCode.NET_REDIRECT_REFUSED, metadata) {
                "The server answered with a redirect (${failure.status}); SallaPush never follows redirects."
            }
        }
        if (failure.failureClass == FailureClass.INVALID_RESPONSE) {
            logger.log(LogCode.NET_INVALID_RESPONSE, metadata) {
                "${failure.method} ${failure.route} returned an unexpected body${failure.detail?.let { " ($it)" } ?: ""}; retrying."
            }
        }
        if (failure.failureClass == FailureClass.RATE_LIMITED) {
            logger.log(LogCode.NET_RATE_LIMITED, metadata) { "Rate limited (429); retry in ${delay.toLong()} s." }
        } else {
            val attemptsHere = (processAttempts[opKey] ?: 0) + 1
            processAttempts[opKey] = attemptsHere
            if (attemptsHere >= Backoff.MAX_ATTEMPTS_PER_PROCESS) {
                exhausted.add(opKey)
                // No retry is timed any more: the op waits for a trigger, so the line carries no delay. A consent
                // withdrawal is retried in this process only, so it ends instead. Events that got no answer also go
                // with the next WorkManager run (flushOnce).
                val next = when {
                    opKey == WITHDRAWAL_KEY -> "the consent withdrawal is retried in this process only, so it ends here"
                    opKey == EVENTS_KEY && failure.failureClass == FailureClass.NETWORK ->
                        "waiting for the next launch, foreground, network change or WorkManager run"
                    else -> "waiting for the next launch, foreground or network change"
                }
                logger.log(LogCode.NET_RETRY_EXHAUSTED, failureMetadata(failure, retry.attempts)) {
                    "${failure.describe()} failed $attemptsHere times; $next."
                }
            } else {
                logger.log(LogCode.NET_RETRY, metadata) {
                    "${failure.describe()} failed; retry ${retry.attempts} in ${delay.toLong()} s."
                }
            }
        }
        if (failure.failureClass == FailureClass.NETWORK) {
            networkFailurePending = true
            host.onNetworkFailure(this)
        }
        // A withdrawal is retried in this process only: it never schedules background work.
        if (delay > DURABLE_RETRY_THRESHOLD_SECONDS && !mode.isDrain && withdrawal == null) scheduleFlushWork()
    }

    fun failureMetadata(failure: ApiFailure, attempt: Int? = null, delay: Double? = null): Map<String, String> {
        val metadata = LinkedHashMap<String, String>()
        metadata[Meta.ROUTE] = failure.route
        if (failure.status > 0) metadata[Meta.HTTP_STATUS] = failure.status.toString()
        failure.code?.let { metadata[Meta.ERROR_CODE] = it }
        failure.requestId?.let { metadata[Meta.REQUEST_ID] = it }
        failure.reason?.let { metadata[Meta.REASON] = it }
        attempt?.let { metadata[Meta.ATTEMPT] = it.toString() }
        delay?.let { metadata[Meta.DELAY_S] = it.toLong().toString() }
        metadata[Meta.APP_ID] = appId
        return metadata
    }

    /** Clears a successful op's in-process attempt bookkeeping. */
    fun clearAttempts(opKey: Long) {
        processAttempts.remove(opKey)
        exhausted.remove(opKey)
    }

    /**
     * A trigger (new identity call, foreground, connectivity regained) makes network- and 5xx-failed ops ready at
     * once (not sooner than 2 s after their last attempt), keeping their attempt count. 429 and dormant waits stay.
     */
    fun applyTrigger() {
        val now = now()
        for (op in identity.ops) resetForTrigger(op.retry, op.id, now)
        for (op in device.ops) resetForTrigger(op.retry, op.id, now)
        resetForTrigger(eventsRetry, EVENTS_KEY, now)
    }

    private fun resetForTrigger(retry: Retry, opKey: Long, now: Double) {
        if (retry.lastFailure?.resetsOnTrigger != true) return
        retry.nextAt = maxOf(now, retry.lastAttemptAt + Backoff.TRIGGER_GAP_SECONDS)
        processAttempts.remove(opKey)
        exhausted.remove(opKey)
    }

    fun hasNetworkFailedWork(): Boolean {
        if (identity.ops.any { it.retry.lastFailure == FailureKind.NETWORK }) return true
        if (device.ops.any { it.retry.lastFailure == FailureKind.NETWORK }) return true
        return eventsRetry.lastFailure == FailureKind.NETWORK && events.pending.isNotEmpty()
    }

    // ── Background durability ──

    /** Hands pending work to WorkManager so it survives process death. */
    fun scheduleDurableWork() {
        if (stopped || withdrawal != null || mode.isDrain) return
        scheduleFlushWork()
        scheduleIdentityWork()
    }

    /**
     * Enqueues the unique flush work for the moment a WorkManager run would first have something to send: at once when
     * something is ready now, else when the earliest wait ends. A work this engine already enqueued for that time or
     * earlier is kept. Otherwise the enqueued one is replaced (it may be a far follow-up from an earlier run or process,
     * and the persisted ops are the whole truth), except while a run is attached: a running worker is never cancelled,
     * and its own follow-up covers what it leaves.
     */
    fun scheduleFlushWork() {
        if (stopped || withdrawal != null || mode.isDrain) return
        val now = now()
        val due = nextDue(now, durable = true)?.at ?: return
        val delaySeconds = Math.ceil(due - now).toLong().coerceAtLeast(0L)
        if (workerAttached) {
            env.work.scheduleFlush(key.appId, key.hostTag, apiBaseUrl, delaySeconds, replace = false, consentRequired = host.consentRequired())
            return
        }
        val enqueued = flushWorkDueAt
        if (enqueued != null && enqueued >= now && enqueued <= due) return
        env.work.scheduleFlush(key.appId, key.hostTag, apiBaseUrl, delaySeconds, replace = true, consentRequired = host.consentRequired())
        flushWorkDueAt = due
    }

    /**
     * Runs [scheduleEventsWork] in an engine task of its own, once per burst of events: after the task that recorded the
     * event saved it, and after a display that waits for the engine thread (handleFirebaseMessage right after a cold
     * start), so a notification never waits for WorkManager to start.
     */
    fun postEventsWork() {
        if (eventsWorkPosted || stopped) return
        val generationNow = generation
        eventsWorkPosted = env.runner.execute(Runnable {
            if (generationNow == generation) {
                task {
                    eventsWorkPosted = false
                    scheduleEventsWork()
                }
            }
        })
    }

    /**
     * Hands the event queue to WorkManager after an event was recorded while the app is not in the foreground. There
     * the 5 s wake may never fire: Android 15 and later cut a cached app's network seconds after FCM's service ends,
     * and the freezer then stops the process; WorkManager runs the work once the network is back. The unique flush work
     * is due at a random whole second of the [EVENTS_WORK_SPREAD_SECONDS] after the oldest unsent event, so that a
     * campaign's devices don't all send in the same second, and an event a dead process left goes at once; never before
     * a run could send anything. A work this engine knows is due within that time is kept as it is: later events never
     * postpone or advance it. Any other is replaced: one due later (this run comes first, and its end enqueues the
     * later one again), and one that was due already, which may sit in WorkManager's retry backoff (30 s, doubling up
     * to 5 h) that KEEP would keep. While push-service makes the events wait (a 429 or a 5xx), nothing is enqueued: no
     * run could send them before that wait ends, and the wait already has its run or the wake. While a run is attached,
     * its own follow-up covers the event. The work flushes the saved queue, as the wake does: whichever comes first
     * sends the batch, and the other finds it sent.
     */
    fun scheduleEventsWork() {
        if (stopped || withdrawal != null || mode.isDrain || workerAttached || stores == null) return
        val oldest = events.pending.minOfOrNull { it.atSeconds } ?: return
        val now = now()
        val serverWait = eventsRetry.lastFailure == FailureKind.RATE_LIMITED || eventsRetry.lastFailure == FailureKind.SERVER
        if (serverWait && eventsRetry.nextAt > now) return
        val ready = nextDue(now, durable = true)?.at ?: return
        val latest = maxOf(now + EVENTS_WORK_SPREAD_SECONDS, ready)
        val enqueued = flushWorkDueAt
        if (enqueued != null && enqueued >= now && enqueued <= latest) return
        val spread = (oldest + eventsWorkSpreadSeconds(env.jitter)).coerceIn(now, now + EVENTS_WORK_SPREAD_SECONDS)
        val due = maxOf(ready, spread)
        // REPLACE also for a known work that was due already: it may be in WorkManager's retry backoff, which KEEP
        // would keep.
        env.work.scheduleFlush(key.appId, key.hostTag, apiBaseUrl, Math.ceil(due - now).toLong(), replace = true, consentRequired = host.consentRequired())
        flushWorkDueAt = due
        eventsWorkDueAt = due
    }

    /**
     * Cancels the flush work [scheduleEventsWork] enqueued once nothing at all is left to send, for a run here or in a new
     * process (the wake sent the events first), so WorkManager never starts or wakes the app for them. A work another
     * need enqueued since (the background hand-off, a long retry, a run's follow-up) is never touched.
     */
    private fun cancelIdleEventsWork(now: Double) {
        val due = eventsWorkDueAt ?: return
        if (flushWorkDueAt != due) {
            eventsWorkDueAt = null
            return
        }
        if (workerAttached || mode.isDrain || nextDue(now, durable = true) != null) return
        env.work.cancelFlush(key.appId, key.hostTag)
        flushWorkDueAt = null
        eventsWorkDueAt = null
    }

    /**
     * Enqueues the unique identity work for the head identity op's remaining wait, replacing an enqueued one. While a
     * WorkManager run is attached to this engine an existing work is kept (KEEP): replacing would cancel a run that is
     * already sending the op, and the attached run's follow-up covers what it leaves.
     */
    fun scheduleIdentityWork() {
        if (mode.isDrain || withdrawal != null) return
        val head = identity.ops.firstOrNull() ?: return
        val now = now()
        val wait = maxOf(head.retry.nextAt, head.retry.dormantUntil ?: 0.0) - now
        env.work.scheduleIdentity(key.appId, key.hostTag, apiBaseUrl, wait.toLong().coerceAtLeast(0L), replace = !workerAttached, consentRequired = host.consentRequired())
    }

    /**
     * One flush for a WorkManager run: enqueue debounces become ready now, and so do events whose last request got no
     * answer (a network error or a timeout): the run has the network its constraint asked for, and a run in a new
     * process sends them at once (their retry state is memory only). The run ends that wait once, also after six
     * failures; the process's attempt count is kept, so a failed attempt after six ends the retries again. Other retry
     * waits and deliberate waits (dormant waits, the logout confirmation's minute, the re-registration hold) are never
     * shortened. [run] is answered when the engine is idle, unless it is released first ([detachWorker]).
     */
    fun flushOnce(run: WorkerRun) {
        if (stopped || run.stopped) {
            run.finish(FlushOutcome.SUCCESS)
            return
        }
        run.engine = this
        workerRuns.add(run)
        val now = now()
        for (op in identity.ops) if (op.retry.attempts == 0 && op.retry.nextAt > now) op.retry.nextAt = now
        for (op in device.ops) if (op.retry.attempts == 0 && op.retry.nextAt > now) op.retry.nextAt = now
        if (events.pending.isNotEmpty()) {
            eventsFlushRequested = true
            // Android 15+ thaws this process for the run after the 5 s wake failed: without this the run lands inside
            // that wake's backoff and ends RETRY, or sends nothing after six failures. The process's failures still
            // count: after six, a failed attempt of the run ends the retries again, with no follow-up run or wake.
            if (eventsRetry.lastFailure == FailureKind.NETWORK) {
                if (eventsRetry.nextAt > now) eventsRetry.nextAt = now
                exhausted.remove(EVENTS_KEY)
            }
        }
    }

    companion object {
        const val DAY_SECONDS = 86_400.0
        const val DEBOUNCE_SECONDS = 1.0
        const val TAGS_DEBOUNCE_SECONDS = 10.0
        const val EVENTS_DEBOUNCE_SECONDS = 5.0
        const val DRAIN_SECONDS = 60.0
        const val CLICK_TTL_SECONDS = 300.0
        const val EVENTS_KEY = -1L
        const val WITHDRAWAL_KEY = -2L
        const val EVENTS_BATCH_SIZE = 50

        /** A worker whose remaining work is a transient backoff this short returns RETRY (WorkManager backs off 30 s). */
        const val WORKER_RETRY_WINDOW_SECONDS = 30.0
        private const val MAX_FUTURE_SECONDS = 86_400.0
        private const val DEFAULT_RATE_LIMIT_SECONDS = 60.0
        private const val DURABLE_RETRY_THRESHOLD_SECONDS = 30.0

        /** The background events work runs at a random whole second of this many after the oldest unsent event (0-25 s). */
        const val EVENTS_WORK_SPREAD_SECONDS = 25L
        const val CREDENTIALS_FILE = "credentials.bin"

        /** A whole number of seconds from 0 to [EVENTS_WORK_SPREAD_SECONDS], each as likely. */
        fun eventsWorkSpreadSeconds(jitter: Jitter): Long =
            Math.floor(jitter.next().coerceIn(0.0, 1.0) * (EVENTS_WORK_SPREAD_SECONDS + 1)).toLong().coerceAtMost(EVENTS_WORK_SPREAD_SECONDS)
    }
}
