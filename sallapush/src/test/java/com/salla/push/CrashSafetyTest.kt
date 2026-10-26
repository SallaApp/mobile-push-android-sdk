package com.salla.push

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.Build
import com.salla.push.internal.api.ApiClient
import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.api.HttpRequest
import com.salla.push.internal.api.HttpResult
import com.salla.push.internal.api.NetExecutor
import com.salla.push.internal.api.Network
import com.salla.push.internal.api.Routes
import com.salla.push.internal.api.Transport
import com.salla.push.internal.engine.Credentials
import com.salla.push.internal.lifecycle.AndroidConnectivityMonitor
import com.salla.push.internal.lifecycle.AppLifecycle
import com.salla.push.internal.lifecycle.ForegroundListener
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.SecretString
import com.salla.push.internal.util.Cancellable
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.TaskRunner
import com.salla.push.internal.util.ThreadRunner
import com.salla.push.internal.util.entry
import com.salla.push.internal.util.entryValue
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Failures inside SallaPush never escape into the host: not from a public entry, a thread it cannot start, a callback,
 * or an answer.
 */
class CrashSafetyTest : SdkTestBase() {

    @Test
    fun test_aThreadThatCannotStart_isARefusal_reportedOnce_neverAThrow() {
        val reported = CopyOnWriteArrayList<Throwable>()
        val runner = ThreadRunner(ThreadFactory { throw OutOfMemoryError("pthread_create (1040KB stack) failed: Try again") }) { reported.add(it) }
        assertFalse(runner.execute(Runnable { }))
        runner.schedule(5L, Runnable { }).cancel()
        assertFalse(runner.execute(Runnable { }))
        assertEquals(1, reported.size)
        assertTrue(reported.single() is OutOfMemoryError)
    }

    @Test
    fun test_aTaskRefusedBecauseItsThreadCouldNotStart_neverRunsLater() {
        // The executor queues a task, then fails to start the thread for it: the refusal must be final.
        assertEquals(listOf("accepted"), runAfterARefusal { runner, ran -> assertFalse(runner.execute(Runnable { ran.add("refused") })) })
        assertEquals(listOf("accepted"), runAfterARefusal { runner, ran -> runner.schedule(0L, Runnable { ran.add("refused") }) })
    }

    /** [refuse] submits a task while the thread cannot start; then a task is accepted. Returns what ran. */
    private fun runAfterARefusal(refuse: (ThreadRunner, MutableList<String>) -> Unit): List<String> {
        val runner = ThreadRunner(failingFirstStart()) { }
        val ran = CopyOnWriteArrayList<String>()
        refuse(runner, ran)
        val done = CountDownLatch(1)
        assertTrue(runner.execute(Runnable {
            ran.add("accepted")
            done.countDown()
        }))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val settled = CountDownLatch(1)
        runner.schedule(50L, Runnable { settled.countDown() })
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        return ran.toList()
    }

