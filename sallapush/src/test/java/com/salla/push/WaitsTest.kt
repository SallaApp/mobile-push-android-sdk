package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.DeviceOpKind
import com.salla.push.internal.engine.onConnectivityRegained
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triggers (a new identity call, a foreground, connectivity regained) make network- and
 * 5xx-failed ops ready at once, but never shorten a 429 wait or a dormant wait. Every case fires every trigger
 * during the wait and checks that the waiting request goes out only when the wait ends.
 */
class WaitsTest : SdkTestBase() {

    @Test
    fun test_a429Wait_isNeverShortened() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "3600") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        holdsUntil("PUT", Routes.DEVICE, waitSeconds = 3600.0, sentBefore = 1)
    }

    // ── A 429 is the server pacing SallaPush: it never counts toward the six attempts of a process (as on iOS) ──

    /**
     * The same case as the iOS SDK's: five 429s, then 503s. The op stops
     * only after six transient failures (eleven requests), then waits for a trigger.
     */
    @Test
    fun test_rateLimitedAnswers_neverCountTowardTheSixAttemptsOfAProcess() {
        h.registered()
        var answered = 0
        h.net.on("PUT", Routes.DEVICE) {
            answered += 1
            if (answered <= 5) Http.error(429, "rate_limited", retryAfter = "1") else Http.error(503, "server_error")
        }
        SallaPush.User.setLanguage("en")
        h.advance(20.0)
        assertEquals(6, answered)
        assertEquals(5, h.capture.count(LogCode.NET_RATE_LIMITED))
        assertEquals(0, h.capture.count(LogCode.NET_RETRY_EXHAUSTED))
        assertEquals(1, h.capture.count(LogCode.NET_RETRY))
        // Backoff counts every attempt (429s included), so the 503s wait 64 s, 128 s, … (jitter 1.0 in tests).
        h.advance(64.0 + 128 + 256 + 512 + 1024)
        assertEquals(11, answered)
        assertEquals(6, h.capture.count(LogCode.NET_RETRY) + h.capture.count(LogCode.NET_RETRY_EXHAUSTED))
        val exhausted = h.capture.of(LogCode.NET_RETRY_EXHAUSTED).single()
        assertEquals(
            "PUT /devices/{id} (503 server_error, request req-503) failed 6 times; waiting for the next launch, foreground or network change.",
            exhausted.message,
        )
        h.advance(3000.0)
        assertEquals(11, answered)
        // A trigger resumes it.
        h.net.on("PUT", Routes.DEVICE) {
            answered += 1
            Http.empty()
        }
        h.background()
        h.foreground()
        h.advance(3.0)
        assertEquals(12, answered)
    }

    @Test
    fun test_fiveRateLimitedAnswersThenA503_isRetriedWithBackoff_neverExhausted() {
        h.registered()
        var answered = 0
        h.net.on("PUT", Routes.DEVICE) {
            answered += 1
            when {
                answered <= 5 -> Http.error(429, "rate_limited", retryAfter = "1")
                answered == 6 -> Http.error(503, "server_error")
                else -> Http.empty()
            }
        }
        SallaPush.User.setLanguage("en")
        h.advance(200.0)
        assertEquals(7, answered)
        assertEquals(0, h.capture.count(LogCode.NET_RETRY_EXHAUSTED))
        assertEquals("PUT /devices/{id} (503 server_error, request req-503) failed; retry 6 in 64 s.", h.capture.of(LogCode.NET_RETRY).single().message)
        assertTrue(h.engine().device.ops.none { it.kind == DeviceOpKind.UPDATE })
    }

    @Test
    fun test_registrationDormant_unknownApp_isNeverShortened() = registrationDormancy(Http.error(404, "unknown_app"))

    @Test
    fun test_registrationDormant_routesClosed_isNeverShortened() = registrationDormancy(Http.error(404, "not_found", message = "Not found"))

    @Test
    fun test_registrationDormant_invalidRequest_isNeverShortened() = registrationDormancy(Http.error(400, "invalid_request"))

    @Test
    fun test_deviceRoutesClosed_identityOpKept_isNeverShortened() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(404, "not_found", message = "Not found") }
        SallaPush.logout()
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        holdsUntil("POST", Routes.UNLINK, waitSeconds = 86_400.0, sentBefore = 1, identityCalls = false)
    }

    @Test
    fun test_identityOpDormantAfterA4xx_isNeverShortened() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(400, "invalid_request") }
        SallaPush.logout()
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        holdsUntil("POST", Routes.UNLINK, waitSeconds = 86_400.0, sentBefore = 1, identityCalls = false)
    }

    @Test
    fun test_updateDormantAfterA4xx_isNeverShortened() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(400, "invalid_request") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        holdsUntil("PUT", Routes.DEVICE, waitSeconds = 86_400.0, sentBefore = 1)
    }

    @Test
    fun test_tagsDormantAfterA4xx_isNeverShortened() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.error(403, "forbidden") }
        SallaPush.User.addTag("plan", "gold")
        h.advance(12.0)
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        holdsUntil("PATCH", Routes.TAGS, waitSeconds = 86_400.0, sentBefore = 1, identityCalls = false)
    }

    private fun registrationDormancy(answer: com.salla.push.internal.api.HttpResult) {
        h.healthyServer()
        h.net.on("POST", Routes.REGISTER) { answer }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        h.net.on("POST", Routes.REGISTER) { Http.registered() }
        holdsUntil("POST", Routes.REGISTER, waitSeconds = 86_400.0, sentBefore = 1)
    }

    /**
     * Fires every trigger three times during a wait of [waitSeconds] that started about 2 s ago, and checks that the
     * waiting request ([method] [route], sent [sentBefore] times so far) goes out only once the wait is over.
     */
    private fun holdsUntil(method: String, route: String, waitSeconds: Double, sentBefore: Int, identityCalls: Boolean = true) {
        val sent = { h.net.calls(route).count { it.method == method } }
        assertEquals(sentBefore, sent())
        for (round in 1..3) {
            if (identityCalls) {
                SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
                SallaPush.logout()
            }
            h.background()
            h.foreground()
            val engine = h.engine()
            engine.task { engine.onConnectivityRegained() }
            h.settle()
            h.advance(waitSeconds / 4)
            assertEquals("round $round", sentBefore, sent())
        }
        h.advance(waitSeconds / 4)
        assertEquals(sentBefore + 1, sent())
    }
}
