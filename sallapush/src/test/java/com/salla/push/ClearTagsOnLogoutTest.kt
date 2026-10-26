package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import com.salla.push.testing.strings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * clearTagsOnLogout (default true) on the server's tags route, as on iOS: before the unlink of a logout, and before
 * the link of another customer, one PATCH removes the tags SallaPush holds, since the server's unlink keeps them.
 * SallaPush's own copy of the tags goes at the call.
 */
class ClearTagsOnLogoutTest : SdkTestBase() {

    private val consentRequired = SallaPushConfig.Builder().setRequiresUserConsent(true).build()

    /** Customer [Harness.CUSTOMER_ID] signed in with [tags] on the server; the requests so far are cleared. */
    private fun signedInWithTags(tags: Map<String, String>) {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTags(tags)
        h.advance(15.0)
        assertEquals(tags, h.serverTags)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        h.net.clear()
    }

    private fun requests(harness: Harness = h): List<String> = harness.net.calls.map { "${it.method} ${it.route}" }

    @Test
    fun test_signedInLogout_clearsTheTagsBeforeTheUnlink() {
        signedInWithTags(mapOf("plan" to "gold", "tier" to "vip"))
        SallaPush.logout()
        assertTrue(SallaPush.User.getTags().isEmpty())
        h.advance(5.0)
        assertEquals(listOf("PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}"), requests())
        assertEquals("""{"remove":["plan","tier"]}""", h.net.calls[0].body.toString())
        assertEquals("Tags cleared before the unlink.", h.capture.of(LogCode.TAGS_FLUSHED).last().message)
        assertTrue(h.serverTags.isEmpty())
        assertNull(h.serverExternalId)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_anAccountSwitch_clearsThePreviousCustomersTagsBeforeTheNewLink() {
        signedInWithTags(mapOf("plan" to "gold"))
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        assertTrue(SallaPush.User.getTags().isEmpty())
        // The new customer's tag goes after their link.
        SallaPush.User.addTag("tier", "silver")
        h.advance(15.0)
        assertEquals(listOf("PATCH ${Routes.TAGS}", "POST ${Routes.LINK}", "PATCH ${Routes.TAGS}"), requests())
        assertEquals("""{"remove":["plan"]}""", h.net.calls[0].body.toString())
        assertEquals(Harness.CUSTOMER_ID_2, h.net.calls[1].body?.getString("external_id"))
        assertEquals("""{"add":{"tier":"silver"}}""", h.net.calls[2].body.toString())
        assertEquals(
            "Account switched; the previous customer's tags are cleared before the new customer is linked.",
            h.capture.of(LogCode.LOGIN_SWITCHED).single().message,
        )
        assertEquals("Tags cleared before the new customer is linked.", h.capture.of(LogCode.TAGS_FLUSHED)[1].message)
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
        assertEquals(mapOf("tier" to "silver"), h.serverTags)
        assertEquals(mapOf("tier" to "silver"), SallaPush.User.getTags())
    }

    @Test
    fun test_aSwitchWithoutTags_sendsOnlyTheNewLink() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.clear()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(3.0)
        assertEquals(listOf("POST ${Routes.LINK}"), requests())
        assertEquals(
            "Account switched; this device holds none of the previous customer's tags.",
            h.capture.of(LogCode.LOGIN_SWITCHED).single().message,
        )
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
    }

    @Test
    fun test_aLogoutOfADeviceThatIsNotLinked_onlyClearsTheTags() {
        // An identity still unknown at the next launch, whose server holds a tag of the earlier one.
        h.registered()
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        val next = h.relaunch()
        SallaPush.logout()
        next.advance(5.0)
        assertEquals(listOf("PATCH ${Routes.TAGS}"), requests(next))
        assertEquals("""{"remove":["tier"]}""", next.net.calls[0].body.toString())
        assertEquals("Not linked; the logout needs no unlink.", next.capture.of(LogCode.LOGOUT_SKIPPED).single().message)
        assertTrue(next.serverTags.isEmpty())
        assertTrue(next.engine().identity.ops.isEmpty())
        next.close()
    }

    @Test
    fun test_aLoginQueuedBehindALogoutThatOnlyClearsTags_goesRightAfterTheClearing() {
        // An identity still unknown at the next launch, whose server holds a tag of the earlier one.
        h.registered()
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        val next = h.relaunch()
        SallaPush.logout()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        next.advance(5.0)
        assertEquals(listOf("PATCH ${Routes.TAGS}", "POST ${Routes.LINK}"), requests(next))
        assertEquals(Harness.CUSTOMER_ID, next.serverExternalId)
        next.close()
    }