    @Test
    fun test_aRequestWhoseNetThreadCouldNotStart_isAnsweredExactlyOnce_andNeverSentLater() {
        val netRunner = ThreadRunner(failingFirstStart()) { }
        val sent = CopyOnWriteArrayList<String>()
        val transport = Transport { request ->
            sent.add(request.route)
            HttpResult.Response(200, "{}".toByteArray(), "application/json", null, null, 1)
        }
        val direct = object : TaskRunner {
            override fun execute(task: Runnable): Boolean {
                task.run()
                return true
            }

            override fun schedule(delayMillis: Long, task: Runnable): Cancellable = Cancellable { }
        }
        val net = NetExecutor(transport, netRunner, direct)
        val answers = CopyOnWriteArrayList<String>()
        val loginAnswered = CountDownLatch(1)
        net.submit(HttpRequest("POST", "${Routes.DEFAULT_BASE_URL}/devices/x/unlink", Routes.UNLINK, null, null, 1000)) {
            answers.add("logout:" + it.javaClass.simpleName)
        }
        net.submit(HttpRequest("POST", "${Routes.DEFAULT_BASE_URL}/devices/x/link", Routes.LINK, null, null, 1000)) {
            answers.add("login:" + it.javaClass.simpleName)
            loginAnswered.countDown()
        }
        assertTrue(loginAnswered.await(5, TimeUnit.SECONDS))
        val settled = CountDownLatch(1)
        netRunner.schedule(50L, Runnable { settled.countDown() })
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("logout:Failure", "login:Response"), answers.toList())
        assertEquals(listOf(Routes.LINK), sent.toList())
    }

    /** Threads whose first start fails like pthread_create under memory pressure; later ones start normally. */
    private fun failingFirstStart(): ThreadFactory {
        val failNext = AtomicBoolean(true)
        return ThreadFactory { runnable ->
            object : Thread(runnable, "crash-safety-test") {
                override fun start() {
                    if (failNext.getAndSet(false)) throw OutOfMemoryError("pthread_create (1040KB stack) failed: Try again")
                    super.start()
                }
            }.apply { isDaemon = true }
        }
    }

    @Test
    fun test_aFailureEscapingAPublicEntry_isReportedAsAnInternalError_neverAsTheAppsNull() {
        // Every entry checks its own parameters for Java nulls first: an NPE reaching the guard is SallaPush's own.
        entry("User.addTag") { throw NullPointerException("inside SallaPush") }
        assertEquals("fallback", entryValue("User.getTags", "fallback") { throw IllegalStateException("inside SallaPush") })
        val reports = h.capture.of(LogCode.INTERNAL_ERROR).map { it.message }
        assertEquals(2, reports.size)
        assertEquals(SallaPushLogLevel.ERROR, LogCode.INTERNAL_ERROR.level)
        assertEquals("lifecycle", LogCode.INTERNAL_ERROR.category)
        // An internal failure is no storage failure.
        assertFalse(h.capture.has(LogCode.STORAGE_FAILED))
        // The API and where it was thrown (class.method:line), never the exception's message.
        assertTrue(reports[0], Regex("^SallaPush hit an internal error in User\\.addTag \\(NullPointerException at com\\.salla\\.push\\.CrashSafetyTest\\.[^ ]+:\\d+.*\\); the call was ignored\\.$").matches(reports[0]))
        assertTrue(reports[1], Regex("^SallaPush hit an internal error in User\\.getTags \\(IllegalStateException at com\\.salla\\.push\\..+\\); the call was ignored\\.$").matches(reports[1]))
        assertFalse(h.capture.allText().contains("inside SallaPush"))
        assertFalse(h.capture.has(LogCode.API_NULL_ARGUMENT))
        assertFalse(h.capture.has(LogCode.API_INVALID_ARGUMENT))
    }

    @Test
    fun test_activityLifecycleCallbacks_neverThrowIntoTheHost() {
        val failing = object : ForegroundListener {
            override fun onForeground(afterScreenOff: Boolean) = throw IllegalStateException("SallaPush bug")

            override fun onBackground() = throw IllegalStateException("SallaPush bug")

            override fun onActivityResumed() = throw IllegalStateException("SallaPush bug")
        }
        val tracker = AppLifecycle(h.main, failing)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        tracker.onActivityStarted(activity)
        tracker.onActivityResumed(activity)
        tracker.onActivityStopped(activity)
        tracker.runWhenKnown(Runnable { throw IllegalStateException("SallaPush bug") })
        h.advance(1.0)
        tracker.seed(true)
        h.settle()
        assertEquals(AppLifecycle.State.BACKGROUND, tracker.state)
    }

    @Test
    @Config(shadows = [RefusingConnectivityManager::class])
    fun test_aRefusedNetworkCallback_isAWarningOnce_neverAThrow() {
        // The console prints once the level is known: the default, WARN.
        h.logger.setManifestLevel(null)
        val monitor = AndroidConnectivityMonitor(h.context, h.logger)
        monitor.start { }
        monitor.start { }
        val warning = h.capture.of(LogCode.NET_MONITOR_UNAVAILABLE).single()
        assertEquals(SallaPushLogLevel.WARN, warning.code.level)
        assertEquals("network", warning.code.category)
        assertEquals(
            "SallaPush cannot watch for the network coming back (TooManyRequestsException); requests that failed for " +
                "lack of a network wait for their next retry, the next foreground or, for events, the next WorkManager run.",
            warning.message,
        )
        assertTrue(h.console.contains("WARN net.monitor_unavailable: ${warning.message}"))
        monitor.stop()
    }

    @Test
    fun test_noConnectivityService_isAWarningOnce_untilARegistrationSucceeds() {
        var available = false
        val context = object : ContextWrapper(h.context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE && !available) null else super.getSystemService(name)
        }
        val monitor = AndroidConnectivityMonitor(context, h.logger)
        monitor.start { }
        monitor.start { }
        assertEquals(listOf("no connectivity service"), h.capture.of(LogCode.NET_MONITOR_UNAVAILABLE).map { Regex("\\((.+?)\\)").find(it.message)?.groupValues?.get(1) })
        available = true
        monitor.start { }
        val manager = shadowOf(h.context.getSystemService(ConnectivityManager::class.java))
        assertEquals(1, manager.networkCallbacks.size)
        monitor.stop()
        assertTrue(manager.networkCallbacks.isEmpty())
        // A registration succeeded since the warning: the next refusal is reported again.
        available = false
        monitor.start { }
        assertEquals(2, h.capture.count(LogCode.NET_MONITOR_UNAVAILABLE))
    }

    @Test
    fun test_theNetworkCallback_neverThrowsIntoTheSystemsConnectivityThread() {
        val monitor = AndroidConnectivityMonitor(h.context, h.logger)
        var calls = 0
        monitor.start {
            calls += 1
            throw IllegalStateException("SallaPush bug")
        }
        val callbacks = shadowOf(h.context.getSystemService(ConnectivityManager::class.java)).networkCallbacks
        assertEquals(1, callbacks.size)
        callbacks.single().onAvailable(ShadowNetwork.newInstance(4_101))
        callbacks.single().onAvailable(ShadowNetwork.newInstance(4_102))
        assertTrue(calls >= 1)
        monitor.stop()
        assertTrue(shadowOf(h.context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.isEmpty())
    }

    @Test
    fun test_anAnswerThatCannotBeInterpreted_stillEndsTheRequest() {
        val results = ArrayList<ApiResult<Unit>>()
        val network = Network { _, onResult -> onResult(HttpResult.Response(503, "{}".toByteArray(), "application/json", "req-1", null, 3)) }
        val failingClock = object : Clock {
            override fun nowMillis(): Long = throw StackOverflowError()
        }
        val client = ApiClient(network, failingClock, Routes.DEFAULT_BASE_URL)
        client.update(Credentials(Http.DEVICE_ID, SecretString(Http.SECRET)), JSONObject()) { results.add(it) }
        val failure = (results.single() as? ApiResult.Err)?.failure ?: throw AssertionError("not a failure")
        assertEquals(FailureClass.INVALID_RESPONSE, failure.failureClass)
        assertEquals("req-1", failure.requestId)
    }

    @Test
    fun test_aRegisterAnswerHidingDeepNestingFromAQuoteCount_isRetried_andTheEngineKeepsWorking() {
        val deep = "[".repeat(25_000) + "]".repeat(25_000)
        var answers = 0
        h.healthyServer()
        h.net.on("POST", Routes.REGISTER) {
            answers += 1
            if (answers == 1) {
                Http.json(201, "{\"device_id\":\"${Http.DEVICE_ID}\",\"device_secret\":\"${Http.SECRET}\",\"status\":\"active\",\"adopted\":false,\"linked\":false,\"x\":'\"',\"y\":$deep,\"z\":'\"'}")
            } else {
                Http.registered()
            }
        }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertTrue(h.capture.has(LogCode.NET_INVALID_RESPONSE))
        assertNull(SallaPush.User.pushSubscription.id)
        h.advance(5.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.LINK).size)
    }

    // ── An answer that cannot reach the engine thread (a thread it cannot start) is never lost ──

    /**
     * The production NetExecutor on the harness's runners, with a canned server. The engine runner it posts answers to
     * refuses the next [refusals] posts, like a thread that cannot start under memory pressure.
     */
    private fun productionNetwork(refusals: AtomicInteger, sent: MutableList<String>) {
        val transport = Transport { request ->
            sent.add(request.method + " " + request.route)
            when (request.route) {
                Routes.REGISTER -> Http.registered()
                Routes.TAGS -> Http.tags(mapOf("a" to "1"))
                Routes.EVENTS -> Http.events(1)
                else -> Http.empty()
            }
        }
        h.close()
        h = Harness(networkFactory = { harness ->
            val engine = harness.engineRunner
            val refusing = object : TaskRunner {
                override fun execute(task: Runnable): Boolean {
                    if (refusals.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return false
                    return engine.execute(task)
                }

                override fun schedule(delayMillis: Long, task: Runnable): Cancellable = engine.schedule(delayMillis, task)
            }
            NetExecutor(transport, harness.netRunner, refusing)
        }).install()
        h.start()
        h.deliverToken()
        h.advance(5.0)
        assertEquals(listOf("POST " + Routes.REGISTER), sent.toList())
    }

    @Test
    fun test_anAnswerTheEngineThreadRefusesOnce_isPostedAgain_andTheNextRequestsGoOut() {
        val refusals = AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        productionNetwork(refusals, sent)
        refusals.set(1)
        SallaPush.User.addTag("a", "1")
        h.advance(15.0)
        assertEquals(0, refusals.get())
        // The refused answer was posted again: the tags request is settled with its real answer.
        assertNull(h.engine().inFlight)
        assertEquals(mapOf("a" to "1"), h.engine().device.tags.mirror)
        sent.clear()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(5.0)
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(listOf("POST " + Routes.LINK, "PATCH " + Routes.TAGS, "POST " + Routes.UNLINK), sent.toList())
    }

    @Test
    fun test_anAnswerThatNeverReachesTheEngineThread_isSettledByTheWatchdog_asATimeout() {
        val refusals = AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        productionNetwork(refusals, sent)
        // The answer's post and each of its retries are refused: the answer is lost.
        refusals.set(4)
        SallaPush.User.addTag("a", "1")
        h.advance(20.0)
        assertEquals(0, refusals.get())
        assertEquals("tags", h.engine().inFlight)
        // The request's watchdog settles it as a network failure 90 s after it was sent; the tags then go out again.
        h.advance(90.0)
        assertTrue(h.capture.of(LogCode.NET_RETRY).any { it.message.contains("no answer reached SallaPush within 90 s") })
        assertNull(h.engine().inFlight)
        assertEquals(2, sent.count { it == "PATCH " + Routes.TAGS })
        assertEquals(mapOf("a" to "1"), h.engine().device.tags.mirror)
        sent.clear()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(5.0)
        assertEquals(listOf("POST " + Routes.LINK), sent.toList())
    }

    @Test
    fun test_aLateAnswerOfARequestTheWatchdogSettled_isIgnored() {
        val engine = h.engineRunner
        val answers = ArrayList<String>()
        var release: (() -> Unit)? = null
        val heldNet = object : TaskRunner {
            override fun execute(task: Runnable): Boolean {
                release = { task.run() }
                return true
            }

            override fun schedule(delayMillis: Long, task: Runnable): Cancellable = Cancellable { }
        }
        val transport = Transport { HttpResult.Response(200, "{}".toByteArray(), "application/json", null, null, 1) }
        NetExecutor(transport, heldNet, engine, watchdogMillis = 1_000L)
            .submit(HttpRequest("POST", "${Routes.DEFAULT_BASE_URL}/devices/x/unlink", Routes.UNLINK, null, null, 1000)) {
                answers.add(it.javaClass.simpleName + ":" + ((it as? HttpResult.Failure)?.detail ?: ""))
            }
        h.advance(1.5)
        assertEquals(listOf("Failure:no answer reached SallaPush within 1 s"), answers)
        // The net thread finally answers: the request was already settled once, so nothing more happens.
        release?.invoke()
        h.settle()
        assertEquals(1, answers.size)
    }

    @Test
    fun test_anInternalErrorInAnEngineStep_namesWhereItHappened_onLogcat_neverItsMessage() {
        h.registered()
        h.engine().task { throw IllegalStateException("customer@example.com") }
        val line = h.console.single { it.contains("internal.error") }
        assertTrue(
            line,
            Regex("^ERROR internal\\.error: SallaPush hit an internal error in an engine step \\(IllegalStateException at com\\.salla\\.push\\.CrashSafetyTest[^ ]*:\\d+.*\\); that step was skipped\\.$").matches(line),
        )
        assertFalse(line.contains("customer@example.com"))
    }

    @Test
    fun test_aFailureThrownOutsideSallaPush_namesTheThrowSite_andTheSallaPushFramesItCameThrough() {
        val inner = IllegalStateException("not logged")
        inner.stackTrace = arrayOf(
            StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 437),
            StackTraceElement("com.salla.push.internal.engine.TagsKt", "sendTags", "Tags.kt", 170),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("com.salla.push.internal.engine.Engine", "pump", "Engine.kt", 700),
            StackTraceElement("com.salla.push.internal.engine.Engine", "task", "Engine.kt", 300),
            StackTraceElement("com.salla.push.internal.SdkCore", "route", "SdkCore.kt", 820),
        )
        assertEquals(
            "IllegalStateException at java.util.ArrayList.get:437 < com.salla.push.internal.engine.TagsKt.sendTags:170 < " +
                "com.salla.push.internal.engine.Engine.pump:700 < com.salla.push.internal.engine.Engine.task:300",
            com.salla.push.internal.util.InternalFailure.describe(inner),
        )
    }

    @Test
    fun test_aDeeplyNestedAnswerInUtf16_neverReachesTheParser_andIsRetried() {
        // UTF-16 puts a NUL byte in every ASCII character: read as UTF-8 text it is no strict JSON, so the depth gate
        // refuses it before org.json could recurse into the nesting (iOS found such a bypass, T2 A2-1).
        val deep = "{\"device_id\":\"${Http.DEVICE_ID}\",\"x\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}"
        val utf16 = deep.toByteArray(Charsets.UTF_16LE)
        val results = ArrayList<ApiResult<Unit>>()
        val network = Network { _, onResult -> onResult(HttpResult.Response(200, utf16, "application/json; charset=utf-16", "req-2", null, 3)) }
        ApiClient(network, h.clock, Routes.DEFAULT_BASE_URL).update(Credentials(Http.DEVICE_ID, SecretString(Http.SECRET)), JSONObject()) { results.add(it) }
        val failure = (results.single() as? ApiResult.Err)?.failure ?: throw AssertionError("not a failure")
        assertEquals(FailureClass.INVALID_RESPONSE, failure.failureClass)
        assertEquals("not a JSON object of at most 32 levels", failure.detail)
    }
}

/** Android 11+ refuses a network callback to an app that holds too many, with ConnectivityManager.TooManyRequestsException. */
@Implements(ConnectivityManager::class)
class RefusingConnectivityManager : ShadowConnectivityManager() {
    @Implementation(minSdk = Build.VERSION_CODES.N)
    override fun registerDefaultNetworkCallback(networkCallback: ConnectivityManager.NetworkCallback) {
        throw TooManyRequestsException()
    }
}

/** Stands for the hidden ConnectivityManager.TooManyRequestsException, a RuntimeException. */
class TooManyRequestsException : RuntimeException("too many network callbacks")
