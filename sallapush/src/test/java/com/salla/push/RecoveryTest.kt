package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.DeviceOp
import com.salla.push.internal.engine.DeviceOpKind
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.engine.RegStatus
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest : SdkTestBase() {

    @Test
    fun test_unknownDevice_withPendingLogout_isSettledByTheNewRow() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(404, "unknown_device") }
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2) }
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
        assertEquals(Http.DEVICE_ID_2, SallaPush.User.pushSubscription.id)
    }

    @Test
    fun test_unknownDevice_relinksAndResendsTheTags_withoutALineOfItsOwn() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "unknown_device") }
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2) }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(15.0)
        assertEquals(2, h.net.calls(Routes.LINK).size)
        val tagCalls = h.net.calls(Routes.TAGS)
        assertEquals(2, tagCalls.size)
        assertEquals("gold", tagCalls[1].body?.getJSONObject("add")?.getString("plan"))
        // The new row adds no line of its own (as on iOS): the one register.reregistering line is the re-registration's.
        assertEquals(listOf("unknown_device"), h.capture.of(LogCode.REGISTER_REREGISTERING).map { it.metadata[Meta.REASON] })
    }

    @Test
    fun test_unknownDevice_guestWithoutTags_registersAgain_withoutAReplayLine() {
        h.registered()
        SallaPush.logout()
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "unknown_device") }
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2) }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(5.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertTrue(h.net.calls(Routes.LINK).isEmpty())
        assertTrue(h.net.calls(Routes.TAGS).isEmpty())
        assertEquals(listOf("unknown_device"), h.capture.of(LogCode.REGISTER_REREGISTERING).map { it.metadata[Meta.REASON] })
    }

    @Test
    fun test_routesClosed_keepsOps_dormant24h() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(404, "not_found", message = "Not found") }
        SallaPush.logout()
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.NET_ROUTES_CLOSED))
        assertEquals(1, h.engine().identity.ops.size)
        h.advance(23.0 * 3600)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        h.advance(2.0 * 3600)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
    }

    /** The server never answers device_disabled: one is an ordinary refusal of its request. */
    @Test
    fun test_a401DeviceDisabledOnUpdate_isAClientError_notAReregistration() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "device_disabled", reason = "deleted") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(
            "PUT /devices/{id} (401 device_disabled: \"Rejected\", request req-401) was rejected; retrying in 24 h.",
            h.capture.of(LogCode.NET_CLIENT_ERROR).single().message,
        )
        assertFalse(h.capture.has(LogCode.REGISTER_REREGISTERING))
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(RegStatus.REGISTERED, h.engine().device.registration.status)
        h.advance(3600.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    // ── The re-registration limit: at most one new registration an hour ──

    @Test
    fun test_reregistrationValve_firstAtOnce_secondHeldUntilAnHourAfterTheFirst() {
        val first = holdASecondReregistration()
        assertTrue(h.capture.has(LogCode.REGISTER_REREGISTERING))
        val held = h.capture.of(LogCode.NET_CLIENT_ERROR).single()
        assertEquals("unknown_device", held.metadata[Meta.REASON])
        assertEquals("404", held.metadata[Meta.HTTP_STATUS])
        assertTrue((held.metadata[Meta.DELAY_S]?.toLong() ?: 0L) in 2390L..2400L)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(first + HOUR - seconds(h) - 1.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        h.advance(2.0)
        assertEquals(3, h.net.calls(Routes.REGISTER).size)
    }

    @Test
    fun test_secondInvalidSecretWithinAnHour_heldUntilAnHourAfterTheFirst() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        SallaPush.User.setLanguage("en")
        h.advance(5.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        val first = h.engine().lastReregisterAt ?: throw AssertionError("no re-registration")
        h.advance(600.0)
        SallaPush.User.setLanguage("fr")
        h.advance(5.0)
        assertEquals("invalid_device_secret", h.capture.of(LogCode.NET_CLIENT_ERROR).single().metadata[Meta.REASON])
        assertEquals(first + HOUR, registerOp().retry.dormantUntil ?: 0.0, TIME_DELTA)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(first + HOUR - seconds(h) - 1.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        h.advance(2.0)
        assertEquals(3, h.net.calls(Routes.REGISTER).size)
        assertFalse(SallaPush.User.pushSubscription.id == null)
    }

    @Test
    fun test_repeatedUnknownDevice_oneRegistrationPerHour_anHourAfterThePrevious() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "unknown_device") }
        h.net.on("PATCH", Routes.TAGS) { Http.error(404, "unknown_device") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        val first = h.engine().lastReregisterAt ?: throw AssertionError("no re-registration")
        SallaPush.User.addTag("plan", "gold")
        h.advance(12.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertTrue(h.capture.has(LogCode.NET_CLIENT_ERROR))
        // A server that keeps answering unknown_device gets exactly one registration per hour, an hour after the previous.
        for (hour in 1..3) {
            val due = first + HOUR * hour
            assertEquals(due, registerOp().retry.dormantUntil ?: 0.0, TIME_DELTA)
            h.advance(due - seconds(h) - 1.0)
            assertEquals(1 + hour, h.net.calls(Routes.REGISTER).size)
            h.advance(15.0)
            assertEquals(2 + hour, h.net.calls(Routes.REGISTER).size)
            assertEquals(1 + hour, h.net.calls(Routes.TAGS).size)
        }
        assertEquals(first + HOUR * 4, registerOp().retry.dormantUntil ?: 0.0, TIME_DELTA)
    }

    @Test
    fun test_heldReregistration_notShortened_byForegroundNewTokenOrWorkerFlush() {
        val first = holdASecondReregistration()
        h.background()
        h.foreground()
        h.deliverToken(Harness.FCM_TOKEN_2)
        h.work.scheduled.clear()
        // The hold is a deliberate wait: the worker ends, and a follow-up run is enqueued for the end of the hold.
        assertEquals(FlushOutcome.SUCCESS, h.runWorker())
        val followUp = h.work.replacingFlushes().single()
        assertEquals(first + HOUR - seconds(h), followUp.delaySeconds.toDouble(), 1.0)
        h.advance(60.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertEquals(first + HOUR, registerOp().retry.dormantUntil ?: 0.0, TIME_DELTA)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(first + HOUR - seconds(h) + 1.0)
        val registers = h.net.calls(Routes.REGISTER)
        assertEquals(3, registers.size)
        assertEquals(Harness.FCM_TOKEN_2, registers.last().body?.getString("push_token"))
    }

    @Test
    fun test_heldReregistration_survivesARelaunch_andCountsAsThePrevious() {
        val heldUntil = holdASecondReregistration() + HOUR
        val next = h.relaunch(keepCalls = true)
        assertEquals(heldUntil, next.engine().lastReregisterAt ?: 0.0, TIME_DELTA)
        assertEquals(heldUntil, registerOp(next).retry.dormantUntil ?: 0.0, TIME_DELTA)
        next.advance(60.0)
        assertEquals(0, next.net.calls(Routes.REGISTER).size)

        // The server still purges the row: the next re-registration waits an hour after the restored one.
        next.net.on("PUT", Routes.DEVICE) { Http.error(404, "unknown_device") }
        next.advance(heldUntil - seconds(next) + 1.0)
        assertEquals(1, next.net.calls(Routes.REGISTER).size)
        SallaPush.User.setLanguage("de")
        next.advance(3.0)
        assertEquals(1, next.net.calls(Routes.REGISTER).size)
        assertEquals(heldUntil + HOUR, registerOp(next).retry.dormantUntil ?: 0.0, TIME_DELTA)
        assertTrue(next.capture.has(LogCode.NET_CLIENT_ERROR))
        next.close()
    }

    @Test
    fun test_heldReregistration_afterTheClockMovedBack_holdsAtMostAnHour() {
        holdASecondReregistration()
        var restartedAt = 0.0
        val next = h.relaunch(keepCalls = true) { relaunched ->
            relaunched.clock.millis -= 3L * 3600 * 1000
            restartedAt = seconds(relaunched)
        }
        assertEquals(restartedAt + HOUR, registerOp(next).retry.dormantUntil ?: 0.0, TIME_DELTA)
        next.advance(restartedAt + HOUR - seconds(next) - 1.0)
        assertEquals(0, next.net.calls(Routes.REGISTER).size)
        next.advance(2.0)
        assertEquals(1, next.net.calls(Routes.REGISTER).size)
        next.close()
    }

    /**
     * Two device-level re-registrations within an hour: the first runs at once, the second is held. Returns the time of
     * the first one (epoch seconds); the hold ends an hour after it.
     */
    private fun holdASecondReregistration(): Double {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "unknown_device") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        val first = h.engine().lastReregisterAt ?: throw AssertionError("no re-registration")
        h.advance(1200.0)
        SallaPush.User.setLanguage("fr")
        h.advance(3.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertEquals(first + HOUR, registerOp().retry.dormantUntil ?: 0.0, TIME_DELTA)
        return first
    }

    private fun registerOp(harness: Harness = h): DeviceOp = harness.engine().device.ops.single { it.kind == DeviceOpKind.REGISTER }

    private fun seconds(harness: Harness): Double = harness.clock.millis / 1000.0

    private companion object {
        const val HOUR = 3600.0
        const val TIME_DELTA = 0.001
    }

    @Test
    fun test_identityClientError_dormant24h_thenRetried() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(400, "invalid_request") }
        SallaPush.logout()
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.IDENTITY_CLIENT_ERROR))
        h.advance(20.0 * 3600)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        h.advance(5.0 * 3600)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
    }
}