    @Test
    fun test_aSwitchReplacedBeforeItWent_stillClearsThePreviousCustomersTags() {
        signedInWithTags(mapOf("plan" to "gold"))
        // Offline: the switch's tag clearing fails in transit, then the app signs out.
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(1.5)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        SallaPush.logout()
        h.healthyServer()
        h.net.clear()
        h.advance(60.0)
        // The logout replaced the switch's login and took on its clearing.
        assertEquals(listOf("PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}"), requests())
        assertEquals("""{"remove":["plan"]}""", h.net.calls[0].body.toString())
        assertTrue(h.serverTags.isEmpty())
        assertNull(h.serverExternalId)
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_aRefusedClearing_stillUnlinks() {
        signedInWithTags(mapOf("plan" to "gold"))
        h.net.on("PATCH", Routes.TAGS) { Http.error(400, "invalid_request") }
        SallaPush.logout()
        h.advance(5.0)
        assertEquals(listOf("PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}"), requests())
        assertNull(h.serverExternalId)
        assertEquals(
            "PATCH /devices/{id}/tags was rejected (400 invalid_request: \"Rejected\", request req-400); the device's tags " +
                "were not cleared.",
            h.capture.of(LogCode.NET_CLIENT_ERROR).single().message,
        )
        assertTrue(h.engine().identity.ops.isEmpty())
    }

    @Test
    fun test_aPendingSwitchClearing_survivesARelaunch() {
        signedInWithTags(mapOf("plan" to "gold"))
        // Offline: the switch's tag clearing fails in transit, and the process ends.
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(1.5)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertTrue(h.net.calls(Routes.LINK).isEmpty())
        val op = JSONObject(h.identityJson()).getJSONArray("ops").getJSONObject(0)
        assertEquals(listOf("plan"), op.getJSONArray("tag_keys").strings())

        // A minute later, the next launch: the engine starts before the app's launch login reaches it.
        val next = nextLaunch(laterSeconds = 60.0)
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        next.settle()
        next.foreground()
        next.advance(5.0)
        val identityRequests = next.net.calls.filter { it.route == Routes.TAGS || it.route == Routes.LINK }
        assertEquals(listOf(Routes.TAGS, Routes.LINK), identityRequests.map { it.route })
        assertEquals("""{"remove":["plan"]}""", identityRequests[0].body.toString())
        assertEquals(Harness.CUSTOMER_ID_2, next.serverExternalId)
        assertTrue(next.serverTags.isEmpty())
        next.close()
    }

    @Test
    fun test_consentWithdrawal_optsOut_clearsTags_thenUnlinks() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        // Unsent at the withdrawal: removed too.
        SallaPush.User.addTag("tier", "vip")
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        assertEquals(listOf("PUT ${Routes.DEVICE}", "PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}"), requests())
        assertEquals("""{"remove":["plan","tier"]}""", h.net.calls[1].body.toString())
        assertTrue(h.serverTags.isEmpty())
        assertNull(h.serverExternalId)
        assertEquals(
            "Consent withdrawn; opted out and unlinked on the server; its tags were cleared. SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
    }

    @Test
    fun test_aWithdrawalAfterAnOfflineLogout_clearsTheTagsThatLogoutHadStillToClear() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        // Offline: the logout's tag clearing fails in transit, then consent is withdrawn.
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(1.5)
        SallaPush.setConsentGiven(false)
        h.healthyServer()
        h.net.clear()
        h.advance(5.0)
        assertEquals(listOf("PUT ${Routes.DEVICE}", "PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}"), requests())
        assertEquals("""{"remove":["plan"]}""", h.net.calls[1].body.toString())
        assertTrue(h.serverTags.isEmpty())
        assertNull(h.serverExternalId)
    }

    // guard: with clearTagsOnLogout off, an account switch only links the new customer; the device keeps its tags.
    @Test
    fun test_withClearTagsOnLogoutOff_aSwitchKeepsTheTags() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setClearTagsOnLogout(false).build())
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        h.net.clear()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(15.0)
        assertEquals(listOf("POST ${Routes.LINK}"), requests())
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
    }

    // guard: a guest's login is no account switch: the guest's tags stay with the customer.
    @Test
    fun test_aGuestsLogin_keepsTheTags() {
        h.registered()
        SallaPush.logout()
        SallaPush.User.addTag("lang", "ar")
        h.advance(15.0)
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        h.net.clear()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(15.0)
        assertEquals(listOf("POST ${Routes.LINK}"), requests())
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
    }

    // guard: the launch logout of an unknown identity never removes the tags this launch set.
    @Test
    fun test_aFirstLaunchLogout_keepsThisLaunchsTags() {
        h.registered()
        SallaPush.User.addTag("lang", "ar")
        h.advance(15.0)
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        h.net.clear()
        SallaPush.logout()
        h.advance(30.0)
        assertTrue(h.net.calls(Routes.TAGS).none { it.body?.has("remove") == true })
        assertTrue(h.net.calls(Routes.UNLINK).isEmpty())
        assertEquals(mapOf("lang" to "ar"), h.serverTags)
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
    }

    // guard: a logout that only clears the tags (no link) leaves the next logout free, and the getters show
    // that at once: a tag set between the two stays.
    @Test
    fun test_theLogoutAfterALogoutThatOnlyClearsTags_isFree_andTheGettersShowItAtOnce() {
        // An identity still unknown at the next launch, whose server holds a tag of the earlier one.
        h.registered()
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        val next = h.relaunch()
        SallaPush.logout()
        SallaPush.User.addTag("lang", "ar")
        SallaPush.logout()
        val shownAtOnce = SallaPush.User.getTags()
        next.settle()
        assertEquals(mapOf("lang" to "ar"), shownAtOnce)
        assertEquals(shownAtOnce, SallaPush.User.getTags())
        next.close()
    }

    /**
     * The next launch on the same phone, [laterSeconds] later, initialised but not in the foreground yet
     * (Application.onCreate before the first activity): same storage and server.
     */
    private fun nextLaunch(laterSeconds: Double): Harness {
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + (laterSeconds * 1000).toLong()
        next.serverExternalId = h.serverExternalId
        next.serverTags.putAll(h.serverTags)
        next.install()
        next.healthyServer()
        next.init()
        return next
    }
}
