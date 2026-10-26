package com.salla.push

import com.salla.push.internal.CoreEnv
import com.salla.push.internal.CorePlatform
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.Sdk
import com.salla.push.internal.SdkCore
import com.salla.push.internal.api.HttpUrlConnectionTransport
import com.salla.push.internal.api.NetExecutor
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.display.SmallIcon
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.storage.GlobalStore
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.internal.util.MainThread
import com.salla.push.internal.util.SystemClock
import com.salla.push.internal.util.ThreadRunner
import com.salla.push.testing.FakeConnectivity
import com.salla.push.testing.FakeDeviceInfo
import com.salla.push.testing.FakeDeviceSettings
import com.salla.push.testing.FakeFirebase
import com.salla.push.testing.FakeImages
import com.salla.push.testing.FakeSecretBox
import com.salla.push.testing.FakeWorkScheduler
import com.salla.push.testing.FixedJitter
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.LogCapture
import com.salla.push.testing.SdkTestBase
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The production threading (engine, net and display threads, the system clock) and real HTTP: a request
 * that hangs on the net thread never delays the engine, so a logout reaches disk at once.
 */
class DurabilityTest : SdkTestBase() {

    private val server = MockWebServer()
    private val root: File = Files.createTempDirectory("sallapush-durability").toFile()
    private val main = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "fake-main").apply { isDaemon = true } }
    private val capture = LogCapture()
    private lateinit var env: CoreEnv
    private lateinit var core: SdkCore

    @Before
    fun startCore() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/devices") == true -> MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                    .setBody("""{"device_id":"${Http.DEVICE_ID}","device_secret":"${Http.SECRET}"}""")
                request.path?.endsWith("/link") == true -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                request.path?.endsWith("/unlink") == true -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else -> MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val logger = com.salla.push.internal.log.Logger(SystemClock) { _, _ -> }.also { it.addSink(capture) }
        val engineRunner = ThreadRunner("SallaPush-engine") { }
        val netRunner = ThreadRunner("SallaPush-net") { }
        val files = SdkFiles { File(root, "sallapush") }
        env = CoreEnv(
            clock = SystemClock,
            jitter = FixedJitter(1.0),
            logger = logger,
            engineRunner = engineRunner,
            netRunner = netRunner,
            displayRunner = ThreadRunner("SallaPush-display") { },
            mainThread = ExecutorMainThread(main),
            platformFactory = { context, _, _ ->
                CorePlatform(
                    context = context,
                    files = files,
                    secretBox = FakeSecretBox(),
                    network = NetExecutor(HttpUrlConnectionTransport("SallaPush-Test/1.0"), netRunner, engineRunner),
                    device = FakeDeviceInfo(),
                    permission = { true },
                    work = FakeWorkScheduler(),
                    connectivity = FakeConnectivity(),
                    processInfo = { true },
                    processIsForeground = { true },
                    firebase = FakeFirebase(),
                    manifestReader = { ManifestConfig.EMPTY },
                    fcmServiceName = { null },
                    displayer = NotificationDisplayer(logger, FakeImages(), SmallIcon(logger)),
                    globalStore = GlobalStore(files),
                    deviceSettings = FakeDeviceSettings(),
                )
            },
        )
        core = SdkCore(env)
        Sdk.install(core)
    }

    @After
    fun stopCore() {
        val stopped = CountDownLatch(1)
        env.engineRunner.execute(Runnable {
            for (engine in core.registry.all()) engine.stop()
            stopped.countDown()
        })
        stopped.await(20, TimeUnit.SECONDS)
        server.shutdown()
        main.shutdownNow()
        root.deleteRecursively()
    }

    @Test
    fun test_logoutDuringHangingRequest_persistedImmediately() {
        val base = "http://127.0.0.1:${server.port}"
        SallaPush.initWithContext(h.context, Harness.APP_ID, SallaPushConfig.Builder().apply { apiBaseUrl = base }.build())
        SallaPush.Notifications.onNewToken(h.context, Harness.FCM_TOKEN)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        assertEquals("POST", server.takeRequest(20, TimeUnit.SECONDS)?.method)
        val login = server.takeRequest(20, TimeUnit.SECONDS)
        assertEquals("/devices/${Http.DEVICE_ID}/link", login?.path)

        val identity = File(File(File(File(root, "sallapush"), Harness.APP_ID), HostTag.of(base) ?: ""), "identity.json")
        val started = System.nanoTime()
        SallaPush.logout()
        val persisted = CountDownLatch(1)
        env.engineRunner.execute(Runnable { persisted.countDown() })
        assertTrue(persisted.await(5, TimeUnit.SECONDS))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("logout reached disk after $elapsedMillis ms", elapsedMillis < 100)
        assertTrue(identity.readText().contains("\"kind\":\"logout\""))
        assertEquals(EngineKey(Harness.APP_ID, HostTag.of(base) ?: ""), core.registry.all().single().key)
    }

    /** A main thread for the core: posted work runs in order on one thread. */
    private class ExecutorMainThread(private val executor: ScheduledThreadPoolExecutor) : MainThread {
        override fun post(task: Runnable) {
            executor.execute(task)
        }

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        }

        override fun isCurrent(): Boolean = Thread.currentThread().name == "fake-main"
    }
}
