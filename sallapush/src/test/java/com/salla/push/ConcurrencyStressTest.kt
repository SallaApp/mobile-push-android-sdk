package com.salla.push

import android.os.Looper
import com.salla.push.internal.CoreEnv
import com.salla.push.internal.CorePlatform
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.Sdk
import com.salla.push.internal.SdkCore
import com.salla.push.internal.api.HttpResult
import com.salla.push.internal.api.NetExecutor
import com.salla.push.internal.api.Routes
import com.salla.push.internal.api.Transport
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.display.SmallIcon
import com.salla.push.internal.engine.Desired
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import com.salla.push.internal.storage.GlobalStore
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.internal.util.AndroidMainThread
import com.salla.push.internal.util.RandomJitter
import com.salla.push.internal.util.SystemClock
import com.salla.push.internal.util.ThreadRunner
import com.salla.push.testing.FakeConnectivity
import com.salla.push.testing.FakeDeviceInfo
import com.salla.push.testing.FakeDeviceSettings
import com.salla.push.testing.FakeFirebase
import com.salla.push.testing.FakeImages
import com.salla.push.testing.FakeSecretBox
import com.salla.push.testing.FakeWorkScheduler
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.LogCapture
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The public API driven from 8 real threads at once on the production threading: the engine,
 * network and display threads of [ThreadRunner], the production NetExecutor, the Android main thread
 * (Robolectric's looper, idled by this test thread while the callers run) and real files. No call may throw, no
 * internal error may be logged, nothing may deadlock, and once SallaPush is idle the getters equal the engine's state.
 */
class ConcurrencyStressTest : SdkTestBase() {

    /** A server answering on the network thread, tracking the link and the tags like the SallaPush server. */
    private class Server {
        @Volatile
        var linked = false
        private val tags = LinkedHashMap<String, String>()

        val transport = Transport { request ->
            val body = request.body?.let { JSONObject(String(it, Charsets.UTF_8)) }
            when (request.route) {
                Routes.REGISTER -> {
                    // A new device row, without a link or tags.
                    linked = false
                    tags.clear()
                    Http.registered()
                }
                Routes.DEVICE -> Http.empty()
                Routes.LINK -> {
                    linked = true
                    Http.empty()
                }
                Routes.UNLINK -> {
                    linked = false
                    Http.empty()
                }
                Routes.TAGS -> {
                    body?.optJSONArray("remove")?.let { remove -> for (index in 0 until remove.length()) tags.remove(remove.getString(index)) }
                    body?.optJSONObject("add")?.let { add -> for (key in add.keys()) tags[key] = add.getString(key) }
                    Http.tags(LinkedHashMap(tags))
                }
                Routes.EVENTS -> Http.events(body?.optJSONArray("events")?.length() ?: 0)
                else -> HttpResult.Failure(com.salla.push.internal.api.TransportFailure.OTHER, "unexpected route")
            }
        }
    }

    @Test
    fun test_eightThreads_mixedPublicCalls_neverThrow_neverLogAnInternalError_andEndConsistent() {
        val root = Files.createTempDirectory("sallapush-stress").toFile()
        val capture = LogCapture()
        val logger = Logger(SystemClock) { _, _ -> }.also { it.addSink(capture) }
        val runnerFailures = CopyOnWriteArrayList<Throwable>()
        val engineRunner = ThreadRunner("SallaPush-engine") { runnerFailures.add(it) }
        val netRunner = ThreadRunner("SallaPush-net") { runnerFailures.add(it) }
        val files = SdkFiles { File(root, "sallapush") }
        val server = Server()
        val env = CoreEnv(
            clock = SystemClock,
            jitter = RandomJitter,
            logger = logger,
            engineRunner = engineRunner,
            netRunner = netRunner,
            displayRunner = ThreadRunner("SallaPush-display") { runnerFailures.add(it) },
            mainThread = AndroidMainThread,
            platformFactory = { context, _, _ ->
                CorePlatform(
                    context = context,
                    files = files,
                    secretBox = FakeSecretBox(),
                    network = NetExecutor(server.transport, netRunner, engineRunner),
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
        val core = SdkCore(env)
        Sdk.install(core)
        val context = h.context
        SallaPush.initWithContext(context, Harness.APP_ID)

        val failures = CopyOnWriteArrayList<Throwable>()
        val callbacks = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(THREADS)
        repeat(THREADS) { index ->
            Thread({
                try {
                    start.await()
                    val random = Random(index.toLong())
                    repeat(CALLS) { call -> mixedCall(random.nextInt(KINDS), call, random, callbacks) }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }, "caller-$index").start()
        }
        val began = System.nanoTime()
        start.countDown()
        // The callers run while this thread is the main thread: it idles the main looper, so main-thread work
        // (observers, listeners, the lifecycle tracker) runs concurrently with them.
        while (!done.await(5, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() - began > TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)) throw AssertionError("deadlock: the callers did not finish")
        }

        // Idle: every call has reached the engine; then read the getters and the engine's state in one engine task.
        val checked = AtomicReference<String>()
        repeat(SETTLE_ROUNDS) {
            val idle = CountDownLatch(1)
            assertTrue(engineRunner.execute(Runnable { idle.countDown() }))
            assertTrue("deadlock: the engine thread is stuck", idle.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
        val compared = CountDownLatch(1)
        engineRunner.execute(Runnable {
            val engine = core.registry.all().single()
            val engineState = listOf(
                engine.identity.desired == Desired.CUSTOMER,
                engine.visibleExternalId(),
                engine.effectiveTags(),
                engine.optedIn(),
                engine.credentials?.deviceId,
            )
            val getters = listOf(
                SallaPush.User.isLoggedIn,
                SallaPush.User.externalId,
                SallaPush.User.getTags(),
                SallaPush.User.pushSubscription.optedIn,
                SallaPush.User.pushSubscription.id,
            )
            checked.set(if (engineState == getters) "" else "getters $getters, engine $engineState")
            compared.countDown()
        })
        assertTrue(compared.await(DEADLINE_SECONDS, TimeUnit.SECONDS))

        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(emptyList<Throwable>(), runnerFailures.toList())
        assertEquals(emptyList<String>(), capture.records.filter { it.code == LogCode.INTERNAL_ERROR || it.code == LogCode.STORAGE_FAILED }.map { it.message })
        assertEquals("", checked.get())
        assertTrue("main-thread callbacks ran during the run", callbacks.get() > 0)

        val stopped = CountDownLatch(1)
        engineRunner.execute(Runnable {
            for (engine in core.registry.all()) engine.stop()
            stopped.countDown()
        })
        assertTrue(stopped.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        root.deleteRecursively()
    }

    private fun mixedCall(kind: Int, call: Int, random: Random, callbacks: AtomicInteger) {
        val context = h.context
        when (kind) {
            0 -> if (random.nextBoolean()) {
                SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
            } else {
                SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
            }
            1 -> SallaPush.logout()
            2 -> SallaPush.User.addTag("k${random.nextInt(6)}", "v$call")
            3 -> SallaPush.User.removeTag("k${random.nextInt(6)}")
            4 -> SallaPush.User.addTags(mapOf("a${random.nextInt(3)}" to "x", "b" to ""))
            5 -> {
                SallaPush.User.getTags()
                SallaPush.User.isLoggedIn
                SallaPush.User.externalId
                SallaPush.User.pushSubscription.optedIn
                SallaPush.User.pushSubscription.id
                SallaPush.Notifications.permission
            }
            6 -> SallaPush.User.pushSubscription.optOut()
            7 -> SallaPush.User.pushSubscription.optIn()
            8 -> SallaPush.User.setLanguage(if (random.nextBoolean()) "ar" else null)
            9 -> SallaPush.Notifications.onNewToken(context, if (random.nextBoolean()) Harness.FCM_TOKEN else Harness.FCM_TOKEN_2)
            10 -> {
                val observer = SallaPushSubscriptionObserver { callbacks.incrementAndGet() }
                SallaPush.User.pushSubscription.addObserver(observer)
                if (random.nextBoolean()) SallaPush.User.pushSubscription.removeObserver(observer)
            }
            11 -> {
                val observer = object : SallaPushUserObserver {
                    override fun onUserStateChange(state: SallaPushUserChangedState) {
                        callbacks.incrementAndGet()
                    }
                }
                SallaPush.User.addObserver(observer)
                if (random.nextBoolean()) SallaPush.User.removeObserver(observer)
            }
            12 -> SallaPush.Notifications.handleFirebaseMessage(context, Messages.remote(Messages.native(id = "msg_${random.nextInt(9) + 1}a")))
            13 -> SallaPush.Migration.setDisplayCompatPayloads(random.nextBoolean())
            14 -> SallaPush.Debug.logLevel = if (random.nextBoolean()) SallaPushLogLevel.VERBOSE else SallaPushLogLevel.WARN
            15 -> {
                val listener = SallaPushLogListener { }
                SallaPush.Debug.addLogListener(listener, SallaPushLogLevel.VERBOSE)
                SallaPush.Debug.removeLogListener(listener)
            }
            16 -> {
                val listener = SallaPushClickListener { callbacks.incrementAndGet() }
                SallaPush.Notifications.addClickListener(listener)
                SallaPush.Notifications.removeClickListener(listener)
            }
            17 -> {
                val listener = SallaPushForegroundLifecycleListener { callbacks.incrementAndGet() }
                SallaPush.Notifications.addForegroundLifecycleListener(listener)
                SallaPush.Notifications.removeForegroundLifecycleListener(listener)
            }
            18 -> SallaPush.Notifications.refreshPermission()
            else -> SallaPush.initWithContext(context, Harness.APP_ID)
        }
    }

    private companion object {
        const val THREADS = 8
        const val CALLS = 500
        const val KINDS = 20
        const val SETTLE_ROUNDS = 3
        const val DEADLINE_SECONDS = 120L
    }
}
