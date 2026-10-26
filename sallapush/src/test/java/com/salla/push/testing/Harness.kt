package com.salla.push.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.salla.push.SallaPush
import com.salla.push.SallaPushConfig
import com.salla.push.internal.CoreEnv
import com.salla.push.internal.CorePlatform
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.Sdk
import com.salla.push.internal.SdkCore
import com.salla.push.internal.api.Routes
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.display.SmallIcon
import com.salla.push.internal.engine.Engine
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.lifecycle.AndroidDeviceSettingsMonitor
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.LogRecord
import com.salla.push.internal.log.LogSink
import com.salla.push.internal.log.Logger
import com.salla.push.internal.storage.GlobalStore
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.internal.work.FlushWork
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** Every log code emitted by any test in this JVM; written out for the log-catalogue coverage check. */
internal object EmittedCodes {
    private val codes = Collections.synchronizedSet(LinkedHashSet<String>())

    fun add(code: String) {
        codes.add(code)
    }

    fun flush() {
        val path = System.getProperty("sallapush.logCodesFile") ?: return
        val file = File(path)
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
        val all = (existing + synchronized(codes) { codes.toList() }).toSortedSet()
        file.writeText(all.joinToString("\n", postfix = "\n"))
    }
}

/** Captures every log record, whatever the levels. */
internal class LogCapture : LogSink {
    val records = CopyOnWriteArrayList<LogRecord>()

    override fun accept(record: LogRecord) {
        records.add(record)
        EmittedCodes.add(record.code.code)
    }

    fun has(code: LogCode): Boolean = records.any { it.code == code }

    fun count(code: LogCode): Int = records.count { it.code == code }

    fun of(code: LogCode): List<LogRecord> = records.filter { it.code == code }

    /** Every message and metadata value, for redaction checks. */
    fun allText(): String = records.joinToString("\n") { "${it.code.code} ${it.message} ${it.metadata}" }

    fun clear() = records.clear()
}

/**
 * A complete SDK core on fakes: manual runners and a fake clock (no real threads or sleeps), a scriptable network,
 * a temporary storage root, and fake platform services. Installed as the process core for the facade.
 */
