package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.FailureKind
import com.salla.push.internal.engine.RegStatus
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's answers are matched on the error code as well as the status, with the same rule as the iOS SDK. Only
 * 403 invalid_identity_token rejects a token, and only 401
 * invalid_device_secret drops the credentials and registers again; any other coded 401 or 403 is the route's client
 * error, logged with its real code.
 */
class AnswerClassificationTest : SdkTestBase() {

    private val failures = ArrayList<SallaPushLoginFailure>()

    private fun observeFailures() {
        SallaPush.User.addObserver(object : SallaPushUserObserver {
            override fun onUserStateChange(state: SallaPushUserChangedState) = Unit
            override fun onLoginFailure(failure: SallaPushLoginFailure) {
                failures.add(failure)
            }
        })
    }

    /** A login answered 403 with another code is a client error, never a rejected token. */
    @Test
    fun test_another403Code_onLogin_isAClientError_neverRejectsTheToken() {
        h.registered()
        observeFailures()
        var logins = 0
        h.net.on("POST", Routes.LINK) {
            logins += 1
            if (logins == 1) Http.error(403, "forbidden") else Http.empty()
        }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val clientError = h.capture.of(LogCode.IDENTITY_CLIENT_ERROR).single()
        assertEquals("forbidden", clientError.metadata[Meta.ERROR_CODE])
        assertEquals("403", clientError.metadata[Meta.HTTP_STATUS])
        assertEquals(
            "POST /devices/{id}/link (403 forbidden: \"Rejected\", request req-403) was rejected; retrying in 24 h.",
            clientError.message,
        )
        assertFalse(h.capture.has(LogCode.LOGIN_REJECTED))
        assertTrue(failures.isEmpty())
        // The login waits a day, then is sent again with the same token, which links.
        h.advance(23.0 * 3600)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        h.advance(2.0 * 3600)
        assertEquals(2, h.net.calls(Routes.LINK).size)
        assertEquals(Harness.CUSTOMER_TOKEN, h.net.calls(Routes.LINK).last().body?.getString("identity_token"))
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)
        assertEquals("1001", SallaPush.User.externalId)
        assertTrue(failures.isEmpty())
    }

    /** An update answered 401 with another code is a client error, never a new registration. */
    @Test
    fun test_another401Code_onAnUpdate_isAClientError_neverRegistersAgain() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "token_expired") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertFalse(h.capture.has(LogCode.REGISTER_REREGISTERING))
        assertNotNull(h.engine().credentials)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(RegStatus.REGISTERED, h.engine().device.registration.status)
        val clientError = h.capture.of(LogCode.NET_CLIENT_ERROR).single()
        assertEquals("token_expired", clientError.metadata[Meta.ERROR_CODE])
        assertEquals("401", clientError.metadata[Meta.HTTP_STATUS])
        assertTrue(clientError.message, clientError.message.startsWith("PUT /devices/{id} (401 token_expired"))
        // The update waits a day (the re-registration limit is never touched), then goes again on the same device.
        assertEquals(FailureKind.CLIENT, h.engine().device.ops.single().retry.lastFailure)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        h.advance(23.0 * 3600)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        h.advance(2.0 * 3600)
        assertEquals(2, h.net.calls(Routes.DEVICE).size)
        assertEquals("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}", h.net.calls(Routes.DEVICE).last().url)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
    }

    @Test
    fun test_another401Code_onLogin_isAClientError_neverRegistersAgain_neverRejects() {
        h.registered()
        observeFailures()
        h.net.on("POST", Routes.LINK) { Http.error(401, "unauthorized") }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertNotNull(h.engine().credentials)
        assertFalse(h.capture.has(LogCode.REGISTER_REREGISTERING))
        assertEquals("unauthorized", h.capture.of(LogCode.IDENTITY_CLIENT_ERROR).single().metadata[Meta.ERROR_CODE])
        assertTrue(failures.isEmpty())
        // The login is kept for its day: a logout still goes, behind nothing that was applied.
        assertEquals(1, h.engine().identity.ops.size)
        assertTrue(SallaPush.User.isLoggedIn)
    }

    @Test
    fun test_another401Code_onALogout_isAClientError_theLogoutIsKeptAndRetriedAfter24h() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(401, "session_expired") }
        SallaPush.logout()
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals("session_expired", h.capture.of(LogCode.IDENTITY_CLIENT_ERROR).single().metadata[Meta.ERROR_CODE])
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        h.advance(25.0 * 3600)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.engine().identity.ops.isEmpty())
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
    }

    @Test
    fun test_another401Code_onTags_waits24h_andKeepsEveryTag() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.error(401, "token_expired") }
        SallaPush.User.addTags(mapOf("plan" to "gold", "city" to "riyadh"))
        h.advance(12.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals("token_expired", h.capture.of(LogCode.NET_CLIENT_ERROR).single().metadata[Meta.ERROR_CODE])
        assertEquals(mapOf("plan" to "gold", "city" to "riyadh"), SallaPush.User.getTags())
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        h.advance(25.0 * 3600)
        assertEquals(2, h.net.calls(Routes.TAGS).size)
        assertEquals(mapOf("plan" to "gold", "city" to "riyadh"), h.serverTags)
    }

    /** The codes the server's contract defines keep their meaning. */
    @Test
    fun test_theContractsCodes_stillDecide() {
        h.registered()
        observeFailures()
        h.net.on("POST", Routes.LINK) { Http.error(403, "invalid_identity_token") }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.LINK) { Http.error(400, "invalid_request") }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(3.0)
        assertEquals(listOf(SallaPushLoginFailure.TOKEN_REJECTED, SallaPushLoginFailure.INVALID_REQUEST), failures)
        assertEquals(1, h.capture.count(LogCode.LOGIN_REJECTED))
        assertTrue(h.engine().identity.ops.isEmpty())

        // 401 invalid_device_secret still drops the credentials and registers again.
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        val registers = h.net.calls(Routes.REGISTER)
        assertEquals(2, registers.size)
        assertEquals(listOf("invalid_device_secret"), h.capture.of(LogCode.REGISTER_REREGISTERING).map { it.metadata[Meta.REASON] })
    }
}
