package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest : SdkTestBase() {

    @Test
    fun test_sameFingerprintWithin24h_sendsNothing() {
        h.registered()
        h.background()
        h.foreground()
        h.advance(3600.0)
        h.background()
        h.foreground()
        h.advance(5.0)
        assertEquals(0, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_after24h_heartbeat() {
        h.registered()
        h.advance(24.0 * 3600 + 1)
        h.background()
        h.foreground()
        h.advance(3.0)
        val puts = h.net.calls(Routes.DEVICE)
        assertEquals(1, puts.size)
        assertTrue(h.capture.has(LogCode.HEARTBEAT_SENT))
        val body = puts[0].body ?: throw AssertionError()
        assertTrue(body.has("opted_in"))
        assertFalse(body.has("push_token"))
        assertEquals("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}", puts[0].url)
        assertEquals(Http.SECRET, puts[0].authorization)
    }

    @Test
    fun test_clockInFuture_treatedAsDue() {
        h.registered()
        h.engine().device.registration.lastFullUpdateAt = h.clock.seconds + 10 * 86_400
        h.background()
        h.foreground()
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_tokenChange_putsOnSameDevice() {
        h.registered()
        h.deliverToken(Harness.FCM_TOKEN_2)
        h.advance(3.0)
        val puts = h.net.calls(Routes.DEVICE)
        assertEquals(1, puts.size)
        assertEquals(Harness.FCM_TOKEN_2, puts[0].body?.getString("push_token"))
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(Harness.FCM_TOKEN_2, SallaPush.User.pushSubscription.token)
        assertTrue(h.capture.has(LogCode.UPDATE_SENT))
    }

    @Test
    fun test_permissionRevoked_putsOptedInFalse() {
        h.registered()
        h.permission = false
        SallaPush.Notifications.refreshPermission()
        h.advance(3.0)
        val puts = h.net.calls(Routes.DEVICE)
        assertEquals(1, puts.size)
        assertFalse(puts[0].body?.getBoolean("opted_in") ?: true)
        assertFalse(SallaPush.User.pushSubscription.optedIn)
        assertTrue(h.capture.has(LogCode.PERMISSION_CHANGED))
    }

    @Test
    fun test_hostPromptGranted_activityResumed_putsOptedInTrue() {
        h.permission = false
        h.registered()
        assertFalse(h.net.calls(Routes.REGISTER).first().body?.getBoolean("opted_in") ?: true)
        h.permission = true
        h.background()
        h.foreground()
        h.advance(3.0)
        val puts = h.net.calls(Routes.DEVICE)
        assertEquals(1, puts.size)
        assertTrue(puts[0].body?.getBoolean("opted_in") ?: false)
        assertTrue(SallaPush.User.pushSubscription.optedIn)
    }

    @Test
    fun test_unchangedTokenDelivery_rereadsPermission_noNetworkWhenSame() {
        h.registered()
        h.deliverToken()
        h.advance(3.0)
        assertEquals(0, h.net.calls(Routes.DEVICE).size)
        assertTrue(h.capture.has(LogCode.TOKEN_UNCHANGED))
        h.permission = false
        h.deliverToken()
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_languageChange_putsTheAppsValueAsGiven_unchangedIgnored() {
        h.registered()
        SallaPush.User.setLanguage("en-US")
        h.advance(3.0)
        val puts = h.net.calls(Routes.DEVICE)
        assertEquals(1, puts.size)
        // As on iOS: the app's value is sent as given; only the device fallback is reduced.
        assertEquals("en-US", puts[0].body?.getString("language"))
        SallaPush.User.setLanguage("  en-US ")
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        SallaPush.User.setLanguage(null)
        h.advance(3.0)
        assertEquals("ar", h.net.calls(Routes.DEVICE)[1].body?.getString("language"))
        assertFalse(h.capture.has(LogCode.API_INVALID_ARGUMENT))
    }

    @Test
    fun test_languageNotATag_isKept_trimmedAndCutTo16Utf16Units_blankMeansTheDeviceLanguage() {
        h.registered()
        SallaPush.User.setLanguage(" zh-Hant-TW-x-private ")
        h.advance(3.0)
        assertEquals("zh-Hant-TW-x-pri", h.net.calls(Routes.DEVICE).last().body?.getString("language"))
        // A cut never splits a surrogate pair, and a space it leaves at the end is trimmed (the iOS rule).
        SallaPush.User.setLanguage("arabic-" + "😀".repeat(5))
        h.advance(3.0)
        assertEquals("arabic-" + "😀".repeat(4), h.net.calls(Routes.DEVICE).last().body?.getString("language"))
        SallaPush.User.setLanguage("abcdefghijklmno pqr")
        h.advance(3.0)
        assertEquals("abcdefghijklmno", h.net.calls(Routes.DEVICE).last().body?.getString("language"))
        SallaPush.User.setLanguage("   ")
        h.advance(3.0)
        assertEquals("ar", h.net.calls(Routes.DEVICE).last().body?.getString("language"))
        assertEquals(4, h.net.calls(Routes.DEVICE).size)
    }

    // ── A change made while an update is on the wire goes out right after it ──

    @Test
    fun test_optOutWhileAnUpdateIsOnTheWire_isPutRightAfterTheAnswer() {
        val held = holdAnUpdate()
        SallaPush.User.pushSubscription.optOut()
        h.settle()
        val second = answerAndAwaitTheNextPut(held)
        assertFalse(second.body?.getBoolean("opted_in") ?: true)
    }

    @Test
    fun test_newTokenWhileAnUpdateIsOnTheWire_isPutRightAfterTheAnswer() {
        val held = holdAnUpdate()
        h.deliverToken(Harness.FCM_TOKEN_2)
        val second = answerAndAwaitTheNextPut(held)
        assertEquals(Harness.FCM_TOKEN_2, second.body?.getString("push_token"))
        h.advance(60.0)
        assertEquals(2, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_languageChangeWhileAnUpdateIsOnTheWire_isPutRightAfterTheAnswer() {
        val held = holdAnUpdate()
        SallaPush.User.setLanguage("fr")
        h.settle()
        val second = answerAndAwaitTheNextPut(held)
        assertEquals("fr", second.body?.getString("language"))
    }

    @Test
    fun test_permissionChangeWhileAnUpdateIsOnTheWire_isPutRightAfterTheAnswer() {
        val held = holdAnUpdate()
        h.permission = false
        SallaPush.Notifications.refreshPermission()
        h.settle()
        val second = answerAndAwaitTheNextPut(held)
        assertFalse(second.body?.getBoolean("opted_in") ?: true)
    }

    @Test
    fun test_noChangeWhileAnUpdateIsOnTheWire_noSecondPut() {
        val held = holdAnUpdate()
        held.respond(Http.empty())
        h.advance(120.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    /** Registered, then a language change whose PUT the server holds; later PUTs are answered at once. */
    private fun holdAnUpdate(): com.salla.push.testing.FakeCall {
        h.registered()
        var held: com.salla.push.testing.FakeCall? = null
        h.net.on("PUT", Routes.DEVICE) { call ->
            if (held == null) {
                held = call
                null
            } else {
                Http.empty()
            }
        }
        SallaPush.User.setLanguage("en")
        h.advance(2.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        return held ?: throw AssertionError("no update on the wire")
    }

    /** Answers the held PUT; the change made meanwhile goes out with the 1 s debounce, not at the next foreground. */
    private fun answerAndAwaitTheNextPut(held: com.salla.push.testing.FakeCall): com.salla.push.testing.FakeCall {
        held.respond(Http.empty())
        h.settle()
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        h.advance(1.5)
        val puts = h.net.calls(Routes.DEVICE)
        assertEquals(2, puts.size)
        return puts[1]
    }

    // ── Time zone and locale changes are update triggers, as on iOS ──

    @Test
    fun test_timezoneChange_inTheForeground_isPutWithTheNewZone() {
        h.registered()
        h.device.zone = "Europe/Paris"
        h.context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        h.advance(3.0)
        val put = h.net.calls(Routes.DEVICE).single()
        assertEquals("Europe/Paris", put.body?.getString("timezone"))
    }

    @Test
    fun test_localeChange_inTheForeground_isPutWithTheNewLanguage() {
        h.registered()
        h.device.language = "fr"
        h.context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_LOCALE_CHANGED))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        h.advance(3.0)
        assertEquals("fr", h.net.calls(Routes.DEVICE).single().body?.getString("language"))
    }

    @Test
    fun test_settingsReceiver_registeredWhileTheMainEngineRuns_only() {
        val settings = com.salla.push.testing.FakeDeviceSettings()
        h.deviceSettings = settings
        val consentRequired = SallaPushConfig.Builder().setRequiresUserConsent(true).build()
        h.healthyServer()
        h.start(config = consentRequired)
        assertFalse("nothing runs before consent", settings.started)
        SallaPush.setConsentGiven(true)
        h.settle()
        assertTrue(settings.started)
        SallaPush.setConsentGiven(false)
        h.settle()
        assertFalse("a withdrawal stops the receiver", settings.started)
        SallaPush.setConsentGiven(true)
        h.settle()
        assertTrue(settings.started)
        assertEquals(2, settings.starts)
    }

    @Test
    fun test_optOutAndOptIn_updateOptedIn() {
        h.registered()
        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        assertFalse(h.net.calls(Routes.DEVICE).last().body?.getBoolean("opted_in") ?: true)
        assertTrue(h.capture.has(LogCode.SUBSCRIPTION_OPTED_OUT))
        SallaPush.User.pushSubscription.optIn()
        h.advance(3.0)
        assertTrue(h.net.calls(Routes.DEVICE).last().body?.getBoolean("opted_in") ?: false)
        assertTrue(h.capture.has(LogCode.SUBSCRIPTION_OPTED_IN))
    }

    @Test
    fun test_optInOrOptOutThatChangesNothing_logsNothing_andWritesNothing() {
        h.registered()
        val stateBefore = h.stateJson()
        // A host that calls optIn() at every launch: the device was never opted out.
        SallaPush.User.pushSubscription.optIn()
        h.advance(3.0)
        assertFalse(h.capture.has(LogCode.SUBSCRIPTION_OPTED_IN))
        assertEquals(stateBefore, h.stateJson())
        assertTrue(h.net.calls(Routes.DEVICE).isEmpty())

        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        assertEquals(1, h.capture.count(LogCode.SUBSCRIPTION_OPTED_OUT))
        val optedOut = h.stateJson()
        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        assertEquals(1, h.capture.count(LogCode.SUBSCRIPTION_OPTED_OUT))
        assertEquals(optedOut, h.stateJson())
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_updateSent_namesTheFieldsThatChanged() {
        h.registered()
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.device.zone = "Europe/Paris"
        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        assertEquals(
            listOf("Device updated (language).", "Device updated (opted_in, timezone)."),
            h.capture.of(LogCode.UPDATE_SENT).map { it.message },
        )
        // After a relaunch the last fields the server accepted are known only as a fingerprint: the line says so.
        val next = h.relaunch(keepCalls = true) { it.device.zone = "Europe/Paris" }
        SallaPush.User.setLanguage("fr")
        next.advance(3.0)
        assertEquals(
            listOf("Device updated (first update since launch: a device field changed since the last one)."),
            next.capture.of(LogCode.UPDATE_SENT).map { it.message },
        )
        next.close()
    }

    @Test
    fun test_updateClientError_dormant24h() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(400, "invalid_request", message = "language too long") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.NET_CLIENT_ERROR))
        SallaPush.User.setLanguage("fr")
        h.advance(3600.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(24.0 * 3600)
        assertEquals(2, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_timezoneChange_alsoSeenAtTheNextForeground() {
        h.registered()
        h.device.zone = "Europe/Paris"
        h.background()
        h.foreground()
        h.advance(3.0)
        assertEquals("Europe/Paris", h.net.calls(Routes.DEVICE).single().body?.getString("timezone"))
    }
}
