package com.salla.push

import android.net.TrafficStats
import com.salla.push.internal.api.ApiClient
import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.api.HttpRequest
import com.salla.push.internal.api.HttpResult
import com.salla.push.internal.api.HttpUrlConnectionTransport
import com.salla.push.internal.api.Network
import com.salla.push.internal.api.Routes
import com.salla.push.internal.api.TagsResult
import com.salla.push.internal.api.TransportFailure
import com.salla.push.internal.engine.Credentials
import com.salla.push.internal.log.SecretString
import com.salla.push.internal.util.SystemClock
import com.salla.push.internal.util.SALLAPUSH_SOCKET_TAG
import com.salla.push.internal.util.withSocketTag
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** The SDK's transport against a real HTTP server: socket timeouts bound each read, the total deadline the exchange. */
class TransportTest : SdkTestBase() {

    private val server = MockWebServer()

    @Before
    fun startServer() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun put(transport: HttpUrlConnectionTransport): HttpResult = transport.send(
        HttpRequest("PUT", "http://127.0.0.1:${server.port}/devices/d1", Routes.DEVICE, "{}".toByteArray(), null, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS),
    )

    @Test
    fun test_aTricklingAnswer_endsAtTheTotalDeadline_asATimeout() {
        // Each read returns long before the 20 s read timeout, but the whole body would take 6 s.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(ByteArray(4_096) { ' '.code.toByte() }))
                .throttleBody(64, 100, TimeUnit.MILLISECONDS),
        )
        val started = System.nanoTime()
        val result = put(HttpUrlConnectionTransport("SallaPush-Test/1.0", totalTimeoutMillis = 1_000L))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(TransportFailure.TIMEOUT, (result as? HttpResult.Failure)?.kind)
        assertTrue("took $elapsed ms", elapsed in 900L..2_500L)
    }

    @Test
    fun test_headersTrickledByteByByte_endAtTheTotalDeadline_asATimeout() {
        // Every byte comes well within the read timeout, but the status line and headers alone take about 6 s: only the
        // deadline's watchdog can end the read the connection is still inside (reading the response code).
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                listener.accept().use { socket ->
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Padding: ${"x".repeat(40)}\r\nContent-Length: 2\r\n\r\n{}"
                    val output = socket.getOutputStream()
                    for (byte in head.encodeToByteArray()) {
                        output.write(byte.toInt())
                        output.flush()
                        Thread.sleep(60)
                    }
                }
            } catch (e: Exception) {
                // The client closed the connection at its deadline.
            }
        }.apply { isDaemon = true }.start()
        val started = System.nanoTime()
        val result = HttpUrlConnectionTransport("SallaPush-Test/1.0", totalTimeoutMillis = 1_000L).send(
            HttpRequest("PUT", "http://127.0.0.1:${listener.localPort}/devices/d1", Routes.DEVICE, "{}".toByteArray(), null, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS),
        )
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        listener.close()
        assertEquals(TransportFailure.TIMEOUT, (result as? HttpResult.Failure)?.kind)
        assertTrue("took $elapsed ms", elapsed in 900L..2_500L)
    }

    @Test
    fun test_noAnswerAtAll_endsAtTheTotalDeadline_asATimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        val result = put(HttpUrlConnectionTransport("SallaPush-Test/1.0", totalTimeoutMillis = 1_000L))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(TransportFailure.TIMEOUT, (result as? HttpResult.Failure)?.kind)
        assertTrue("took $elapsed ms", elapsed in 900L..2_500L)
    }

    @Test
    fun test_aNormalAnswer_isRead_withinTheDeadline() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("X-Request-Id", "req-7").setBody("{\"linked\":true}"))
        val result = put(HttpUrlConnectionTransport("SallaPush-Test/1.0", totalTimeoutMillis = 5_000L))
        val response = result as? HttpResult.Response ?: throw AssertionError("not a response: $result")
        assertEquals(200, response.status)
        assertEquals("{\"linked\":true}", response.body.decodeToString())
        assertEquals("req-7", response.requestId)
    }

    @Test
    fun test_aRefusedConnection_isANetworkFailure_namingTheExceptionAndItsMessage() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = HttpUrlConnectionTransport("SallaPush-Test/1.0").send(
            HttpRequest("PUT", "http://127.0.0.1:$closed/devices/d1", Routes.DEVICE, "{}".toByteArray(), null, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS),
        )
        val failure = result as? HttpResult.Failure ?: throw AssertionError("not a failure: $result")
        assertEquals(TransportFailure.NETWORK, failure.kind)
        assertTrue(failure.detail, failure.detail.matches(Regex("^ConnectException: .+")))
        val described = com.salla.push.internal.api.ApiFailure.transport("PUT", Routes.DEVICE, failure).describe()
        assertTrue(described, described.startsWith("PUT /devices/{id} (network: ConnectException: "))
    }

    // guard: the JDK's HttpURLConnection refuses PATCH (Android's sends it); a refused method is a failure, never a crash.
    @Test
    fun test_aMethodTheJdkRefuses_isATransportFailure_neverACrash() {
        val result = HttpUrlConnectionTransport("SallaPush-Test/1.0").send(
            HttpRequest("PATCH", "http://127.0.0.1:${server.port}/devices/d1/tags", Routes.TAGS, "{}".toByteArray(), null, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS),
        )
        val failure = result as? HttpResult.Failure ?: throw AssertionError("not a failure: $result")
        assertEquals(TransportFailure.NETWORK, failure.kind)
        assertTrue(failure.detail, failure.detail.startsWith("ProtocolException"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun test_theTotalDeadline_isNamedInTheCause_aSocketTimeoutNamesItsPhase() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(ByteArray(4_096) { ' '.code.toByte() }))
                .throttleBody(64, 100, TimeUnit.MILLISECONDS),
        )
        val trickled = put(HttpUrlConnectionTransport("SallaPush-Test/1.0", totalTimeoutMillis = 1_500L))
        assertEquals("no complete answer within 1.5 s", (trickled as? HttpResult.Failure)?.detail)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val silent = HttpUrlConnectionTransport("SallaPush-Test/1.0").send(
            HttpRequest("PUT", "http://127.0.0.1:${server.port}/devices/d1", Routes.DEVICE, "{}".toByteArray(), null, 300),
        )
        val failure = silent as? HttpResult.Failure ?: throw AssertionError("not a failure: $silent")
        assertEquals(TransportFailure.TIMEOUT, failure.kind)
        assertTrue(failure.detail, failure.detail.startsWith("SocketTimeoutException"))
    }

    @Test
    fun test_theDefaultDeadline_isOneMinute_likeTheIosResourceTimeout() {
        assertEquals(60_000L, HttpUrlConnectionTransport.TOTAL_TIMEOUT_MILLIS)
    }

    @Test
    fun test_socketsAreTaggedAsSallaPush_onlyWhileTheRequestRuns() {
        TrafficStats.clearThreadStatsTag()
        assertEquals(SALLAPUSH_SOCKET_TAG, withSocketTag { TrafficStats.getThreadStatsTag() })
        assertNotEquals(SALLAPUSH_SOCKET_TAG, TrafficStats.getThreadStatsTag())
    }

    // ── Answer size: the largest answer the server's contract allows is a tags answer ──

    /** A tags answer of 100 keys of 64 characters, each with [value] (at most 255 UTF-16 units). */
    private fun tagsAnswer(value: String): String {
        val tags = JSONObject()
        for (index in 0 until 100) tags.put("k" + index.toString().padStart(63, '0'), value)
        return JSONObject().put("tags", tags).toString()
    }

    /**
     * [body] answered to a tags request, through the production transport and API client. The JDK's HttpURLConnection
     * refuses PATCH (Android's sends it), so the request goes as POST: these tests are about sizes, not methods.
     */
    private fun tagsAnswered(body: String): ApiResult<TagsResult> {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))
        val transport = HttpUrlConnectionTransport("SallaPush-Test/1.0")
        val network = Network { request, onResult ->
            val method = if (request.method == "PATCH") "POST" else request.method
            onResult(transport.send(HttpRequest(method, request.url, request.route, request.body, request.authorization, request.readTimeoutMillis)))
        }
        val client = ApiClient(network, SystemClock, "http://127.0.0.1:${server.port}")
        var result: ApiResult<TagsResult>? = null
        client.tags(Credentials(Http.DEVICE_ID, SecretString(Http.SECRET)), mapOf("k" to "v"), emptyList()) { result = it }
        return result ?: throw AssertionError("no result")
    }

    @Test
    fun test_theLargestTagsAnswer_with3ByteCharacters_isRead() {
        val value = "€".repeat(255)
        val body = tagsAnswer(value)
        assertEquals(83_510, body.toByteArray(Charsets.UTF_8).size)
        val tags = (tagsAnswered(body) as? ApiResult.Ok)?.value?.tags ?: throw AssertionError("refused")
        assertEquals(100, tags.size)
        assertEquals(value, tags["k" + "0".repeat(63)])
    }

    @Test
    fun test_aTagsAnswerOfEscapedControlCharacters_ofAbout160KB_isRead() {
        val value = "\u0001".repeat(255)
        val body = tagsAnswer(value)
        // JSON escapes each control character to 6 bytes: the largest answer the contract allows.
        assertEquals(160_010, body.toByteArray(Charsets.UTF_8).size)
        val tags = (tagsAnswered(body) as? ApiResult.Ok)?.value?.tags ?: throw AssertionError("refused")
        assertEquals(100, tags.size)
        assertEquals(value, tags["k" + "0".repeat(63)])
    }

    @Test
    fun test_anAnswerLargerThan256KB_isRefused_andNeverParsed() {
        val failure = (tagsAnswered("{\"tags\":{\"k\":\"" + "x".repeat(262_200) + "\"}}") as? ApiResult.Err)?.failure
        assertEquals(FailureClass.INVALID_RESPONSE, failure?.failureClass)
        assertEquals("larger than 256 KB", failure?.detail)
    }

    @Test
    fun test_anAnswerOfExactly256KB_isRead() {
        val prefix = "{\"tags\":{\"k\":\""
        val suffix = "\"}}"
        val body = prefix + "x".repeat(HttpUrlConnectionTransport.MAX_BODY_BYTES - prefix.length - suffix.length) + suffix
        assertEquals(HttpUrlConnectionTransport.MAX_BODY_BYTES, body.length)
        assertEquals(1, (tagsAnswered(body) as? ApiResult.Ok)?.value?.tags?.size)
    }
}
