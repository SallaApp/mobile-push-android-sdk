package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.FakeCall
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The same rule as the iOS SDK: only the state written at a consent withdrawal assumes
 * that the requests on the wire took effect. The withdrawal's tag clearing and unlink are decided from the state in
 * memory once those requests are answered: a request answered with an error applied nothing, and one that got no usable
 * answer (failed in transit) may have applied.
 */
class ConsentInFlightTest : SdkTestBase() {

    private val consentRequired = SallaPushConfig.Builder().setRequiresUserConsent(true).build()

    /** Consent given and registered (a guest: no link, no tags); the requests so far are cleared. */
    private fun consentGivenAndRegistered() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
        h.net.clear()
    }

    /** Logs in with the login request held on the wire, then withdraws consent; returns the held login. */
    private fun withdrawWhileALoginIsOnTheWire(): FakeCall {
        consentGivenAndRegistered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val login = h.net.held().single()
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.settle()
        // Written at the withdrawal: the login on the wire may still link the device, and no customer id stays on disk.
        assertEquals("unknown", JSONObject(h.identityJson()).getString("server_link"))
        assertFalse(h.identityJson(), h.identityJson().contains(Harness.CUSTOMER_ID))
        assertTrue(h.net.calls.isEmpty())
        return login
    }

    private fun revoked(): String = h.capture.of(LogCode.CONSENT_REVOKED).single().message

    /** A login on the wire at the withdrawal that the server rejects: the withdrawal only opts out. */
    @Test
    fun test_withdrawalWhileALoginIsInFlight_thatIsRejected_onlyOptsOut() {
        val login = withdrawWhileALoginIsOnTheWire()
        val written = fileContents()
        login.respond(Http.error(403, "invalid_identity_token"))
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
        assertEquals("Consent withdrawn; opted out on the server; it held no link or tags to clear. SallaPush is now idle.", revoked())
        // Nothing is written after the withdrawal's one write.
        assertEquals(written, fileContents())
    }

    @Test
    fun test_withdrawalWhileALoginIsInFlight_answeredWithA503_onlyOptsOut() {
        val login = withdrawWhileALoginIsOnTheWire()
        login.respond(Http.error(503, "server_error"))
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
        assertEquals("Consent withdrawn; opted out on the server; it held no link or tags to clear. SallaPush is now idle.", revoked())
    }

    @Test
    fun test_withdrawalWhileALoginIsInFlight_thatFailsInTransit_unlinks_sinceItMayHaveApplied() {
        val login = withdrawWhileALoginIsOnTheWire()
        login.respond(Http.timeout())
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), h.net.calls.map { it.route })
        assertEquals("{}", h.net.calls[1].body.toString())
        assertEquals("Consent withdrawn; opted out and unlinked on the server. SallaPush is now idle.", revoked())
    }

    /** A login on the wire at the withdrawal that links: the withdrawal unlinks once it lands. */
    @Test
    fun test_withdrawalWhileALoginIsInFlight_thatLinks_unlinksOnceItLands() {
        val login = withdrawWhileALoginIsOnTheWire()
        h.serverExternalId = Harness.CUSTOMER_ID
        login.respond(Http.empty())
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), h.net.calls.map { it.route })
        assertNull(h.serverExternalId)
        assertEquals("Consent withdrawn; opted out and unlinked on the server. SallaPush is now idle.", revoked())
    }

    /**
     * A tags request on the wire at the withdrawal that the server applied, but whose answer
     * never came. "No tags" is no longer known, so the withdrawal clears the tags; a device that was never linked needs
     * no unlink.
     */
    @Test
    fun test_withdrawalWhileATagsRequestIsInFlight_thatFailsInTransit_sendsNoUnlink() {
        consentGivenAndRegistered()
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        val tags = h.net.held().single()
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.settle()
        h.serverTags["plan"] = "gold"
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        tags.respond(Http.timeout())
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE, Routes.TAGS), h.net.calls.map { it.route })
        assertEquals("""{"remove":["plan"]}""", h.net.calls[1].body.toString())
        assertTrue(h.serverTags.isEmpty())
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
        assertEquals("Consent withdrawn; opted out on the server; its tags were cleared. SallaPush is now idle.", revoked())
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_withdrawalWhileATagsRequestIsInFlight_answeredWithA503_onlyOptsOut() {
        consentGivenAndRegistered()
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        val tags = h.net.held().single()
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.settle()
        tags.respond(Http.error(503, "server_error"))
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
        assertEquals("Consent withdrawn; opted out on the server; it held no link or tags to clear. SallaPush is now idle.", revoked())
    }

    /** A tags request on the wire at the withdrawal that lands: its tags stay hidden from the getters. */
    @Test
    fun test_withdrawalWhileATagsRequestIsInFlight_thatLands_staysHiddenFromTheGetters() {
        consentGivenAndRegistered()
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        val tags = h.net.held().single()
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.settle()
        assertTrue("the getters forget the tags at once", SallaPush.User.getTags().isEmpty())
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        tags.respond(Http.tags(mapOf("plan" to "gold")).also { h.serverTags["plan"] = "gold" })
        h.advance(30.0)
        // The withdrawal clears the tag the request on the wire left.
        assertEquals(listOf(Routes.DEVICE, Routes.TAGS), h.net.calls.map { it.route })
        assertEquals("""{"remove":["plan"]}""", h.net.calls[1].body.toString())
        assertTrue(h.serverTags.isEmpty())
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    /** A login that lands during the withdrawal stays hidden from the getters. */
    @Test
    fun test_aLoginLandingDuringTheWithdrawal_staysHiddenFromTheGetters() {
        consentGivenAndRegistered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val login = h.net.held().single()
        h.net.on("PUT", Routes.DEVICE) { null }
        SallaPush.setConsentGiven(false)
        h.settle()
        h.serverExternalId = Harness.CUSTOMER_ID
        login.respond(Http.empty())
        h.settle()
        val optOut = h.net.held().single()
        assertEquals(Routes.DEVICE, optOut.route)
        assertNull(SallaPush.User.externalId)
        assertTrue(SallaPush.User.isLoggedIn)
        optOut.respond(Http.empty())
        h.advance(5.0)
        assertNull("the withdrawal's unlink unlinked it", h.serverExternalId)
        assertNull(SallaPush.User.externalId)
    }

    /** A login rejected while consent is withdrawn is not sent again. */
    @Test
    fun test_aLoginRejectedDuringTheWithdrawal_isNotSentAgain() {
        val login = withdrawWhileALoginIsOnTheWire()
        login.respond(Http.error(403, "invalid_identity_token"))
        h.advance(60.0)
        assertTrue(h.net.calls(Routes.LINK).isEmpty())
        // Consent given again: the rejected token left memory, so nothing links the customer before the app's next login.
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertTrue(h.net.calls(Routes.LINK).isEmpty())
    }

    /** Credentials refused during the withdrawal touch nothing, and are reported. */
    @Test
    fun test_credentialsRefusedDuringTheWithdrawal_touchNothing_andAreReported() {
        consentGivenAndRegistered()
        h.net.on("PUT", Routes.DEVICE) { null }
        SallaPush.User.setLanguage("fr")
        h.advance(2.0)
        val update = h.net.held().single()
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.settle()
        val credentials = File(h.appDirectory(), "credentials.bin")
        val sealed = credentials.readBytes()
        update.respond(Http.error(401, "invalid_device_secret"))
        h.advance(30.0)
        // Nothing is written or deleted while consent is withdrawn, and consent rules out a new registration.
        assertTrue(sealed.contentEquals(credentials.readBytes()))
        assertTrue(h.net.calls.isEmpty())
        assertEquals(
            "Consent withdrawn; the server refused this device's credentials meanwhile, so it could not be told. SallaPush is now idle.",
            revoked(),
        )
        // Consent given again: the stale credentials go at once, and the device registers again.
        h.net.on("POST", Routes.REGISTER) { null }
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertFalse("the stale credentials are deleted once consent is back", credentials.exists())
        val register = h.net.held().single()
        assertEquals(Routes.REGISTER, register.route)
        register.respond(Http.registered())
        h.settle()
        assertTrue(credentials.isFile)
    }

    /** Consent given again while the login is still on the wire: the login lands. */
    @Test
    fun test_consentGivenAgain_whileTheLoginIsStillOnTheWire_theLoginLands() {
        val login = withdrawWhileALoginIsOnTheWire()
        SallaPush.setConsentGiven(true)
        h.settle()
        h.serverExternalId = Harness.CUSTOMER_ID
        login.respond(Http.empty())
        h.advance(5.0)
        assertFalse(h.net.calls.map { it.route }.toString(), h.net.calls.any { it.route == Routes.UNLINK })
        assertTrue("the login on the wire is not sent twice", h.net.calls(Routes.LINK).isEmpty())
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        assertEquals("1001", SallaPush.User.externalId)
        assertTrue(h.net.calls(Routes.DEVICE).last().body?.getBoolean("opted_in") ?: false)
    }

    /** Every file under the SDK's root and its bytes, as text. */
    private fun fileContents(): Map<String, String> =
        File(h.root, "sallapush").walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(h.root).path to it.readBytes().joinToString(",") }
}
