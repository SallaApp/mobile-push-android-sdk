package com.salla.push

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.salla.push.internal.FirebaseToken
import com.salla.push.internal.FirebaseTokens
import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.RegStatus
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import com.salla.push.testing.keysList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class RegistrationTest : SdkTestBase() {

    @Test
    fun test_firstToken_registersOnceWithEveryField() {
        h.healthyServer()
        h.start()
        h.deliverToken()
        h.advance(2.0)

        val registers = h.net.calls(Routes.REGISTER)
        assertEquals(1, registers.size)
        val body = registers[0].body ?: throw AssertionError("no body")
        assertEquals("android", body.getString("platform"))
        assertEquals(Harness.FCM_TOKEN, body.getString("push_token"))
        assertTrue(body.getBoolean("opted_in"))
        assertEquals("5.0.37", body.getString("app_version"))
        assertEquals("1.1.0", body.getString("sdk_version"))
        assertEquals("14", body.getString("os_version"))
        assertEquals("Pixel 8", body.getString("device_model"))
        assertEquals("ar", body.getString("language"))
        assertEquals("Asia/Riyadh", body.getString("timezone"))
        assertFalse(body.has("apns_env"))
        assertFalse(body.has("previous_device_id"))
        assertNull(registers[0].authorization)
        assertEquals("${Routes.DEFAULT_BASE_URL}/apps/st_42_android/devices", registers[0].url)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals("Device registered: ${Http.DEVICE_ID} in 7 ms.", h.capture.of(LogCode.REGISTER_OK).single().message)
        assertEquals(Http.DEVICE_ID, h.capture.of(LogCode.REGISTER_OK).first().metadata["device_id"])
    }

    @Test
    fun test_theDefaultBaseUrl_isTheSallaPushServers() {
        h.healthyServer()
        h.start()
        h.deliverToken()
        h.advance(2.0)
        // The server's SDK routes are under /notifications/v1/sallapush.
        assertEquals(
            "https://api.salla.dev/notifications/v1/sallapush/apps/st_42_android/devices",
            h.net.calls(Routes.REGISTER).single().url,
        )
    }

    @Test
    fun test_aRegistrationAnswerWithOnlyIdAndSecret_registers() {
        h.healthyServer()
        // The server's whole answer.
        h.net.on("POST", Routes.REGISTER) { Http.json(201, """{"device_id":"${Http.DEVICE_ID}","device_secret":"${Http.SECRET}"}""") }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertFalse(h.capture.has(LogCode.NET_INVALID_RESPONSE))
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(RegStatus.REGISTERED, h.engine().device.registration.status)
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_theRegisterBody_holdsOnlyWhatTheServerStores() {
        h.registered()
        val keys = h.net.calls(Routes.REGISTER).single().body?.keysList()?.toSet() ?: throw AssertionError("no body")
        val stored = setOf("platform", "push_token", "opted_in", "app_version", "sdk_version", "os_version", "device_model", "language", "timezone")
        assertTrue("$keys", stored.containsAll(keys))
    }

    // guard: the installation id store is deleted, so nothing can write the file any more.
    @Test
    fun test_registration_createsNoInstallationIdFile() {
        h.registered()
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertFalse(java.io.File(java.io.File(h.root, "sallapush"), "installation_id").exists())
    }

    // guard: the server's registration takes a device field as a string or not at all, never as null.
    @Test
    fun test_aNullDeviceField_isOmitted() {
        h.device.appVersion = null
        h.device.osVersion = null
        h.device.model = null
        h.device.language = null
        h.device.zone = null
        h.registered()
        val body = h.net.calls(Routes.REGISTER).single().body ?: throw AssertionError("no body")
        for (key in listOf("app_version", "os_version", "device_model", "language", "timezone")) assertFalse(key, body.has(key))
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
    }

    @Test
    fun test_secondLaunch_reusesCredentials_sendsNoRegister() {
        h.registered()
        val second = h.relaunch()
        second.advance(5.0)
        assertEquals(0, second.net.calls(Routes.REGISTER).size)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        second.close()
    }

    @Test
    fun test_twoTokenDeliveries_oneRegister() {
        h.healthyServer()
        h.start()
        h.deliverToken()
        h.deliverToken()
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertTrue(h.capture.has(LogCode.TOKEN_UNCHANGED))
    }

    @Test
    fun test_noToken_noRegister_andLogcatSaysWhatToForward() {
        h.firebase.answers = false
        h.fcmServiceName = "com.example.store.AppMessagingService"
        h.healthyServer()
        h.start()
        h.advance(60.0)
        assertTrue(h.net.calls.isEmpty())
        // What an integrator sees in Logcat at the default level, not only what a log listener receives.
        val line = h.console.single { it.startsWith("WARN token.missing:") }
        assertTrue(line, line.contains("FCM delivers tokens to com.example.store.AppMessagingService") && line.contains("onNewToken"))
    }

    @Test
    fun test_tokenMissing_advice_followsTheFirebaseAnswer_andTheServiceFcmUses() {
        assertTrue(tokenMissingAdvice { it.firebase.result = FirebaseToken.FirebaseMissing }.contains("Firebase is not initialized in this app"))
        assertTrue(tokenMissingAdvice { it.firebase.result = FirebaseToken.AutoInitDisabled }.contains("auto-init is disabled"))
        assertTrue(tokenMissingAdvice { it.firebase.result = FirebaseToken.Failed("IOException") }.contains("Reading the FCM token failed (IOException)"))
        val ownService = tokenMissingAdvice {
            it.firebase.answers = false
            it.fcmServiceName = SallaPushFirebaseMessagingService::class.java.name
        }
        // The SDK's own service already forwards: telling the app to forward would be wrong.
        assertFalse(ownService, ownService.contains("call SallaPush.Notifications.onNewToken"))
        assertTrue(ownService, ownService.contains("google-services.json"))
        val noService = tokenMissingAdvice { it.firebase.answers = false }
        assertTrue(noService, noService.contains("Forward FCM tokens with SallaPush.Notifications.onNewToken"))
    }

    /** The token.missing message of a launch that gets no token, with the harness prepared by [prepare]. */
    private fun tokenMissingAdvice(prepare: (Harness) -> Unit): String {
        val harness = Harness(h.context).install()
        prepare(harness)
        harness.healthyServer()
        harness.start()
        harness.advance(31.0)
        val message = harness.capture.of(LogCode.TOKEN_MISSING).single().message
        harness.close()
        return message
    }

    @Test
    fun test_invalidToken_isIgnoredWithWarning() {
        h.healthyServer()
        h.start()
        h.deliverToken("short")
        h.advance(5.0)
        assertTrue(h.capture.has(LogCode.TOKEN_INVALID))
        assertTrue(h.net.calls.isEmpty())
    }

    @Test
    fun test_reRegister_sendsNoPreviousDeviceId() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID) }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        val registers = h.net.calls(Routes.REGISTER)
        assertEquals(2, registers.size)
        val again = registers[1].body ?: throw AssertionError("no body")
        assertFalse(again.has("previous_device_id"))
        assertTrue(h.capture.has(LogCode.REGISTER_REREGISTERING))
    }

    @Test
    fun test_unknownApp_dormant24h_retriedAfter() {
        h.net.on("POST", Routes.REGISTER) { Http.error(404, "unknown_app") }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertTrue(h.capture.has(LogCode.REGISTER_DORMANT))
        assertEquals(
            "ERROR register.dormant: SallaPush is not available for app st_42_android (HTTP 404 unknown_app, request req-404). " +
                "Check the app id. Next try in 24 h.",
            h.console.single { it.startsWith("ERROR register.dormant:") },
        )
        h.advance(23.0 * 3600)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        h.net.on("POST", Routes.REGISTER) { Http.registered() }
        h.advance(2.0 * 3600)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertNotNull(SallaPush.User.pushSubscription.id)
    }

    @Test
    fun test_registerNotFound_saysSallaPushIsNotEnabledYet() {
        h.net.on("POST", Routes.REGISTER) { Http.error(404, "not_found", message = "Not found") }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertEquals(
            "SallaPush is not enabled for app st_42_android yet (HTTP 404 not_found, request req-404); it registers once it " +
                "is. Next try in 24 h.",
            h.capture.of(LogCode.REGISTER_DORMANT).single().message,
        )
        assertEquals(RegStatus.DORMANT, h.engine().device.registration.status)
        h.advance(3600.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
    }

    @Test
    fun test_registerInvalidRequest_isDormantWithClientError() {
        h.net.on("POST", Routes.REGISTER) { Http.error(400, "invalid_request", message = "push_token too long") }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertTrue(h.capture.has(LogCode.NET_CLIENT_ERROR))
        h.advance(3600.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
    }

    /** The server answers no status; an earlier one's "disabled" no longer stops this device's work. */
    @Test
    fun test_aStatusDisabledRegisterAnswer_isIgnored() {
        h.healthyServer()
        h.net.on("POST", Routes.REGISTER) {
            Http.json(201, """{"device_id":"${Http.DEVICE_ID}","device_secret":"${Http.SECRET}","status":"disabled"}""")
        }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertFalse(h.capture.has(LogCode.NET_INVALID_RESPONSE))
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(RegStatus.REGISTERED, h.engine().device.registration.status)
        SallaPush.User.addTag("plan", "gold")
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_d15ab1ed"))))
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertEquals(1, h.net.calls(Routes.EVENTS).size)
    }

    @Test
    fun test_aBeta1TokenDeadState_loadsRegistered_withOneFullUpdate() {
        h.registered()
        // beta.1 saved this status when its server called the token dead; the credentials are still valid.
        val next = h.relaunch(keepCalls = true) { relaunched ->
            editStateJson(relaunched, "\"status\":\"registered\"", "\"status\":\"token_dead\"")
        }
        next.advance(3.0)
        assertEquals(RegStatus.REGISTERED, next.engine().device.registration.status)
        assertEquals(0, next.net.calls(Routes.REGISTER).size)
        val update = next.net.calls(Routes.DEVICE).single().body?.keysList()?.toSet() ?: throw AssertionError("no body")
        assertEquals(setOf("opted_in", "app_version", "sdk_version", "os_version", "device_model", "language", "timezone"), update)
        SallaPush.User.addTag("plan", "gold")
        next.advance(11.0)
        assertEquals(1, next.net.calls(Routes.DEVICE).size)
        assertEquals(1, next.net.calls(Routes.TAGS).size)
        next.close()
    }

    @Test
    fun test_aBeta1AppDisabledDormancy_endsAtLoad() {
        h.net.on("POST", Routes.REGISTER) { Http.error(400, "invalid_request") }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertEquals(RegStatus.DORMANT, h.engine().device.registration.status)
        // beta.1 saved a 24 h wait for 409 app_disabled, which the server never answers.
        val next = h.relaunch(keepCalls = true) { relaunched ->
            editStateJson(relaunched, "\"dormant_reason\":\"invalid_request\"", "\"dormant_reason\":\"app_disabled\"")
        }
        next.advance(3.0)
        assertEquals(1, next.net.calls(Routes.REGISTER).size)
        assertEquals(RegStatus.REGISTERED, next.engine().device.registration.status)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        next.close()
    }

    private fun editStateJson(harness: Harness, from: String, to: String) {
        val file = java.io.File(harness.appDirectory(), "state.json")
        val text = file.readText()
        assertEquals(text, 1, text.split(from).size - 1)
        file.writeText(text.replace(from, to))
    }

    @Test
    fun test_backgroundLaunch_withoutIdentityOps_sendsNoRegister() {
        h.healthyServer()
        h.init()
        h.deliverToken()
        h.advance(10.0)
        // No activity started: this is a background launch (for example a process started by FCM).
        assertTrue(h.engine().backgroundLaunch)
        assertEquals(0, h.net.calls(Routes.REGISTER).size)

        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        assertEquals(0, h.net.calls(Routes.DEVICE).size)
    }

    private fun copyStorage(from: Harness, to: Harness) {
        java.io.File(from.root, "sallapush").copyRecursively(java.io.File(to.root, "sallapush"), overwrite = true)
    }

    @Test
    fun test_onlyAnotherSdksNamedFirebaseApp_isFirebaseMissing_andBothLinesNameGoogleServicesJson() {
        // OneSignal 5 creates its own FirebaseApp; without google-services.json there is no default app.
        val options = FirebaseOptions.Builder()
            .setApplicationId("1:123456789012:android:0123456789abcdef")
            .setApiKey("AIzaSyA0000000000000000000000000000000")
            .setProjectId("onesignal-shared-public")
            .setGcmSenderId("123456789012")
            .build()
        val named = FirebaseApp.initializeApp(h.context, options, "ONESIGNAL_SDK_FCM_APP_NAME")
        try {
            var result: FirebaseToken? = null
            FirebaseTokens(h.context, Executor { it.run() }).fetch { result = it }
            assertTrue("$result", result is FirebaseToken.FirebaseMissing)

            h.firebase.result = result ?: throw AssertionError("no answer")
            h.healthyServer()
            h.start()
            h.advance(31.0)
            assertTrue(h.capture.of(LogCode.TOKEN_FIREBASE_MISSING).single().message.contains("add google-services.json"))
            assertFalse(h.capture.has(LogCode.TOKEN_FETCH_FAILED))
            val advice = h.capture.of(LogCode.TOKEN_MISSING).single().message
            assertTrue(advice, advice.contains("google-services.json"))
            assertFalse(advice, advice.contains("IllegalStateException"))
        } finally {
            named.delete()
        }
    }
}