internal class Harness(
    val context: Context = ApplicationProvider.getApplicationContext(),
    networkFactory: ((Harness) -> com.salla.push.internal.api.Network)? = null,
) {

    val clock = FakeClock()
    val engineRunner = ManualRunner(clock)
    val netRunner = ManualRunner(clock)
    val displayRunner = ManualRunner(clock)
    val main = FakeMainThread(clock)
    val net = FakeNetwork(engineRunner)
    val capture = LogCapture()
    val console = CopyOnWriteArrayList<String>()
    val logger = Logger(clock) { level, line -> console.add("$level $line") }.also { it.addSink(capture) }
    val root: File = Files.createTempDirectory("sallapush-test").toFile()
    /** Every storage, manifest or process-info access, and the ones made outside an SDK task (the caller's thread). */
    val ioCount = java.util.concurrent.atomic.AtomicInteger()
    val ioOffSdkThreads = CopyOnWriteArrayList<String>()
    val files = SdkFiles({ recordIo("file") }) { File(root, "sallapush") }
    val secretBox = FakeSecretBox()
    val device = FakeDeviceInfo()
    val work = FakeWorkScheduler()
    val connectivity = FakeConnectivity()
    val firebase = FakeFirebase()
    val images = FakeImages()
    var drawableLoader: (Context, Int) -> android.graphics.drawable.Drawable? = { ctx, id -> androidx.core.content.ContextCompat.getDrawable(ctx, id) }
    val displayer = NotificationDisplayer(logger, images, SmallIcon(logger) { ctx, id -> drawableLoader(ctx, id) })
    val network: com.salla.push.internal.api.Network = networkFactory?.invoke(this) ?: net

    @Volatile
    var permission = true
    var mainProcess = true
    var processForeground = false
    var manifest = ManifestConfig.EMPTY
    var fcmServiceName: String? = null
    var platformsBuilt = 0

    /** The engine's random source: 1.0 makes the background events work due 25 s after the event. */
    val jitter = FixedJitter(1.0)

    val env = CoreEnv(
        clock = clock,
        jitter = jitter,
        logger = logger,
        engineRunner = engineRunner,
        netRunner = netRunner,
        displayRunner = displayRunner,
        mainThread = main,
        platformFactory = { appContext, _, _ -> platform(appContext) },
    )
    val core = SdkCore(env)

    fun platform(appContext: Context): CorePlatform {
        platformsBuilt += 1
        return CorePlatform(
            context = appContext,
            files = files,
            secretBox = secretBox,
            network = network,
            device = device,
            permission = { permission },
            work = work,
            connectivity = connectivity,
            processInfo = {
                recordIo("process")
                mainProcess
            },
            processIsForeground = { processForeground },
            firebase = firebase,
            manifestReader = {
                recordIo("manifest")
                manifest
            },
            fcmServiceName = { fcmServiceName },
            displayer = displayer,
            globalStore = GlobalStore(files),
            deviceSettings = deviceSettings ?: AndroidDeviceSettingsMonitor(appContext).also { deviceSettings = it },
        )
    }

    /** The production time zone / locale receiver, registered on the Robolectric context while the main engine runs. */
    var deviceSettings: com.salla.push.internal.lifecycle.DeviceSettingsMonitor? = null

    /** Told before every file-system access of the SDK's files (a test can stop the "disk" at a given access). */
    @Volatile
    var onFileAccess: (() -> Unit)? = null

    private fun recordIo(what: String) {
        ioCount.incrementAndGet()
        if (!ManualRunner.inTask()) ioOffSdkThreads.add("$what on ${Thread.currentThread().name}")
        if (what == "file") onFileAccess?.invoke()
    }

    fun install(): Harness {
        Sdk.install(core)
        return this
    }

    /** Runs every task that is due now, on every runner and the main thread, until all are idle. */
    fun settle() {
        var guard = 0
        while (guard < 10_000) {
            val ran = engineRunner.runDue() + displayRunner.runDue() + netRunner.runDue() + main.runDue()
            if (ran == 0) return
            guard += 1
        }
        throw AssertionError("tasks keep scheduling themselves")
    }

    /** Moves the clock forward, running everything that becomes due on the way, in time order. */
    fun advance(seconds: Double) {
        val target = clock.millis + (seconds * 1000.0).toLong()
        settle()
        while (true) {
            val next = listOfNotNull(
                engineRunner.nextDueMillis(),
                displayRunner.nextDueMillis(),
                netRunner.nextDueMillis(),
                main.nextDueMillis(),
            ).minOrNull()
            if (next == null || next > target) break
            if (next > clock.millis) clock.millis = next
            settle()
        }
        clock.millis = target
        settle()
    }

    fun init(appId: String = APP_ID, config: SallaPushConfig? = null) {
        if (config == null) SallaPush.initWithContext(context, appId) else SallaPush.initWithContext(context, appId, config)
        settle()
    }

    private var activity: org.robolectric.android.controller.ActivityController<android.app.Activity>? = null

    /** Starts and resumes an activity: the app is in the foreground (the lifecycle tracker sees it). */
    fun foreground() {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        activity = controller
        settle()
        advance(0.6)
    }

    /** Stops the activity; the background is reported after the tracker's configuration-change grace period. */
    fun background() {
        activity?.pause()?.stop()
        activity = null
        settle()
        advance(1.0)
    }

    /** init + foreground. */
    fun start(appId: String = APP_ID, config: SallaPushConfig? = null) {
        init(appId, config)
        foreground()
    }

    fun deliverToken(token: String = FCM_TOKEN) {
        SallaPush.Notifications.onNewToken(context, token)
        settle()
    }

    /**
     * Runs one WorkManager flush (the worker's body) while this harness runs the SDK's tasks: it starts at once, and
     * its outcome arrives once the engine settled. [consentRequired] is the flag the work carries when it was enqueued
     * while consent was required.
     */
    fun runWorker(appId: String? = APP_ID, baseUrl: String? = Routes.DEFAULT_BASE_URL, consentRequired: Boolean = false): FlushOutcome {
        var outcome: FlushOutcome? = null
        FlushWork.start(context, appId, baseUrl, consentRequired) { outcome = it }
        settle()
        return outcome ?: throw AssertionError("the worker run did not end once the engine settled")
    }

    /** The customer the fake server's device row is linked to; null while it has no link. */
    var serverExternalId: String? = null

    /** Default answers for a healthy server that tracks the device row's link and tags. */
    fun healthyServer() {
        net.on("POST", Routes.REGISTER) {
            // Every registration is a new device row, without a link or tags.
            serverExternalId = null
            serverTags.clear()
            Http.registered()
        }
        net.on("PUT", Routes.DEVICE) { Http.empty() }
        net.on("POST", Routes.LINK) { call ->
            serverExternalId = call.body?.optString("external_id")
            Http.empty()
        }
        net.on("POST", Routes.UNLINK) {
            // The unlink keeps the device's tags.
            serverExternalId = null
            Http.empty()
        }
        net.on("PATCH", Routes.TAGS) { call -> Http.tags(tagsAfter(call)) }
        net.on("POST", Routes.EVENTS) { call -> Http.events(call.body?.getJSONArray("events")?.length() ?: 0) }
    }

    /**
     * A new process on the same phone: same storage, same server, clock carried over. Initialised and in the
     * foreground with the same token, and its request log cleared.
     */
    fun relaunch(keepCalls: Boolean = false, prepare: (Harness) -> Unit = { }): Harness {
        val next = Harness(context)
        File(root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = clock.millis
        next.serverExternalId = serverExternalId
        next.serverTags.putAll(serverTags)
        next.install()
        next.healthyServer()
        prepare(next)
        next.start()
        next.deliverToken()
        if (!keepCalls) {
            next.advance(1.0)
            next.net.clear()
        }
        return next
    }

    val serverTags = LinkedHashMap<String, String>()

    /** A minimal server-side tag store for [healthyServer]. */
    fun tagsAfter(call: FakeCall): Map<String, String> {
        val body = call.body ?: return serverTags
        body.optJSONArray("remove")?.let { remove -> for (index in 0 until remove.length()) serverTags.remove(remove.getString(index)) }
        body.optJSONObject("add")?.let { add -> for (key in add.keysList()) serverTags[key] = add.getString(key) }
        return LinkedHashMap(serverTags)
    }

    /** Initialised, in the foreground, token delivered, registered: the usual starting point. */
    fun registered(appId: String = APP_ID) {
        healthyServer()
        start(appId)
        deliverToken()
        advance(2.0)
    }

    val key: EngineKey get() = EngineKey(APP_ID, HostTag.of(Routes.DEFAULT_BASE_URL) ?: "")

    fun engine(key: EngineKey = this.key): Engine = core.registry.get(key) ?: throw AssertionError("no engine for $key")

    fun appDirectory(key: EngineKey = this.key): File = File(File(File(root, "sallapush"), key.appId), key.hostTag)

    fun identityJson(key: EngineKey = this.key): String = File(appDirectory(key), "identity.json").readText()

    fun stateJson(key: EngineKey = this.key): String = File(appDirectory(key), "state.json").readText()

    fun close() {
        root.deleteRecursively()
    }

    companion object {
        const val APP_ID = "st_42_android"
        const val FCM_TOKEN = "dGVzdC1mY20tdG9rZW4:APA91bHPRgkF3JUikC4ENAHEeMrd41Zxv3hVZjC9KtT8OvPVGJ-hQMRKRrZuJAEcl7B338qju59zJMjw2DELjzEvxwYv7hH5Ynpc1ODQ0aT4U4OFEeco8ohsN5PjL1iC2dNtk2BAokeMCg2ZXKqpc8FXKmhX94kIxQ"
        const val FCM_TOKEN_2 = "c2Vjb25kLWZjbS10b2tlbg:APA91bGkF3JUikC4ENAHEeMrd41Zxv3hVZjC9KtT8OvPVGJ-hQMRKRrZuJAEcl7B338qju59zJMjw2DELjzEvxwYv7hH5Ynpc1ODQ0aT4U4OFEeco8ohsN5PjL1iC2dNtk2BAokeMCg2ZXKqpc8FXK"
        /** The subjects of the two test identity tokens below. */
        const val CUSTOMER_ID = "1001"
        const val CUSTOMER_ID_2 = "2002"
        const val CUSTOMER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAxIiwiaWF0IjoxNzkwMDAwMDAwfQ.c2lnbmF0dXJl"
        const val CUSTOMER_TOKEN_2 = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMDAyIiwiaWF0IjoxNzkwMDAwMDAwfQ.c2lnbmF0dXJlMg"
    }
}

/** Robolectric on API 34 with a fresh, installed [Harness] per test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class SdkTestBase {

    internal lateinit var h: Harness

    @Before
    fun setUpHarness() {
        h = Harness().install()
    }

    @After
    fun tearDownHarness() {
        h.close()
    }

    companion object {
        @JvmStatic
        @AfterClass
        fun writeEmittedCodes() {
            EmittedCodes.flush()
        }
    }
}
