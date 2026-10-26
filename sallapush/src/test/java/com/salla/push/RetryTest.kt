package com.salla.push

import com.salla.push.internal.api.HttpRequest
import com.salla.push.internal.api.HttpUrlConnectionTransport
import com.salla.push.internal.api.NetExecutor
import com.salla.push.internal.api.Transport
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.util.Cancellable
import com.salla.push.internal.util.TaskRunner
import com.salla.push.internal.util.ThreadRunner
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real HTTP against MockWebServer: the production transport and NetExecutor on a real net thread, the engine on
 * the test's manual runner and fake clock. Each answer is awaited, then run, so the engine stays deterministic.
 */
class RetryTest : SdkTestBase() {

    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val scripted = ConcurrentHashMap<String, ConcurrentLinkedDeque<MockResponse>>()
    private val received = CopyOnWriteArrayList<RecordedRequest>()
    private val netThread = ThreadRunner("SallaPush-net-test") { }
    private val submitted = AtomicInteger()
    private val answered = Semaphore(0)

    @Volatile
    private var readTimeoutMillis = READ_TIMEOUT_MILLIS

    private lateinit var base: String

    private val key: EngineKey get() = EngineKey(Harness.APP_ID, HostTag.of(base) ?: "")

    @Before
    fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.add(request)
                val key = "${request.method} ${route(request.path ?: "")}"
                return scripted[key]?.pollFirst() ?: healthy(key)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
        base = "http://127.0.0.1:${server.port}"
        h.close()
        h = Harness(networkFactory = { harness -> NetExecutor(shortTimeouts(), countingNetRunner(), signallingRunner(harness.engineRunner)) }).install()
    }

    @After
    fun stopServer() {
        server.shutdown()
        elsewhere.shutdown()
    }

    @Test
    fun test_requests_carryTheContractHeaders() {
        registered()
        updateLanguage("en")
        val register = received.first { it.method == "POST" && route(it.path ?: "") == "register" }
        assertNull(register.getHeader("Authorization"))
        assertEquals("application/json", register.getHeader("Content-Type"))
        assertEquals("application/json", register.getHeader("Accept"))
        assertTrue(register.getHeader("User-Agent")?.startsWith("SallaPush-Test") == true)
        assertEquals(Harness.FCM_TOKEN, JSONObject(register.body.readUtf8()).getString("push_token"))
        val put = received.last()
        assertEquals("PUT", put.method)
        assertEquals("/devices/${Http.DEVICE_ID}", put.path)
        assertEquals("Device ${Http.SECRET}", put.getHeader("Authorization"))
        assertEquals("en", JSONObject(put.body.readUtf8()).getString("language"))
    }

    @Test
    fun test_429_retryAfterSeconds_isHonoured() {
        registered()
        script("PUT device", MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
        updateLanguage("en")
        assertTrue(h.capture.has(LogCode.NET_RATE_LIMITED))
        val sent = submitted.get()
        h.advance(119.0)
        assertEquals(sent, submitted.get())
        h.advance(2.0)
        awaitAnswer()
        assertEquals(sent + 1, submitted.get())
        assertEquals(2, received.count { it.method == "PUT" })
    }

    @Test
    fun test_429_retryAfterHttpDate_isHonoured() {
        registered()
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val at = format.format(Date(h.clock.millis + 300_000L))
        script("PUT device", MockResponse().setResponseCode(429).setHeader("Retry-After", at).setBody("{}"))
        updateLanguage("en")
        val sent = submitted.get()
        h.advance(298.0)
        assertEquals(sent, submitted.get())
        h.advance(3.0)
        awaitAnswer()
        assertEquals(2, received.count { it.method == "PUT" })
    }

    @Test
    fun test_5xx_backsOffExponentially_andPersistsAttempts() {
        registered()
        repeat(3) { script("PUT device", MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody(SERVER_ERROR)) }
        updateLanguage("en")
        assertTrue(h.stateJson(key).contains("\"attempts\":1"))
        for (delay in listOf(2.0, 4.0, 8.0)) {
            val sent = submitted.get()
            h.advance(delay - 0.1)
            assertEquals(sent, submitted.get())
            h.advance(0.2)
            assertEquals(sent + 1, submitted.get())
            awaitAnswer()
        }
        assertEquals(4, received.count { it.method == "PUT" })
        assertEquals(3, h.capture.count(LogCode.NET_RETRY))
        assertFalse(h.stateJson(key).contains("\"kind\":\"update\""))
    }

    // ── Retry-After on 5xx, the 429 default, and the 1 s…24 h clamp ──

    @Test
    fun test_503_withRetryAfter_waitsThatLong_notTheBackoff() {
        registered()
        script("PUT device", MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setHeader("Retry-After", "30").setBody(SERVER_ERROR))
        updateLanguage("en")
        assertTrue(h.capture.of(LogCode.NET_RETRY).single().message.endsWith("retry 1 in 30 s."))
        assertNextAttemptAfter(30.0)
    }

    @Test
    fun test_429_withoutRetryAfter_waitsTheDefault60s() {
        registered()
        script("PUT device", MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody(RATE_LIMITED))
        updateLanguage("en")
        assertEquals("Rate limited (429); retry in 60 s.", h.capture.of(LogCode.NET_RATE_LIMITED).single().message)
        assertNextAttemptAfter(60.0)
    }

    @Test
    fun test_429_withAnUnreadableRetryAfter_waitsTheDefault60s() {
        registered()
        script("PUT device", MockResponse().setResponseCode(429).setHeader("Retry-After", "soon").setBody(RATE_LIMITED))
        updateLanguage("en")
        assertNextAttemptAfter(60.0)
    }

    @Test
    fun test_retryAfterZero_isClampedToOneSecond() = clampedTo("0", 1.0)

    @Test
    fun test_retryAfterNegative_isClampedToOneSecond() = clampedTo("-5", 1.0)

    @Test
    fun test_retryAfterInThePast_isClampedToOneSecond() {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        clampedTo(format.format(Date(h.clock.millis - 300_000L)), 1.0)
    }

    @Test
    fun test_retryAfterBeyondADay_isClampedTo24h() = clampedTo("999999", 86_400.0)

    /** A 429 whose Retry-After is [header] is retried after exactly [seconds]. */
    private fun clampedTo(header: String, seconds: Double) {
        registered()
        script("PUT device", MockResponse().setResponseCode(429).setHeader("Retry-After", header).setBody(RATE_LIMITED))
        updateLanguage("en")
        assertNextAttemptAfter(seconds)
    }

    /** No request for [seconds] after the failed one (minus 0.1 s), then exactly one. */
    private fun assertNextAttemptAfter(seconds: Double) {
        val sent = submitted.get()
        h.advance(seconds - 0.1)
        assertEquals("nothing before $seconds s", sent, submitted.get())
        h.advance(0.2)
        awaitAnswer()
        assertEquals(sent + 1, submitted.get())
        assertEquals(2, received.count { it.method == "PUT" })
    }

    @Test
    fun test_html502_isRetried_andItsBodyNeverLogged() {
        registered()
        script("PUT device", MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html><body>Bad gateway fake_body_token_0123456789abcdefghijklmnop</body></html>"))
        updateLanguage("en")
        assertTrue(h.capture.has(LogCode.NET_RETRY))
        assertFalse(h.capture.allText().contains("Bad gateway"))
        assertFalse(h.capture.allText().contains("fake_body_token"))
        h.advance(2.1)
        awaitAnswer()
        assertEquals(2, received.count { it.method == "PUT" })
    }

    @Test
    fun test_readTimeout_isANetworkFailure_retried_andWatchesConnectivity() {
        registered()
        readTimeoutMillis = 300
        script("PUT device", MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        updateLanguage("en")
        assertTrue(h.capture.of(LogCode.NET_RETRY).any { it.message.contains("timeout") })
        assertTrue(h.connectivity.started)
        readTimeoutMillis = READ_TIMEOUT_MILLIS
        h.connectivity.networkAvailable()
        h.settle()
        h.advance(2.1)
        awaitAnswer()
        assertEquals(2, received.count { it.method == "PUT" })
        assertFalse(h.connectivity.started)
    }

    @Test
    fun test_disconnect_isANetworkFailure_andRetried() {
        registered()
        // A buffered request is sent again once by the connection itself when the connection drops (the JDK's
        // HttpURLConnection, like Android's, does so): a request that drops twice is what reaches SallaPush.
        script("PUT device", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        script("PUT device", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        updateLanguage("en")
        assertTrue(h.capture.has(LogCode.NET_RETRY))
        assertEquals(2, received.count { it.method == "PUT" })
        h.advance(2.1)
        awaitAnswer()
        assertEquals(3, received.count { it.method == "PUT" })
        assertTrue(h.engine(key).device.ops.isEmpty())
    }

    @Test
    fun test_aConnectionDroppedOnce_isSentAgainByTheConnection_andItsAnswerUsed() {
        registered()
        script("PUT device", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        updateLanguage("en")
        assertFalse(h.capture.has(LogCode.NET_RETRY))
        assertEquals(2, received.count { it.method == "PUT" })
        assertTrue(h.engine(key).device.ops.isEmpty())
        assertTrue(h.capture.has(LogCode.UPDATE_SENT))
    }

    // ── 401 answers through the production transport: their error code is read and decides ──

    @Test
    fun test_401InvalidDeviceSecret_throughTheTransport_registersAgain() {
        registered()
        script("PUT device", json(401, """{"error":{"code":"invalid_device_secret","message":"Invalid device secret"}}"""))
        updateLanguage("en")
        awaitAnswer()
        val registers = received.filter { it.method == "POST" && route(it.path ?: "") == "register" }
        assertEquals(2, registers.size)
        val reregistering = h.capture.of(LogCode.REGISTER_REREGISTERING).single()
        assertEquals("invalid_device_secret", reregistering.metadata[Meta.REASON])
        assertFalse(h.capture.has(LogCode.NET_RETRY))
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
    }

    @Test
    fun test_401WithAChallenge_throughTheTransport_isStillRead() {
        registered()
        script(
            "PUT device",
            json(401, """{"error":{"code":"invalid_device_secret","message":"Invalid device secret"}}""")
                .setHeader("WWW-Authenticate", "Basic realm=\"SallaPush\""),
        )
        updateLanguage("en")
        awaitAnswer()
        assertEquals(2, received.count { it.method == "POST" && route(it.path ?: "") == "register" })
        assertEquals(listOf("invalid_device_secret"), h.capture.of(LogCode.REGISTER_REREGISTERING).map { it.metadata[Meta.REASON] })
        assertFalse(h.capture.has(LogCode.NET_RETRY))
    }

    @Test
    fun test_302_isNeverFollowed_andTheSecretNeverLeavesTheHost() {
        registered()
        val redirect = MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${elsewhere.port}/devices/${Http.DEVICE_ID}")
        script("PUT device", redirect)
        script("PUT device", MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:${elsewhere.port}/steal"))
        updateLanguage("en")
        h.advance(2.1)
        awaitAnswer()
        assertEquals(0, elsewhere.requestCount)
        assertEquals(1, h.capture.count(LogCode.NET_REDIRECT_REFUSED))
        h.advance(4.1)
        awaitAnswer()
        assertEquals(3, received.count { it.method == "PUT" })
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun test_oversizedOkBody_isAnInvalidResponse_retried() {
        registered()
        script("PUT device", MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{\"linked\":false,\"pad\":\"" + "x".repeat(270_000) + "\"}"))
        updateLanguage("en")
        assertEquals(
            "PUT /devices/{id} returned an unexpected body (larger than 256 KB); retrying.",
            h.capture.of(LogCode.NET_INVALID_RESPONSE).single().message,
        )
        h.advance(2.1)
        awaitAnswer()
        assertEquals(2, received.count { it.method == "PUT" })
    }

    @Test
    fun test_aTagsAnswerOfAbout160KB_isAccepted_andReplacesTheMirror() {
        registered()
        val value = "\u0001".repeat(255)
        val tags = JSONObject()
        for (index in 0 until 100) tags.put("k" + index.toString().padStart(63, '0'), value)
        val body = JSONObject().put("tags", tags).toString()
        assertTrue(body.length > 160_000)
        script("POST tags", json(200, body))
        SallaPush.User.addTag("plan", "gold")
        h.advance(10.1)
        awaitAnswer()
        assertFalse(h.capture.has(LogCode.NET_INVALID_RESPONSE))
        assertEquals(1, received.count { it.method == "POST" && route(it.path ?: "") == "tags" })
        val shown = SallaPush.User.getTags()
        assertEquals(100, shown.size)
        assertEquals(value, shown["k" + "0".repeat(63)])
        // Confirmed: nothing is sent again at the next foreground.
        h.background()
        h.foreground()
        h.advance(20.0)
        assertEquals(1, received.count { it.method == "POST" && route(it.path ?: "") == "tags" })
    }

    // ── Helpers ──

    private fun registered() {
        h.start(Harness.APP_ID, SallaPushConfig.Builder().apply { apiBaseUrl = base }.build())
        h.deliverToken()
        h.advance(1.1)
        awaitAnswer()
        assertEquals(1, received.size)
    }

    /** Changes the language and waits for the PUT it causes to be answered. */
    private fun updateLanguage(language: String) {
        SallaPush.User.setLanguage(language)
        h.advance(1.1)
        awaitAnswer()
    }

    /** Waits for the next result to be posted to the engine, then runs it. */
    private fun awaitAnswer() {
        assertTrue("no answer from the net thread", answered.tryAcquire(20, TimeUnit.SECONDS))
        h.settle()
    }

    private fun script(key: String, response: MockResponse) {
        scripted.getOrPut(key) { ConcurrentLinkedDeque() }.add(response)
    }

    private fun healthy(key: String): MockResponse = when (key) {
        "POST register" -> json(201, """{"device_id":"${Http.DEVICE_ID}","device_secret":"${Http.SECRET}"}""")
        "PUT device" -> json(200, "{}")
        "POST link" -> json(200, "{}")
        "POST unlink" -> json(200, "{}")
        "POST events" -> json(202, """{"accepted":0,"ignored":0}""")
        else -> json(404, """{"error":{"code":"not_found","message":"No such route"}}""")
    }

    private fun json(status: Int, body: String): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setHeader("X-Request-Id", "req-$status").setBody(body)

    private fun route(path: String): String = when {
        path.matches(Regex("^/apps/[a-z0-9_]+/devices$")) -> "register"
        path.matches(Regex("^/devices/[0-9a-f-]+$")) -> "device"
        else -> path.substringAfterLast('/')
    }

    /**
     * The production transport with [readTimeoutMillis]. The JDK's HttpURLConnection refuses PATCH (Android's sends it),
     * so tags go to MockWebServer as POST: these tests are about retries and sizes, not methods.
     */
    private fun shortTimeouts(): Transport {
        val transport = HttpUrlConnectionTransport("SallaPush-Test/1.0")
        return Transport { request ->
            val method = if (request.method == "PATCH") "POST" else request.method
            transport.send(HttpRequest(method, request.url, request.route, request.body, request.authorization, readTimeoutMillis))
        }
    }

    /** The net runner, counting submissions on the engine thread (the request itself runs on a real thread). */
    private fun countingNetRunner(): TaskRunner = object : TaskRunner {
        override fun execute(task: Runnable): Boolean {
            submitted.incrementAndGet()
            return netThread.execute(task)
        }

        override fun schedule(delayMillis: Long, task: Runnable): Cancellable = netThread.schedule(delayMillis, task)
    }

    /** The engine runner as the net thread sees it: each posted result also signals [answered]. */
    private fun signallingRunner(engine: TaskRunner): TaskRunner = object : TaskRunner {
        override fun execute(task: Runnable): Boolean {
            val accepted = engine.execute(task)
            answered.release()
            return accepted
        }

        override fun schedule(delayMillis: Long, task: Runnable): Cancellable = engine.schedule(delayMillis, task)
    }

    private companion object {
        /**
         * The read timeout of every request but the timeout test's own: the local server answers in milliseconds, and
         * this bound only keeps a heavily loaded machine from failing an exchange, below awaitAnswer's 20 s.
         */
        const val READ_TIMEOUT_MILLIS = 15_000
        const val SERVER_ERROR = """{"error":{"code":"server_error","message":"Try again"}}"""
        const val RATE_LIMITED = """{"error":{"code":"rate_limited","message":"Too many requests"}}"""
    }
}
