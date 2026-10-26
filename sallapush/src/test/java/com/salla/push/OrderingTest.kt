package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.IdentityOpKind
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.FakeCall
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderingTest : SdkTestBase() {

    @Test
    fun test_logoutDuringLoginInFlight_sentAfter() {
        h.registered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        assertEquals(1, h.net.held().size)
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(0, h.net.calls(Routes.UNLINK).size)
        h.net.held().first().respond(Http.empty())
        h.advance(3.0)
        val order = h.net.calls.map { it.route }.filter { it == Routes.LINK || it == Routes.UNLINK }
        assertEquals(listOf(Routes.LINK, Routes.UNLINK), order)
        assertTrue(!SallaPush.User.isLoggedIn)
    }

    @Test
    fun test_twoLogouts_oneRequest() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        SallaPush.logout()
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
    }

    @Test
    fun test_loginLogoutLogin_unsent_collapsesToLastLogin() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.logout()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(5.0)
        val logins = h.net.calls(Routes.LINK)
        assertEquals(1, logins.size)
        assertEquals(Harness.CUSTOMER_TOKEN_2, logins[0].body?.getString("identity_token"))
        assertEquals(0, h.net.calls(Routes.UNLINK).size)
    }

    @Test
    fun test_logoutThenLogin_unsentLogoutStaysFirst() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        SallaPush.logout()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(5.0)
        val order = h.net.calls.map { it.route }.filter { it == Routes.LINK || it == Routes.UNLINK }
        assertEquals(listOf(Routes.LINK, Routes.UNLINK, Routes.LINK), order)
    }

    @Test
    fun test_unknownOutcomeLogin_logoutConfirmAfter60s() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.timeout() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        SallaPush.logout()
        h.advance(5.0)
        val logouts = h.net.calls(Routes.UNLINK)
        assertEquals(1, logouts.size)
        h.advance(53.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        h.advance(5.0)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.capture.count(LogCode.LOGOUT_OK) == 2)
    }

    @Test
    fun test_unknownOutcomeLogin_replacedByALaterLogin_logoutIsStillConfirmed() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.timeout() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        // Another account replaces the first login before it is retried, then the app signs out. The first login may
        // still reach the server, so the logout unlinks and is confirmed 60 s later (an unknown outcome).
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        val logouts = h.net.calls(Routes.UNLINK)
        assertEquals(1, logouts.size)
        assertEquals("{}", logouts[0].body.toString())
        h.advance(60.0)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_loginAfterFailedLogout_retriesLogoutImmediately() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(200.0)
        val failedAttempts = h.net.calls(Routes.UNLINK).size
        assertTrue(failedAttempts >= 4)
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(3.0)
        assertEquals(failedAttempts + 1, h.net.calls(Routes.UNLINK).size)
        val order = h.net.calls.map { it.route }.filter { it == Routes.LINK || it == Routes.UNLINK }
        assertEquals(Routes.LINK, order.last())
    }

    @Test
    fun test_tagsHeldWhileIdentityOpPending() {
        h.registered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(30.0)
        assertEquals(0, h.net.calls(Routes.TAGS).size)
        h.net.held().first().respond(Http.empty())
        h.advance(15.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_retriesExhaustedAfterSixAttempts_waitForTrigger() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.error(503, "server_error") }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(4.0 * 3600)
        assertEquals(6, h.net.calls(Routes.LINK).size)
        assertTrue(h.capture.has(LogCode.NET_RETRY_EXHAUSTED))
        assertTrue(h.capture.has(LogCode.NET_RETRY))
        h.net.on("POST", Routes.LINK) { Http.empty() }
        h.background()
        h.foreground()
        h.advance(5.0)
        assertEquals(7, h.net.calls(Routes.LINK).size)
    }

    // ── The logout confirmation never signs out a later login ──

    private fun identityRoutes(): List<String> = h.net.calls.map { it.route }.filter { it == Routes.LINK || it == Routes.UNLINK }

    @Test
    fun test_loginOnTheWire_thenLogoutAndLoginB_failsInTransit_theConfirmationGoesBeforeB() {
        h.registered()
        var heldA: FakeCall? = null
        h.net.on("POST", Routes.LINK) { call ->
            if (heldA == null) {
                heldA = call
                null
            } else {
                h.serverExternalId = Harness.CUSTOMER_ID_2
                Http.empty()
            }
        }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        SallaPush.logout()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(0.5)
        // Login A fails in transit: the server may still apply it late, so the logout is confirmed a minute later.
        heldA?.respond(Http.networkFailure())
        h.advance(120.0)
        // The confirmation goes where the logout was, before B's login: B's login is the server's last word.
        assertEquals(listOf(Routes.LINK, Routes.UNLINK, Routes.UNLINK, Routes.LINK), identityRoutes())
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
        assertTrue(SallaPush.User.isLoggedIn)
        assertEquals("2002", SallaPush.User.externalId)
        assertTrue(h.engine().identity.ops.isEmpty())
        h.advance(600.0)
        assertEquals(4, identityRoutes().size)
    }

    @Test
    fun test_aLoginRetriedAfterATransitFailure_thenAnOfflineLogoutAndLoginB_endsLinkedToB_withoutAConfirmation() {
        h.registered()
        var first = true
        h.net.on("POST", Routes.LINK) {
            if (first) {
                first = false
                Http.networkFailure()
            } else {
                h.serverExternalId = Harness.CUSTOMER_ID
                Http.empty()
            }
        }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(30.0)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        // Ten minutes later the user signs out while offline, signs in as B, and the network comes back.
        h.advance(600.0)
        h.net.clear()
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(3.0)
        h.net.on("POST", Routes.LINK) {
            h.serverExternalId = Harness.CUSTOMER_ID_2
            Http.empty()
        }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(1.5)
        h.net.on("POST", Routes.UNLINK) {
            h.serverExternalId = null
            Http.empty()
        }
        h.connectivity.networkAvailable()
        h.advance(300.0)
        val routes = identityRoutes()
        assertEquals(Routes.LINK, routes.last())
        assertEquals(1, routes.count { it == Routes.LINK })
        // A's retry was answered, so nothing of it can arrive late: one logout went through, and no confirmation.
        assertEquals(1, h.capture.count(LogCode.LOGOUT_OK))
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
        assertEquals("2002", SallaPush.User.externalId)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_unknownOutcomeLogin_answeredWithA503OnItsRetry_thenReplaced_logoutIsStillConfirmed() {
        h.registered()
        var attempts = 0
        h.net.on("POST", Routes.LINK) {
            attempts += 1
            if (attempts == 1) Http.timeout() else Http.error(503, "server_error")
        }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        h.connectivity.networkAvailable()
        h.advance(3.0)
        assertTrue(attempts >= 2)
        val loginCalls = h.net.calls(Routes.LINK).size
        // The 503 answered only the retry: the first request may still reach the server. Another account replaces the
        // login and the app signs out, so the logout unlinks and is confirmed 60 s later.
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(loginCalls, h.net.calls(Routes.LINK).size)
        val logouts = h.net.calls(Routes.UNLINK)
        assertEquals(1, logouts.size)
        assertEquals("{}", logouts[0].body.toString())
        h.advance(60.0)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_aSecondLogout_whileALoginThatMayStillApplyIsLoggedOut_isNotFree_andDropsTheTagSetMeanwhile() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.timeout() }
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        SallaPush.logout()
        h.advance(3.0)
        assertEquals(listOf(IdentityOpKind.LOGOUT), h.engine().identity.ops.map { it.kind })
        // The login may still reach the server and the logout that unlinks it is not through: a tag set now, then
        // logout() again, is not a guest's free logout, so the tag goes like every tag at a logout.
        SallaPush.User.addTag("k", "v")
        SallaPush.logout()
        assertTrue(SallaPush.User.getTags().isEmpty())
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        h.connectivity.networkAvailable()
        h.advance(120.0)
        assertTrue(h.engine().identity.ops.isEmpty())
        assertTrue(h.net.calls(Routes.TAGS).isEmpty())
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    // ── Repeated logouts → one unlink ──

    /** A linked customer signs out; [first] answers the first logout request(s), later ones succeed. */
    private fun twoLogouts(first: (FakeCall) -> com.salla.push.internal.api.HttpResult?): Int {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.clear()
        var failing = true
        h.net.on("POST", Routes.UNLINK) { call -> if (failing) first(call) else Http.empty() }
        SallaPush.logout()
        h.advance(1.5)
        val attempted = h.net.calls(Routes.UNLINK).size
        SallaPush.logout()
        h.advance(0.5)
        failing = false
        h.connectivity.networkAvailable()
        h.advance(3600.0)
        assertTrue(h.engine().identity.ops.isEmpty())
        assertEquals("Logout already queued; this call adds no request.", h.capture.of(LogCode.LOGOUT_QUEUED).last().message)
        return h.net.calls(Routes.UNLINK).size - attempted
    }

    @Test
    fun test_secondLogout_afterATransportFailure_oneLogoutAfterRecovery() {
        assertEquals(1, twoLogouts { Http.networkFailure() })
    }

    @Test
    fun test_secondLogout_afterA503_oneLogoutAfterRecovery() {
        assertEquals(1, twoLogouts { Http.error(503, "server_error") })
    }

    @Test
    fun test_secondLogout_whileTheFirstIsOnTheWire_addsNoRequest() {
        var held: FakeCall? = null
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.clear()
        h.net.on("POST", Routes.UNLINK) { call ->
            held = call
            null
        }
        SallaPush.logout()
        h.advance(2.0)
        SallaPush.logout()
        h.advance(2.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        held?.respond(Http.empty())
        h.advance(60.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
        assertEquals("Logout already queued; this call adds no request.", h.capture.of(LogCode.LOGOUT_QUEUED).last().message)
    }

    @Test
    fun test_secondLogout_keepsThe429WaitOfTheFirst() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.clear()
        var limited = true
        h.net.on("POST", Routes.UNLINK) { if (limited) Http.error(429, "rate_limited", retryAfter = "120") else Http.empty() }
        SallaPush.logout()
        h.advance(1.5)
        limited = false
        SallaPush.logout()
        // The second call is covered by the first logout, whose 429 wait is never shortened.
        h.advance(110.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        h.advance(15.0)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_aQueuedLogoutThatKeepsTheTags_doesNotCoverALogoutThatClearsThem() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setClearTagsOnLogout(false).build())
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        // Signed out keeping the tags; the process dies before that logout is sent.
        SallaPush.logout()
        h.settle()
        assertEquals(listOf(false), h.engine().identity.ops.map { it.clearTags })
        assertTrue(h.net.calls(Routes.UNLINK).isEmpty())
        // The next launch clears the tags at logout: its launch logout() clears more than the queued one, so it replaces it.
        val next = h.relaunch(keepCalls = true) { SallaPush.logout() }
        assertEquals(listOf(true), next.engine().identity.ops.map { it.clearTags })
        next.advance(5.0)
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertTrue(next.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_aLogoutConfirmation_goesAfterALogoutQueuedBehindTheConfirmedOne() {
        // Launch 1 keeps the tags at logout: a tag on the server, a login on the wire, then logout(); the process dies
        // with both queued.
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setClearTagsOnLogout(false).build())
        h.deliverToken()
        h.advance(3.0)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        SallaPush.logout()
        h.settle()
        assertEquals(listOf(IdentityOpKind.LOGIN, IdentityOpKind.LOGOUT), h.engine().identity.ops.map { it.kind })
        // Launch 2 clears the tags at logout. The login of launch 1 may still be applied: its logout is confirmed.
        var first: FakeCall? = null
        val next = h.relaunch(keepCalls = true) { harness ->
            harness.net.on("POST", Routes.UNLINK) { call ->
                if (first == null) {
                    first = call
                    null
                } else {
                    Http.empty()
                }
            }
        }
        next.advance(3.0)
        val onTheWire = first ?: throw AssertionError("the queued logout was not sent")
        // This logout clears the tag, which the one on the wire does not, so it is queued behind it.
        SallaPush.logout()
        next.settle()
        assertEquals(listOf(false, true), next.engine().identity.ops.map { it.clearTags })
        onTheWire.respond(Http.empty())
        next.advance(5.0)
        // The confirmation goes after that logout: its clearing is sent at once (the device is then known unlinked, so it
        // needs no unlink of its own), the confirmation a minute later.
        assertEquals("""{"remove":["plan"]}""", next.net.calls(Routes.TAGS).single().body.toString())
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        assertEquals(IdentityOpKind.LOGOUT_CONFIRM, next.engine().identity.ops.single().kind)
        next.advance(60.0)
        assertEquals(2, next.net.calls(Routes.UNLINK).size)
        assertTrue(next.engine().identity.ops.isEmpty())
        assertTrue(next.serverTags.isEmpty())
    }

    @Test
    fun test_aLogoutAnsweredWithAnError_isNoLongerSent_aTransportFailureKeepsItSent() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        // An answered error applied nothing (the identity routes are transactional): the op is no longer "sent".
        h.net.on("POST", Routes.UNLINK) { Http.error(503, "server_error") }
        SallaPush.logout()
        h.advance(1.5)
        val op = h.engine().identity.ops.single()
        assertEquals(IdentityOpKind.LOGOUT, op.kind)
        assertFalse(op.sent)
        assertTrue(h.identityJson().contains("\"sent\":false"))
        // No answer at all: the request may have been applied, so the op stays sent.
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        h.advance(2.5)
        assertTrue(h.engine().identity.ops.single().sent)
    }
}
