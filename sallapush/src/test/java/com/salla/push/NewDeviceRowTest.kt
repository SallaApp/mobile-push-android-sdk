package com.salla.push

import com.salla.push.internal.api.HttpResult
import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server answers every registration with a new device row, without a link or tags: a queued
 * logout is settled by it, and SallaPush links the customer again with the identity token in memory, then sends the
 * tags again.
 */
class NewDeviceRowTest : SdkTestBase() {

    @Test
    fun test_aReregistration_linksAgain_thenResendsTheTags() {
        signedInWithTags()
        // The next request finds the row gone.
        failNextUpdate(Http.error(404, "unknown_device"))
        SallaPush.User.setLanguage("en")
        h.advance(30.0)
        assertTheNewRowIsLinkedAndTagged()
    }

    @Test
    fun test_anInvalidSecret_alsoRelinks() {
        signedInWithTags()
        failNextUpdate(Http.error(401, "invalid_device_secret"))
        SallaPush.User.setLanguage("en")
        h.advance(30.0)
        assertTheNewRowIsLinkedAndTagged()
    }

    @Test
    fun test_freshInstallLaunchLogout_isQueued_thenSettledByTheRegistration() {
        h.healthyServer()
        h.start()
        SallaPush.Debug.logLevel = SallaPushLogLevel.INFO
        // A fresh install does not know its row yet: the logout waits for the registration.
        SallaPush.logout()
        h.settle()
        val expected = "Logout queued until this device is registered; the registration answer settles whether it is still needed."
        assertEquals(listOf(expected), h.capture.of(LogCode.LOGOUT_QUEUED).map { it.message })
        assertTrue(h.console.contains("INFO logout.queued: $expected"))
        h.deliverToken()
        h.advance(5.0)
        assertEquals(listOf(Routes.REGISTER), h.net.calls.map { it.route })
        assertTrue(h.engine().identity.ops.isEmpty())
        assertEquals(
            "This device is registered without a link or tags to clear; the queued logout needs no request.",
            h.capture.of(LogCode.LOGOUT_SKIPPED).single().message,
        )
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
    }

    /** Signed in and linked, with a tag the server holds; the request log is cleared. */
    private fun signedInWithTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        h.net.on("POST", Routes.REGISTER) {
            h.serverExternalId = null
            h.serverTags.clear()
            Http.registered(deviceId = Http.DEVICE_ID_2)
        }
        h.net.clear()
    }

    private fun failNextUpdate(answer: HttpResult) {
        var failed = false
        h.net.on("PUT", Routes.DEVICE) {
            if (failed) {
                Http.empty()
            } else {
                failed = true
                answer
            }
        }
    }

    /** register, then the link with the token in memory, then the tags the device held, on the new row. */
    private fun assertTheNewRowIsLinkedAndTagged() {
        val routes = h.net.calls.map { it.route }
        assertEquals(Routes.DEVICE, routes.first())
        assertEquals(listOf(Routes.REGISTER, Routes.LINK, Routes.TAGS), routes.drop(1).filter { it != Routes.DEVICE })
        val link = h.net.calls(Routes.LINK).single()
        assertTrue(link.url.contains(Http.DEVICE_ID_2))
        assertEquals(Harness.CUSTOMER_ID, link.body?.getString("external_id"))
        assertEquals(Harness.CUSTOMER_TOKEN, link.body?.getString("identity_token"))
        assertEquals("gold", h.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("plan"))
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)
        assertEquals(Http.DEVICE_ID_2, SallaPush.User.pushSubscription.id)
    }
}
