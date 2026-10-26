package com.salla.push

import com.salla.push.internal.api.HttpUrlConnectionTransport
import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.Desired
import com.salla.push.internal.engine.IdentityOpKind
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import com.salla.push.testing.keysList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** login(externalId, identityToken) links the device, logout() unlinks it, and what the answers do. */
class IdentityTest : SdkTestBase() {

    private val failures = ArrayList<SallaPushLoginFailure>()
    private val observer = object : SallaPushUserObserver {
        override fun onUserStateChange(state: SallaPushUserChangedState) = Unit
        override fun onLoginFailure(failure: SallaPushLoginFailure) {
            failures.add(failure)
        }
    }

    // ── The link and the unlink ──

    @Test
    fun test_login_sendsTheLink_withTheExternalIdAndTheIdentityToken() {
        h.registered()
        SallaPush.login(" ${Harness.CUSTOMER_ID} ", " ${Harness.CUSTOMER_TOKEN}\n")
        h.advance(3.0)
        val link = h.net.calls(Routes.LINK).single()
        assertEquals("POST", link.method)
        assertEquals("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}/link", link.url)
        assertEquals(Http.SECRET, link.authorization)
        assertEquals(HttpUrlConnectionTransport.LOGIN_READ_TIMEOUT_MILLIS, link.request.readTimeoutMillis)
        val body = link.body ?: throw AssertionError()
        assertEquals(setOf("external_id", "identity_token"), body.keysList().toSet())
        assertEquals(Harness.CUSTOMER_ID, body.getString("external_id"))
        assertEquals(Harness.CUSTOMER_TOKEN, body.getString("identity_token"))
        assertEquals("Customer linked.", h.capture.of(LogCode.LOGIN_OK).single().message)
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)
        assertEquals(Harness.CUSTOMER_ID, h.engine().identity.linkedExternalId)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
    }

    @Test
    fun test_loginBeforeToken_linksAfterRegistration() {
        h.healthyServer()
        h.start()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(5.0)
        assertTrue(h.net.calls.isEmpty())
        assertTrue(SallaPush.User.isLoggedIn)
        assertEquals("Login queued (waiting for registration).", h.capture.of(LogCode.LOGIN_QUEUED).single().message)

        h.deliverToken()
        h.advance(3.0)
        assertEquals(listOf(Routes.REGISTER, Routes.LINK), h.net.calls.map { it.route })
        assertEquals(Harness.CUSTOMER_ID, SallaPush.User.externalId)
        assertTrue(h.capture.has(LogCode.LOGIN_OK))
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)
    }

    /** beta.1's OneSignal hand-off keys in identity.json are ignored, and no link carries more than its two fields. */
    @Test
    fun test_aLoginRequest_neverCarriesOnesignalId() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val file = File(h.appDirectory(), "identity.json")
        val beta1 = JSONObject(file.readText())
            .put("legacy_onesignal_id", "7d3f1c2a-0000-4000-8000-000000000001")
            .put("legacy_sent_for_fp", JSONObject.NULL)
        file.writeText(beta1.toString())
        val next = h.relaunch(keepCalls = true) { SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2) }
        next.advance(3.0)
        val link = next.net.calls(Routes.LINK).single().body ?: throw AssertionError()
        assertEquals(setOf("external_id", "identity_token"), link.keysList().toSet())
        assertFalse(next.identityJson(), next.identityJson().contains("legacy"))
        next.close()
    }

    @Test
    fun test_logout_sendsTheUnlink_withAnEmptyBody() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        SallaPush.logout()
        h.advance(3.0)
        val unlink = h.net.calls(Routes.UNLINK).single()
        assertEquals("POST", unlink.method)
        assertEquals("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}/unlink", unlink.url)
        assertEquals("{}", unlink.body.toString())
        assertEquals(Http.SECRET, unlink.authorization)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertFalse(SallaPush.User.isLoggedIn)
        assertNull(SallaPush.User.externalId)
        assertEquals("Customer unlinked.", h.capture.of(LogCode.LOGOUT_OK).single().message)
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
        assertNull(h.serverExternalId)
    }

    @Test
    fun test_logoutWhenNeverLinked_sendsNothing() {
        h.registered()
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(0, h.net.calls(Routes.UNLINK).size)
        assertEquals("Not linked; logout needs no request.", h.capture.of(LogCode.LOGOUT_SKIPPED).single().message)
    }

    @Test
    fun test_logoutAtFirstLaunch_settledByRegistration_noRequest() {
        h.healthyServer()
        h.start()
        SallaPush.logout()
        h.deliverToken()
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(0, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_logoutBehindARequestOnTheWire_isReportedAsQueued() {
        h.registered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        assertEquals(1, h.net.held().size)
        SallaPush.logout()
        h.settle()
        assertEquals(listOf("Logout queued behind the request in flight."), h.capture.of(LogCode.LOGOUT_QUEUED).map { it.message })
    }

    @Test
    fun test_aLogoutWhileTheDeviceRoutesAreClosed_saysItIsSentWhenTheyReopen() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "not_found") }
        SallaPush.User.setLanguage("en")
        h.advance(2.0)
        assertTrue(h.capture.has(LogCode.NET_ROUTES_CLOSED))
        SallaPush.logout()
        h.settle()
        assertEquals(
            "Logout queued while the SallaPush device routes are closed; it is sent when they reopen.",
            h.capture.of(LogCode.LOGOUT_QUEUED).single().message,
        )
        h.advance(60.0)
        assertTrue(h.net.calls(Routes.UNLINK).isEmpty())
    }

    @Test
    fun test_repeatedLogout_whileALogoutIsPending_keepsIt() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.timeout() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        SallaPush.logout()
        // The pump drops the login whose outcome is unknown; the logout waits for the debounce.
        h.settle()
        val pending = h.engine().identity.ops.single()
        assertEquals(IdentityOpKind.LOGOUT, pending.kind)
        SallaPush.logout()
        h.settle()
        // The first login may still be applied (its outcome is unknown), so this is no free guest logout: the pending
        // logout already covers the call, keeps its place, and no request is added.
        assertEquals(listOf(pending.id), h.engine().identity.ops.map { it.id })
        assertEquals(0, h.capture.count(LogCode.LOGOUT_SKIPPED))
        assertEquals("Logout already queued; this call adds no request.", h.capture.of(LogCode.LOGOUT_QUEUED).last().message)
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        // The login may still reach the server late: the logout is confirmed once, 60 s later.
        h.advance(60.0)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_logoutMissedWhileUninitialised_nextLaunchLogsOut() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        // The app signed out while SallaPush was not initialised; at the next launch it asserts the signed-out state.
        val next = h.relaunch()
        SallaPush.logout()
        next.advance(3.0)
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        next.close()
    }

    /** A literal beta.1 identity.json: its unlink of a link the app did not make is sent as an unlink. */
    @Test
    fun test_aBeta1IdentityFile_keepsItsPendingLogout() {
        h.registered()
        File(h.appDirectory(), "identity.json").writeText(
            """{"v":1,"app_id":"${Harness.APP_ID}","api_base_url":"${Routes.DEFAULT_BASE_URL}","desired":"guest",""" +
                """"desired_fp":null,"server_link":"linked","server_fp":"5f2cd8ebddced675","external_id":"1001",""" +
                """"login_unsupported":false,"rejected_fps":[{"fp":"0123456789abcdef","until":1790086400}],""" +
                """"revoked_fps":["fedcba9876543210"],"server_tags":"some","relogin_at":{"5f2cd8ebddced675":1789990000},""" +
                """"ops":[{"id":7,"kind":"unlink","fp":null,"clear_tags":true,"sent":false,"attempts":0,"next_at":0,""" +
                """"last_attempt_at":0,"last_failure":null,"dormant_until":null}],"unlinked_customer":null}""",
        )
        val next = h.relaunch(keepCalls = true)
        next.advance(5.0)
        assertEquals("{}", next.net.calls(Routes.UNLINK).single().body.toString())
        assertTrue(next.engine().identity.ops.isEmpty())
        assertEquals(ServerLink.NONE, next.engine().identity.serverLink)
        assertTrue(next.capture.has(LogCode.LOGOUT_OK))
        next.close()
    }

    // ── The answers to a link ──

    @Test
    fun test_aRejectedIdentityToken_isReported_andNotSentAgain() {
        h.registered()
        SallaPush.User.addObserver(observer)
        h.net.on("POST", Routes.LINK) { Http.error(403, "invalid_identity_token") }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(listOf(SallaPushLoginFailure.TOKEN_REJECTED), failures)
        assertEquals(
            "The identity token was rejected (403 invalid_identity_token); this device is not linked to the customer. " +
                "Call login(externalId, identityToken) with a fresh token.",
            h.capture.of(LogCode.LOGIN_REJECTED).single().message,
        )
        // The refused token leaves memory; the app still asserts the customer.
        assertNull(h.engine().identityToken)
        assertTrue(SallaPush.User.isLoggedIn)
        assertTrue(h.engine().identity.ops.isEmpty())
        h.advance(25 * 3600.0)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        assertEquals(1, failures.size)
    }

    @Test
    fun test_rejectedLogin_reachesLogcat_withItsRequestId() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.error(403, "invalid_identity_token") }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val line = h.console.single { it.startsWith("WARN login.rejected:") }
        assertTrue(line, line.endsWith("[request req-403]"))
    }

    @Test
    fun test_anInvalidRequest_isReported_andNotSentAgain() {
        h.registered()
        SallaPush.User.addObserver(observer)
        h.net.on("POST", Routes.LINK) { Http.error(400, "invalid_request") }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(listOf(SallaPushLoginFailure.INVALID_REQUEST), failures)
        assertEquals(
            "POST /devices/{id}/link was rejected (400 invalid_request: \"Rejected\", request req-400); the login is " +
                "not sent again.",
            h.capture.of(LogCode.IDENTITY_CLIENT_ERROR).single().message,
        )
        assertTrue(h.engine().identity.ops.isEmpty())
        h.advance(25 * 3600.0)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        assertEquals(1, failures.size)
    }

    // guard: a 4xx without the server's error envelope (a proxy's or an edge's answer) is retried like a 5xx.
    @Test
    fun test_a403WithoutACode_isRetried_andReportsNothing() {
        h.registered()
        SallaPush.User.addObserver(observer)
        var links = 0
        h.net.on("POST", Routes.LINK) {
            links += 1
            if (links == 1) Http.json(403, """{"message":"Forbidden"}""") else Http.empty()
        }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        assertEquals(1, links)
        assertTrue(h.capture.has(LogCode.NET_RETRY))
        h.advance(30.0)
        assertEquals(2, links)
        assertTrue(h.capture.has(LogCode.LOGIN_OK))
        assertTrue(failures.isEmpty())
        assertFalse(h.capture.has(LogCode.LOGIN_REJECTED))
    }

    /**
     * The app passed a newer token while the link was on the wire: the 403 of the older one sends the link again with
     * the newer token, and reports nothing.
     */
    @Test
    fun test_aRejectedTokenReplacedMeanwhile_isSentAgainWithTheNewerToken() {
        h.registered()
        SallaPush.User.addObserver(observer)
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val refused = h.net.held().single()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN_2)
        h.settle()
        assertEquals(
            "Login already queued; this call adds no request.",
            h.capture.of(LogCode.LOGIN_QUEUED).single().message,
        )
        h.healthyServer()
        refused.respond(Http.error(403, "invalid_identity_token"))
        h.settle()
        assertEquals(
            listOf(Harness.CUSTOMER_TOKEN, Harness.CUSTOMER_TOKEN_2),
            h.net.calls(Routes.LINK).map { it.body?.getString("identity_token") },
        )
        assertEquals(
            "The identity token was rejected, and a newer one came since; the link is sent again with it.",
            h.capture.of(LogCode.LOGIN_QUEUED).last().message,
        )
        assertTrue(failures.isEmpty())
        assertFalse(h.capture.has(LogCode.LOGIN_REJECTED))
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)
        assertEquals(Harness.CUSTOMER_ID, h.engine().identity.linkedExternalId)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
    }

    // ── The arguments and the getters ──

    @Test
    fun test_invalidArguments_areIgnoredOnTheCallerThread() {
        h.registered()
        SallaPush.User.addObserver(observer)
        h.capture.clear()
        SallaPush.login("", Harness.CUSTOMER_TOKEN)
        SallaPush.login(" \n ", Harness.CUSTOMER_TOKEN)
        SallaPush.login("x".repeat(129), Harness.CUSTOMER_TOKEN)
        // Logged before any SallaPush thread runs: on the caller's thread.
        assertEquals(
            List(3) { "login ignored: the external id must be 1-128 characters." },
            h.capture.of(LogCode.API_INVALID_ARGUMENT).map { it.message },
        )
        h.capture.clear()
        SallaPush.login(Harness.CUSTOMER_ID, "")
        SallaPush.login(Harness.CUSTOMER_ID, "a".repeat(4097))
        assertEquals(
            List(2) { "login ignored: the identity token must be 1-4096 characters." },
            h.capture.of(LogCode.API_INVALID_ARGUMENT).map { it.message },
        )
        assertFalse(SallaPush.User.isLoggedIn)
        h.advance(3.0)
        assertTrue(h.net.calls(Routes.LINK).isEmpty())
        assertFalse(SallaPush.User.isLoggedIn)
        assertTrue(failures.isEmpty())
        // The limits themselves are accepted: 128 UTF-16 units of id, 4,096 code points of token.
        SallaPush.login("x".repeat(128), "😀".repeat(4096))
        h.advance(3.0)
        assertEquals("x".repeat(128), h.net.calls(Routes.LINK).single().body?.getString("external_id"))
    }

    @Test
    fun test_externalId_isTheCallsId_fromTheMomentItReturns() {
        h.registered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        assertEquals(Harness.CUSTOMER_ID, SallaPush.User.externalId)
        assertTrue(SallaPush.User.isLoggedIn)
        // The link is still on the wire: the getter shows the call's id, not a server confirmation.
        h.advance(3.0)
        assertEquals(1, h.net.held().size)
        assertEquals(Harness.CUSTOMER_ID, SallaPush.User.externalId)
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        assertEquals(Harness.CUSTOMER_ID_2, SallaPush.User.externalId)
        SallaPush.logout()
        assertNull(SallaPush.User.externalId)
        assertFalse(SallaPush.User.isLoggedIn)
        h.settle()
        assertNull(SallaPush.User.externalId)
    }

    // guard: the next launch's login of the linked customer, with a refreshed token, sends nothing.
    @Test
    fun test_theNextLaunchsLogin_ofTheLinkedCustomer_sendsNothing() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.LINK).size)

        val next = h.relaunch()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN_2)
        next.advance(10.0)
        assertEquals(0, next.net.calls(Routes.LINK).size)
        assertEquals("Already linked to this customer; nothing to send.", next.capture.of(LogCode.LOGIN_SKIPPED_SAME).single().message)
        assertEquals(Harness.CUSTOMER_ID, SallaPush.User.externalId)
        next.close()
    }

    // guard: the identity token is kept in memory only, and no log line names the token or the customer.
    @Test
    fun test_theIdentityToken_isNeverStoredOrLogged() {
        h.registered()
        SallaPush.Debug.logLevel = SallaPushLogLevel.VERBOSE
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.LINK) { Http.error(403, "invalid_identity_token") }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(3.0)
        SallaPush.logout()
        h.advance(3.0)
        val logs = h.capture.allText() + h.console.joinToString()
        val files = h.root.walkTopDown().filter { it.isFile }.joinToString { it.readBytes().decodeToString() }
        for (token in listOf(Harness.CUSTOMER_TOKEN, Harness.CUSTOMER_TOKEN_2)) {
            assertFalse(logs.contains(token))
            assertFalse(files.contains(token))
        }
        assertFalse(logs.contains(Harness.CUSTOMER_ID))
        assertFalse(logs.contains(Harness.CUSTOMER_ID_2))
    }

    // guard: the server's update answers carry no link, and none is read: a stray "linked" changes nothing.
    @Test
    fun test_noLinkIsReconciled_fromUpdateAnswers() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.json(200, """{"linked":false}""") }
        SallaPush.User.setLanguage("en")
        h.advance(30.0)
        assertTrue(h.capture.has(LogCode.UPDATE_SENT))
        assertEquals(1, h.net.calls(Routes.LINK).size)
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)

        SallaPush.logout()
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { Http.json(200, """{"linked":true}""") }
        SallaPush.User.setLanguage("fr")
        h.advance(30.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
    }

    // ── A login without its identity token in memory waits for the app's login (Android's rule) ──

    @Test
    fun test_aLinkPendingFromThePreviousLaunch_goesWithTheLaunchLoginsToken() {
        h.registered()
        // The process ends while the link is on the wire.
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        assertEquals(1, h.net.held().size)

        // The next launch: initialize (the engine pumps once before the app's login reaches it), the app's launch
        // login, then the foreground.
        val next = launchWithoutForeground()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        next.settle()
        next.foreground()
        next.advance(5.0)
        assertEquals(Harness.CUSTOMER_TOKEN, next.net.calls(Routes.LINK).single().body?.getString("identity_token"))
        assertTrue(next.capture.has(LogCode.LOGIN_OK))
        assertFalse(next.capture.has(LogCode.IDENTITY_TOKEN_NEEDED))
        next.close()
    }

    @Test
    fun test_aSignedInLaunchWithoutLogin_warnsTokenNeededOnceInTheForeground() {
        h.registered()
        // The process ends while the link is on the wire.
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(2.0)

        // The app makes no login at this launch: the link waits, silently until the app is in the foreground.
        val next = launchWithoutForeground()
        next.advance(30.0)
        assertFalse(next.capture.has(LogCode.IDENTITY_TOKEN_NEEDED))
        next.foreground()
        next.advance(30.0)
        assertEquals(
            "SallaPush keeps the identity token in memory only and has none for the signed-in customer, so this device is " +
                "not linked to them; call login(externalId, identityToken) at every launch while the customer is signed in.",
            next.capture.of(LogCode.IDENTITY_TOKEN_NEEDED).single().message,
        )
        next.background()
        next.foreground()
        next.advance(30.0)
        assertEquals(1, next.capture.count(LogCode.IDENTITY_TOKEN_NEEDED))
        // The login is kept, and the tags wait behind it.
        assertTrue(next.net.calls(Routes.LINK).isEmpty())
        assertTrue(next.net.calls(Routes.TAGS).isEmpty())
        assertEquals(IdentityOpKind.LOGIN, next.engine().identity.ops.single().kind)
        assertEquals(Desired.CUSTOMER, next.engine().identity.desired)
        next.close()
    }

    @Test
    fun test_tokenNeeded_isOncePerLaunch_evenAcrossARestartedEngine() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.networkFailure() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        val next = h.relaunch()
        next.advance(3.0)
        assertEquals(1, next.capture.count(LogCode.IDENTITY_TOKEN_NEEDED))
        // Consent becomes required, then is given: a new engine of the same app id runs in this launch.
        SallaPush.setConsentRequired(true)
        next.settle()
        SallaPush.setConsentGiven(true)
        next.advance(3.0)
        next.background()
        next.foreground()
        next.advance(3.0)
        assertEquals(1, next.capture.count(LogCode.IDENTITY_TOKEN_NEEDED))
        next.close()
    }

    // ── Account switches ──

    @Test
    fun test_switchCustomer_previousCustomersTagsDropped() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("last_order", "a")
        h.advance(12.0)
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        SallaPush.User.addTag("plan", "gold")
        h.advance(3.0)
        h.advance(15.0)
        assertEquals(
            "Account switched; the previous customer's tags are cleared before the new customer is linked.",
            h.capture.of(LogCode.LOGIN_SWITCHED).single().message,
        )
        assertEquals(Harness.CUSTOMER_ID_2, h.net.calls(Routes.LINK).last().body?.getString("external_id"))
        val last = h.net.calls(Routes.TAGS).last().body ?: throw AssertionError()
        assertFalse(last.optJSONObject("add")?.has("last_order") ?: false)
        assertEquals("gold", last.getJSONObject("add").getString("plan"))
        assertEquals(Harness.CUSTOMER_ID_2, SallaPush.User.externalId)
    }

    @Test
    fun test_sameCustomersFreshToken_isNoSwitch_andKeepsTheTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("last_order", "a")
        h.advance(12.0)
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN_2)
        h.advance(3.0)
        h.advance(15.0)
        assertFalse(h.capture.has(LogCode.LOGIN_SWITCHED))
        assertEquals(1, h.net.calls(Routes.LINK).size)
        val last = h.net.calls(Routes.TAGS).last().body ?: throw AssertionError()
        assertEquals("a", last.getJSONObject("add").getString("last_order"))
    }

    // ── The launch logout() of an unknown identity keeps the tag changes made earlier in that launch ──

    @Test
    fun test_guestWithTags_steadyStateLaunchLogout_sendsNothing_keepsTags() {
        h.registered()
        SallaPush.logout()
        SallaPush.User.addTag("add_to_cart", "1790000000000")
        h.advance(15.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)

        // A signed-out app's cold start: initialize, then the launch logout(). It is free.
        val events = CopyOnWriteArrayList<SallaPushLogEvent>()
        val next = h.relaunch(keepCalls = true) {
            SallaPush.Debug.addLogListener(SallaPushLogListener { events.add(it) }, SallaPushLogLevel.DEBUG)
            SallaPush.logout()
        }
        next.advance(30.0)
        assertEquals(emptyList<String>(), next.net.calls.map { "${it.method} ${it.route}" })
        assertEquals(mapOf("add_to_cart" to "1790000000000"), SallaPush.User.getTags())
        assertEquals(mapOf("add_to_cart" to "1790000000000"), next.engine().device.tags.mirror)
        assertEquals(Desired.GUEST, next.engine().identity.desired)
        assertTrue(next.engine().identity.ops.isEmpty())
        assertFalse(SallaPush.User.isLoggedIn)
        assertEquals(1, next.capture.count(LogCode.LOGOUT_SKIPPED))
        waitUntil { events.any { it.code == "logout.skipped" } }
        val skipped = events.first { it.code == "logout.skipped" }
        assertEquals(SallaPushLogLevel.DEBUG, skipped.level)
        assertEquals("identity", skipped.category)
        next.close()
    }

    @Test
    fun test_signedIn_launchLogout_stillLogsOutAndClearsTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("add_to_cart", "1790000000000")
        h.advance(15.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)

        // The app signed out while SallaPush was not running: the next launch's logout() is a real sign-out.
        val next = h.relaunch(keepCalls = true) { SallaPush.logout() }
        next.advance(5.0)
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertEquals(ServerLink.NONE, next.engine().identity.serverLink)
        assertFalse(next.capture.has(LogCode.LOGOUT_SKIPPED))
        next.close()
    }

    @Test
    fun test_freshInstall_aTagSetBeforeTheLaunchLogout_isKept_andSent() {
        h.healthyServer()
        h.start()
        SallaPush.User.addTag("lang", "ar")
        SallaPush.logout()
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        h.settle()
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        assertEquals(Desired.GUEST, h.engine().identity.desired)
        h.deliverToken()
        h.advance(15.0)
        // A fresh row holds no link or tags: the queued logout is settled, and the tag goes.
        assertEquals(listOf(Routes.REGISTER, Routes.TAGS), h.net.calls.map { it.route })
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
    }

    @Test
    fun test_lostIdentityFile_theLaunchLogoutKeepsOnlyThisLaunchsTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        assertEquals(mapOf("tier" to "gold"), h.serverTags)
        // A change of that launch that never reached the server.
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("vip", "1")
        h.advance(15.0)
        File(h.appDirectory(), "identity.json").writeText("[]")
        val next = h.relaunch(keepCalls = true) {
            SallaPush.User.addTag("lang", "ar")
            SallaPush.logout()
        }
        next.advance(30.0)
        assertEquals(Desired.GUEST, next.engine().identity.desired)
        // The link is unknown: the logout unlinks.
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        // The earlier launch's tags are cleared from the server, never sent again; this launch's tag was kept and sent.
        val tags = next.net.calls(Routes.TAGS).map { it.body.toString() }
        assertEquals(listOf("""{"remove":["tier","vip"]}""", """{"add":{"lang":"ar"}}"""), tags)
        assertEquals(mapOf("lang" to "ar"), next.serverTags)
        assertEquals("ar", SallaPush.User.getTags()["lang"])
        next.close()
    }

    @Test
    fun test_unknownIdentity_aLateLaunchLogout_keepsTheTagsThisLaunchAlreadySent() {
        h.registered()
        SallaPush.User.addTag("lang", "ar")
        h.advance(15.0)
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        h.net.clear()
        // The launch logout comes after the tag reached the server: the tag is kept, and sent again.
        SallaPush.logout()
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        h.advance(15.0)
        assertEquals(listOf(Routes.TAGS), h.net.calls.map { it.route })
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
    }

    @Test
    fun test_unknownIdentityAcrossLaunches_aStaticTagTheServerHolds_isKeptByTheLaunchLogout() {
        // An app that made no identity call yet: its identity stays unknown across launches.
        h.registered()
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        val next = h.relaunch(keepCalls = true) {
            // The same static tag, which the server already holds, then the launch logout.
            SallaPush.User.addTag("tier", "gold")
            SallaPush.logout()
        }
        next.advance(15.0)
        assertEquals(listOf(Routes.TAGS), next.net.calls.map { it.route })
        assertEquals(mapOf("tier" to "gold"), next.serverTags)
        assertEquals(mapOf("tier" to "gold"), SallaPush.User.getTags())
        next.close()
    }

    @Test
    fun test_unknownIdentity_aTagTheServerRejected_isNotKeptByTheLaunchLogout() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { call -> if (call.body.toString().contains("bad")) Http.error(400, "invalid_request") else Http.tags(h.tagsAfter(call)) }
        SallaPush.User.addTags(mapOf("bad" to "x", "lang" to "ar"))
        h.advance(30.0)
        assertTrue(h.capture.has(LogCode.TAGS_REJECTED_KEY))
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        h.net.clear()
        // Only the tags of this launch that the getters still show are kept.
        SallaPush.logout()
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        h.advance(30.0)
        assertTrue(h.net.calls(Routes.TAGS).none { it.body.toString().contains("bad") })
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
    }

    @Test
    fun test_unknownIdentity_aLaunchLogoutWhileTheTagsRequestIsOnTheWire_stillEndsWithTheTag() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("lang", "ar")
        h.advance(12.0)
        val held = h.net.held().single()
        SallaPush.logout()
        h.settle()
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        // The request on the wire lands; the kept tag goes out again after it.
        held.respond(Http.tags(h.tagsAfter(held)))
        h.advance(15.0)
        assertTrue(h.net.calls(Routes.UNLINK).isEmpty())
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
    }

    @Test
    fun test_aKnownIdentitysLogout_stillClearsTheTagsSetBeforeIt() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        SallaPush.User.addTag("lang", "ar")
        SallaPush.logout()
        assertTrue(SallaPush.User.getTags().isEmpty())
        h.advance(15.0)
        assertTrue(h.net.calls(Routes.TAGS).isEmpty())
        assertTrue(h.serverTags.isEmpty())
    }

    /**
     * The next launch on the same phone, initialised but not in the foreground yet (Application.onCreate before the
     * first activity): same storage, server and clock.
     */
    private fun launchWithoutForeground(): Harness {
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.serverExternalId = h.serverExternalId
        next.serverTags.putAll(h.serverTags)
        next.install()
        next.healthyServer()
        next.init()
        return next
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
