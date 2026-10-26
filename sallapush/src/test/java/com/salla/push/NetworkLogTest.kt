package com.salla.push

import com.salla.push.internal.api.HttpResult
import com.salla.push.internal.api.Routes
import com.salla.push.internal.api.TransportFailure
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** What the network log lines say: a transport failure names its cause. */
class NetworkLogTest : SdkTestBase() {

    private val cleartext = "UnknownServiceException: CLEARTEXT communication to 10.0.2.2 not permitted by network security policy"

    @Test
    fun test_aTransportFailure_namesItsCause_inLogcat_andInTheListenerEvent() {
        val events = CopyOnWriteArrayList<SallaPushLogEvent>()
        SallaPush.Debug.addLogListener(SallaPushLogListener { events.add(it) }, SallaPushLogLevel.INFO)
        h.healthyServer()
        h.net.on("POST", Routes.REGISTER) { HttpResult.Failure(TransportFailure.NETWORK, cleartext) }
        h.start()
        h.deliverToken()
        h.advance(200.0)

        val retry = h.capture.of(LogCode.NET_RETRY).first()
        assertEquals("POST /apps/{app_id}/devices (network: $cleartext) failed; retry 1 in 2 s.", retry.message)
        assertEquals("UnknownServiceException", retry.metadata[Meta.REASON])

        // At the default level, Logcat holds the exhausted line: it names the cause too.
        val exhausted = h.capture.of(LogCode.NET_RETRY_EXHAUSTED).single()
        assertTrue(exhausted.message, exhausted.message.contains("(network: $cleartext) failed 6 times; waiting for the next launch, foreground or network change."))
        val line = h.console.single { it.startsWith("WARN net.retry_exhausted:") }
        assertTrue(line, line.contains("CLEARTEXT communication to 10.0.2.2"))

        waitUntil { events.any { it.code == "net.retry_exhausted" } }
        assertTrue(events.first { it.code == "net.retry" }.message.contains(cleartext))
    }

    @Test
    fun test_aTimeout_saysSo() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { HttpResult.Failure(TransportFailure.TIMEOUT, "no complete answer within 60 s") }
        SallaPush.User.setLanguage("en")
        h.advance(2.0)
        val retry = h.capture.of(LogCode.NET_RETRY).single()
        assertEquals("PUT /devices/{id} (timeout: no complete answer within 60 s) failed; retry 1 in 2 s.", retry.message)
        assertFalse(retry.metadata.containsKey(Meta.REASON))
    }

    @Test
    fun test_retryExhausted_carriesNoDelay_noRetryIsTimed() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(503, "server_error") }
        SallaPush.User.setLanguage("en")
        h.advance(200.0)
        val retries = h.capture.of(LogCode.NET_RETRY)
        assertEquals(5, retries.size)
        assertTrue(retries.all { it.metadata.containsKey(Meta.DELAY_S) })
        val exhausted = h.capture.of(LogCode.NET_RETRY_EXHAUSTED).single()
        assertFalse(exhausted.metadata.containsKey(Meta.DELAY_S))
        assertEquals("6", exhausted.metadata[Meta.ATTEMPT])
        assertEquals("503", exhausted.metadata[Meta.HTTP_STATUS])
    }

    /** Log listeners run on the SDK's own thread: polls until [condition] holds, for at most 10 s. */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(2)
        }
    }
}
