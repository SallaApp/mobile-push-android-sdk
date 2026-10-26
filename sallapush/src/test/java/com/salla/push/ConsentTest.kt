package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsentTest : SdkTestBase() {

    private val consentRequired = SallaPushConfig.Builder().setRequiresUserConsent(true).build()

    private fun storedFiles(): List<String> =
        File(h.root, "sallapush").walkTopDown().filter { it.isFile }.map { it.relativeTo(h.root).path }.toList()

    @Test
    fun test_requiredNotGiven_nothingSentOrStored() {
        h.healthyServer()
        h.start(config = consentRequired)
        h.deliverToken()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(60.0)
        assertTrue(h.net.calls.isEmpty())
        assertTrue(storedFiles().isEmpty())
        assertEquals(1, h.capture.count(LogCode.CONSENT_WAITING))
        assertEquals(0, h.firebase.fetches)
    }

    @Test
    fun test_consentGiven_startsAndReplaysInOrder() {
        h.healthyServer()
        h.start(config = consentRequired)
        h.deliverToken()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(5.0)
        SallaPush.setConsentGiven(true)
        h.advance(15.0)
        assertTrue(h.capture.has(LogCode.CONSENT_GIVEN))
        val order = h.net.calls.map { it.route }
        assertEquals(listOf(Routes.REGISTER, Routes.LINK, Routes.TAGS), order)
        assertTrue(storedFiles().contains("sallapush/consent.json"))
    }

    @Test
    fun test_consentPersistedAcrossRelaunch() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.install()
        next.healthyServer()
        next.start(config = consentRequired)
        next.deliverToken()
        SallaPush.User.setLanguage("en")
        next.advance(5.0)
        assertFalse(next.capture.has(LogCode.CONSENT_WAITING))
        assertEquals(1, next.net.calls(Routes.DEVICE).size)
        next.close()
    }

    @Test
    fun test_consentWithdrawal_optsOutThenUnlinks() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.clear()

        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        assertTrue(h.capture.has(LogCode.CONSENT_REVOKED))
        val routes = h.net.calls.map { it.route }
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), routes)
        val put = h.net.calls[0].body ?: throw AssertionError()
        assertEquals(listOf("opted_in"), put.keys().asSequence().toList())
        assertFalse(put.getBoolean("opted_in"))
        assertEquals("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}/unlink", h.net.calls[1].url)
        assertEquals("{}", h.net.calls[1].body.toString())
        // No customer id stays on disk.
        assertTrue(JSONObject(h.identityJson()).isNull("desired_external_id"))
        assertFalse(h.identityJson(), h.identityJson().contains(Harness.CUSTOMER_ID))

        // The withdrawal's one write is behind it: nothing is sent or written for the calls made now.
        val atRest = fileContents()
        h.net.clear()
        SallaPush.User.addTag("x", "1")
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(60.0)
        assertTrue(h.net.calls.isEmpty())
        assertEquals(atRest, fileContents())
        assertTrue(File(h.root, "sallapush/consent.json").readText().contains("\"given\":false"))
        assertEquals(
            "Consent withdrawn; opted out and unlinked on the server. SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
    }

    // ── The withdrawal writes the minimised state once (data minimisation, as on iOS) ──

    @Test
    fun test_withdrawal_writesTheMinimisedStateOnce_beforeItsRequests_andNothingAfter() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("first_name", "Customer A")
        h.advance(15.0)
        // Unsent at the withdrawal: a tag change, and the events of a push.
        SallaPush.User.addTag("last_login", "1790000000000")
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_c0de")))
        h.settle()
        assertTrue(h.identityJson().contains("\"desired_external_id\":\"1001\""))
        assertTrue(h.stateJson().contains("Customer A") && h.stateJson().contains("last_login"))
        assertTrue(File(h.appDirectory(), "events.json").readText().contains("msg_c0de"))
        h.net.on("PUT", Routes.DEVICE) { null }
        h.net.clear()

        SallaPush.setConsentGiven(false)
        h.settle()
        val optOut = h.net.held().single()
        // Written before any withdrawal request was answered: no external id, tags or events at rest, and a state that
        // makes a later launch send the opt-in and the login again.
        val identity = JSONObject(h.identityJson())
        assertEquals("customer", identity.getString("desired"))
        assertEquals("unknown", identity.getString("server_link"))
        assertTrue(identity.isNull("linked_external_id"))
        assertTrue(identity.isNull("desired_external_id"))
        assertEquals("unknown", identity.getString("server_tags"))
        val state = JSONObject(h.stateJson())
        assertTrue(state.getJSONObject("registration").isNull("sent_fingerprint"))
        val tags = state.getJSONObject("tags")
        assertEquals(0, tags.getJSONObject("mirror").length())
        assertEquals(0, tags.getJSONObject("pending_add").length())
        assertEquals(0, tags.getJSONObject("pending_remove").length())
        assertEquals(0, state.getJSONArray("ops").length())
        assertEquals(0, JSONObject(File(h.appDirectory(), "events.json").readText()).getJSONArray("pending").length())
        assertTrue(File(h.appDirectory(), "credentials.bin").isFile)
        val written = fileContents()

        optOut.respond(Http.empty())
        h.advance(30.0)
        assertEquals(listOf(Routes.DEVICE, Routes.TAGS, Routes.UNLINK), h.net.calls.map { it.route })
        assertEquals("""{"remove":["first_name","last_login"]}""", h.net.calls[1].body.toString())
        assertEquals(written, fileContents())
        assertNull(SallaPush.User.externalId)
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_withdrawal_thenALaunchThatNoLongerRequiresConsent_optsInAndRelinksAtOnce() {
        withdrawFromALinkedCustomerWithTags()
        // The app stops requiring consent; consent.json still says withdrawn, and nothing gives consent again.
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 60_000L
        next.serverExternalId = h.serverExternalId
        next.install()
        next.healthyServer()
        next.start()
        next.deliverToken()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        next.advance(5.0)
        // The launch login is not skipped and the foreground's update sends opted_in: the server learns both at once.
        assertEquals(listOf(Routes.DEVICE, Routes.LINK), next.net.calls.map { it.route })
        assertTrue(next.net.calls[0].body?.getBoolean("opted_in") ?: false)
        assertEquals(Harness.CUSTOMER_ID, next.serverExternalId)
        assertEquals("1001", SallaPush.User.externalId)
        assertFalse(next.capture.has(LogCode.LOGIN_SKIPPED_SAME))
        next.advance(60.0)
        assertEquals(2, next.net.calls.size)
        next.close()
    }

    @Test
    fun test_withdrawalWhileARegistrationIsOnTheWire_writesNoLinkOrTags_andOnlyOptsOut() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        assertEquals(ServerLink.NONE, h.engine().identity.serverLink)
        // The secret is refused: SallaPush registers again, and that registration is on the wire at the withdrawal.
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        h.net.on("POST", Routes.REGISTER) { null }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        val register = h.net.held().single()
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.settle()
        // Its answer is a new row, without a link or tags: the written state says so.
        val identity = JSONObject(h.identityJson())
        assertEquals("none", identity.getString("server_link"))
        assertEquals("none", identity.getString("server_tags"))
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        register.respond(Http.registered(deviceId = Http.DEVICE_ID_2))
        h.advance(5.0)
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
    }

    @Test
    fun test_consentBackWhileTheWithdrawalOptOutIsOnTheWire_relinks_soALaterSignOutUnlinks() {
        linkedWithConsent()
        h.net.on("PUT", Routes.DEVICE) { null }
        SallaPush.setConsentGiven(false)
        h.settle()
        val optOut = h.net.held().single()
        SallaPush.setConsentGiven(true)
        h.settle()
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        optOut.respond(Http.empty())
        h.advance(5.0)
        // The withdrawal assumed the link cleared: the customer is linked again, so this install's link holds it.
        assertEquals(1, h.net.calls(Routes.LINK).size)
        assertEquals(ServerLink.LINKED, h.engine().identity.serverLink)
        assertEquals(Harness.CUSTOMER_ID, h.engine().identity.linkedExternalId)
        h.net.clear()
        SallaPush.logout()
        h.advance(3.0)
        assertEquals(listOf(Routes.UNLINK), h.net.calls.map { it.route })
    }

    // ── What the withdrawal says: what the server applied, and what it refused (as on iOS) ──

    /** A registered, linked customer who gave consent; the requests and log records so far are cleared. */
    private fun linkedWithConsent() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        h.net.clear()
        h.capture.clear()
    }

    @Test
    fun test_withdrawal_refusedByTheServer_logsTheRefusals_andSaysSo() {
        linkedWithConsent()
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "device_disabled", reason = "dead_token") }
        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        // An ordinary refusal (the server never answers device_disabled): the opt-out is refused, the unlink applied.
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), h.net.calls.map { it.route })
        val refused = h.capture.of(LogCode.NET_CLIENT_ERROR).single()
        assertEquals(
            "PUT /devices/{id} (401 device_disabled: \"Rejected\", request req-401) was rejected; the consent withdrawal's opt-out was not applied.",
            refused.message,
        )
        assertEquals("401", refused.metadata[Meta.HTTP_STATUS])
        assertEquals("device_disabled", refused.metadata[Meta.ERROR_CODE])
        assertEquals("dead_token", refused.metadata[Meta.REASON])
        assertEquals("req-401", refused.metadata[Meta.REQUEST_ID])
        assertEquals(Routes.DEVICE, refused.metadata[Meta.ROUTE])
        assertTrue(h.console.toString(), h.console.contains("ERROR net.client_error: ${refused.message}"))
        assertEquals(
            "Consent withdrawn; unlinked on the server; the opt-out was refused (401 device_disabled: \"Rejected\", request req-401). " +
                "SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
        // A refusal is final for the withdrawal: nothing is retried, and the device is not registered again.
        h.advance(600.0)
        assertEquals(2, h.net.calls.size)
        assertFalse(h.capture.has(LogCode.NET_RETRY))
    }

    @Test
    fun test_withdrawal_bothStepsRefused_routesClosed_logsBoth_andNeverSaysOptedOut() {
        linkedWithConsent()
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "not_found", message = "Not found") }
        h.net.on("POST", Routes.UNLINK) { Http.error(404, "not_found", message = "Not found") }
        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), h.net.calls.map { it.route })
        assertEquals(
            listOf(
                "SallaPush device routes are closed (404 not_found); the consent withdrawal's opt-out was not applied.",
                "SallaPush device routes are closed (404 not_found); the consent withdrawal's unlink was not applied.",
            ),
            h.capture.of(LogCode.NET_ROUTES_CLOSED).map { it.message },
        )
        assertEquals(2, h.console.count { it.startsWith("WARN net.routes_closed: SallaPush device routes are closed (404 not_found); the consent withdrawal's") })
        assertEquals(
            "Consent withdrawn; the opt-out was refused (404 not_found: \"Not found\", request req-404); the unlink was refused " +
                "(404 not_found: \"Not found\", request req-404). SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
    }

    @Test
    fun test_withdrawal_retriesRunOut_saysWhatWasNotSent() {
        linkedWithConsent()
        h.net.on("PUT", Routes.DEVICE) { Http.networkFailure() }
        SallaPush.setConsentGiven(false)
        h.advance(300.0)
        assertEquals(6, h.net.calls(Routes.DEVICE).size)
        assertTrue(h.net.calls(Routes.UNLINK).isEmpty())
        assertEquals(
            "PUT /devices/{id} (network: IOException) failed 6 times; the consent withdrawal is retried in this process only, so it ends here.",
            h.capture.of(LogCode.NET_RETRY_EXHAUSTED).single().message,
        )
        assertEquals(
            "Consent withdrawn; the opt-out could not be sent in this process (network: IOException), so the later steps were not sent " +
                "either. SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
        h.advance(3600.0)
        assertEquals(6, h.net.calls.size)
    }

    @Test
    fun test_reconsentInTheSameProcess_sameToken_optsInAgain_relinks_andWritesTheReconciledState() {
        withdrawFromALinkedCustomerWithTags()
        h.net.clear()

        SallaPush.setConsentGiven(true)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(5.0)
        assertEquals(listOf(Routes.LINK, Routes.DEVICE), h.net.calls.map { it.route })
        assertEquals(Harness.CUSTOMER_TOKEN, h.net.calls[0].body?.getString("identity_token"))
        assertTrue(h.net.calls[1].body?.getBoolean("opted_in") ?: false)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        assertEquals("1001", SallaPush.User.externalId)
        assertTrue(SallaPush.User.pushSubscription.optedIn)
        // The withdrawal dropped the tags: they are not shown again.
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertTrue(h.identityJson().contains("\"server_link\":\"linked\""))
        assertFalse(h.stateJson().contains("Customer A"))
        h.advance(60.0)
        assertEquals(2, h.net.calls.size)
    }

    @Test
    fun test_reconsentInTheSameProcess_withoutALoginCall_relinksWithTheTokenInMemory() {
        withdrawFromALinkedCustomerWithTags()
        h.net.clear()
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertEquals(listOf(Routes.LINK, Routes.DEVICE), h.net.calls.map { it.route })
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
    }

    @Test
    fun test_reconsentAfterARelaunch_sameToken_optsInAgain_andRelinks() {
        withdrawFromALinkedCustomerWithTags()
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.serverExternalId = h.serverExternalId
        next.install()
        next.healthyServer()
        next.start(config = consentRequired)
        next.deliverToken()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        next.advance(5.0)
        assertTrue(next.net.calls.isEmpty())
        assertTrue(next.capture.has(LogCode.CONSENT_WAITING))

        SallaPush.setConsentGiven(true)
        next.advance(5.0)
        assertEquals(listOf(Routes.LINK, Routes.DEVICE), next.net.calls.map { it.route })
        assertTrue(next.net.calls[1].body?.getBoolean("opted_in") ?: false)
        assertEquals(Harness.CUSTOMER_ID, next.serverExternalId)
        assertEquals(ServerLink.LINKED, next.engine().identity.serverLink)
        assertEquals("1001", SallaPush.User.externalId)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertFalse(next.stateJson().contains("Customer A"))
        next.close()
    }

    @Test
    fun test_withdrawalWhileALoginIsInFlight_unlinksOnceTheLoginLands() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        val login = h.net.held().single()
        h.net.clear()

        SallaPush.setConsentGiven(false)
        h.advance(3.0)
        assertTrue(h.net.calls.isEmpty())
        h.serverExternalId = Harness.CUSTOMER_ID
        login.respond(Http.empty())
        h.advance(5.0)
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), h.net.calls.map { it.route })
        assertFalse(h.net.calls[0].body?.getBoolean("opted_in") ?: true)
        assertEquals("{}", h.net.calls[1].body.toString())
        assertNull(h.serverExternalId)
        h.advance(60.0)
        assertEquals(2, h.net.calls.size)
    }

    @Test
    fun test_withdrawalWhileTheFirstRegistrationIsOnTheWire_optsOutOnceItLands() {
        h.healthyServer()
        h.net.on("POST", Routes.REGISTER) { null }
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        val register = h.net.held().single()
        h.net.clear()

        SallaPush.setConsentGiven(false)
        h.advance(3.0)
        assertTrue(h.net.calls.isEmpty())
        val atWithdrawal = fileContents()
        register.respond(Http.registered())
        h.advance(5.0)
        // The row the registration created is opted out like any other; nothing else goes out.
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
        assertFalse(h.net.calls[0].body?.getBoolean("opted_in") ?: true)
        h.advance(60.0)
        assertEquals(1, h.net.calls.size)
        assertTrue(h.capture.of(LogCode.CONSENT_REVOKED).single().message.contains("opted out on the server"))
        // Nothing is written while consent is withdrawn: the new credentials live in memory only.
        assertEquals(atWithdrawal, fileContents())
        assertFalse(File(h.appDirectory(), "credentials.bin").exists())

        // Consent given again: the same registration is used and its credentials are written now.
        h.net.clear()
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertTrue(File(h.appDirectory(), "credentials.bin").isFile)
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
        assertTrue(h.net.calls.single().body?.getBoolean("opted_in") ?: false)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(Http.DEVICE_ID, h.relaunch().engine().credentials?.deviceId)
    }

    /** Every file under the SDK's root and its bytes, as text. */
    private fun fileContents(harness: Harness = h): Map<String, String> =
        File(harness.root, "sallapush").walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(harness.root).path to it.readBytes().joinToString(",") }

    @Test
    fun test_reconsentWhileTheWithdrawalUnlinkIsOnTheWire_relinksAfterIt() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { null }
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.advance(3.0)
        val unlink = h.net.held().single()

        SallaPush.setConsentGiven(true)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK), h.net.calls.map { it.route })
        h.serverExternalId = null
        unlink.respond(Http.empty())
        h.advance(5.0)
        assertEquals(listOf(Routes.DEVICE, Routes.UNLINK, Routes.LINK, Routes.DEVICE), h.net.calls.map { it.route })
        assertTrue(h.net.calls[3].body?.getBoolean("opted_in") ?: false)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
    }

    @Test
    fun test_failedWithdrawalWrite_removesTheOldAnswer_soTheNextLaunchWaits() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        val consentFile = File(h.root, "sallapush/consent.json")
        assertTrue(consentFile.readText().contains("\"given\":true"))
        // Writing consent.json fails from now on (its temporary file cannot be created); deleting it still works.
        File(h.root, "sallapush/consent.json.new/blocker").mkdirs()
        SallaPush.setConsentGiven(false)
        SallaPush.setConsentRequired(true)
        h.advance(5.0)
        assertEquals(1, h.capture.of(LogCode.STORAGE_FAILED).count { it.message.contains("consent.json") })
        assertFalse(consentFile.exists())

        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.install()
        next.healthyServer()
        next.start(config = consentRequired)
        next.deliverToken()
        SallaPush.User.setLanguage("en")
        next.advance(10.0)
        assertTrue(next.capture.has(LogCode.CONSENT_WAITING))
        assertTrue(next.net.calls.isEmpty())
        next.close()
    }

    @Test
    fun test_failedConsentWrite_isWrittenAgainAtTheNextForeground() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        val blocker = File(h.root, "sallapush/consent.json.new")
        File(blocker, "blocker").mkdirs()
        SallaPush.setConsentGiven(false)
        h.advance(3.0)
        assertFalse(File(h.root, "sallapush/consent.json").exists())
        blocker.deleteRecursively()
        h.background()
        h.foreground()
        assertTrue(File(h.root, "sallapush/consent.json").readText().contains("\"given\":false"))
        assertEquals(1, h.capture.of(LogCode.STORAGE_FAILED).count { it.message.contains("consent.json") })
    }

    // ── A WorkManager run never sends after a withdrawal, also in a new process ──

    @Test
    fun test_failedWithdrawalWrite_pendingFlushWork_workerInANewProcess_sendsNothing() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        h.net.on("PATCH", Routes.TAGS) { Http.html(503) }
        SallaPush.User.addTag("cart", "1")
        h.advance(11.0)
        h.background()
        val flush = h.work.of("flush").last()
        assertTrue("the work carries the consent requirement", flush.consentRequired)
        assertTrue(h.stateJson().contains("cart"))

        File(h.root, "sallapush/consent.json.new/blocker").mkdirs()
        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        assertFalse(File(h.root, "sallapush/consent.json").exists())
        assertEquals(listOf(h.key.appId to h.key.hostTag), h.work.of("cancel").map { it.appId to it.hostTag })

        // The work ran before the cancel reached WorkManager, in a process where nothing required consent.
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 120_000L
        next.install()
        next.healthyServer()
        assertEquals(FlushOutcome.SUCCESS, next.runWorker(consentRequired = flush.consentRequired))
        assertTrue(next.net.calls.map { "${it.method} ${it.route}" }.toString(), next.net.calls.isEmpty())
        next.close()
    }

    @Test
    fun test_workEnqueuedWhileConsentRequired_runsInANewProcessOnceConsentIsGiven() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        h.net.on("PATCH", Routes.TAGS) { Http.html(503) }
        SallaPush.User.addTag("cart", "1")
        h.advance(11.0)
        h.background()
        val flush = h.work.of("flush").last()

        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 120_000L
        next.install()
        next.healthyServer()
        assertEquals(FlushOutcome.SUCCESS, next.runWorker(consentRequired = flush.consentRequired))
        assertEquals("1", next.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("cart"))
        next.close()
    }

    /** A registered, linked customer with a tag, then setConsentGiven(false): the server is opted out and unlinked. */
    private fun withdrawFromALinkedCustomerWithTags() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("first_name", "Customer A")
        h.advance(15.0)
        assertEquals(mapOf("first_name" to "Customer A"), SallaPush.User.getTags())
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        assertEquals(listOf(Routes.DEVICE, Routes.TAGS, Routes.UNLINK), h.net.calls.map { it.route })
        assertNull(h.serverExternalId)
        assertTrue(h.serverTags.isEmpty())
        assertNull(SallaPush.User.externalId)
    }

    @Test
    fun test_withdrawal_whenNotLinked_sendsOnlyOptOut() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        h.net.clear()
        SallaPush.setConsentGiven(false)
        h.advance(5.0)
        assertEquals(listOf(Routes.DEVICE), h.net.calls.map { it.route })
    }

    @Test
    fun test_setConsentRequiredFalse_lowersOnlyWhatACallRaised() {
        h.healthyServer()
        SallaPush.setConsentRequired(true)
        h.start()
        h.deliverToken()
        h.advance(5.0)
        assertTrue(h.net.calls.isEmpty())
        SallaPush.setConsentRequired(false)
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)

        val other = Harness(h.context).install()
        other.healthyServer()
        other.start(config = consentRequired)
        other.deliverToken()
        SallaPush.setConsentRequired(false)
        other.advance(5.0)
        assertTrue(other.net.calls.isEmpty())
        other.close()
    }

    @Test
    fun test_consentGivenBeforeInit_isPersistedAtBootstrap() {
        SallaPush.setConsentGiven(true)
        h.healthyServer()
        h.start(config = consentRequired)
        h.deliverToken()
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertTrue(File(h.root, "sallapush/consent.json").readText().contains("\"given\":true"))
    }

    @Test
    fun test_consentRaisedWhileRunning_pausesEngine() {
        h.registered()
        SallaPush.setConsentRequired(true)
        h.advance(1.0)
        h.net.clear()
        SallaPush.User.setLanguage("en")
        h.advance(10.0)
        assertTrue(h.net.calls.isEmpty())
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    // ── A withdrawal made before initWithContext, after consent was given at an earlier launch ──

    private enum class Call { REQUIRED, GIVEN, WITHDRAWN, INIT }

    /**
     * Launch 1 gives consent, registers, logs in and sets a tag. Launch 2 makes [calls] in that order (init without a
     * consent requirement in its configuration), then runs: the withdrawal must reach the server as one opt-out PUT, one
     * tag clearing and one unlink, with nothing written but consent.json, and SallaPush idle afterwards.
     */
    private fun withdrawalAtTheNextLaunch(vararg calls: Call, config: SallaPushConfig? = null) {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(20.0)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)

        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 60_000L
        next.serverExternalId = h.serverExternalId
        next.serverTags.putAll(h.serverTags)
        h.close()
        h = next.install()
        h.healthyServer()
        var before = fileContents()
        for (call in calls) {
            when (call) {
                Call.REQUIRED -> SallaPush.setConsentRequired(true)
                Call.GIVEN -> SallaPush.setConsentGiven(true)
                Call.WITHDRAWN -> {
                    h.settle()
                    before = fileContents()
                    SallaPush.setConsentGiven(false)
                }
                Call.INIT -> h.start(config = config)
            }
        }
        h.advance(30.0)
        assertEquals(
            listOf("PUT ${Routes.DEVICE}", "PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}"),
            h.net.calls.map { "${it.method} ${it.route}" },
        )
        val optOut = h.net.calls[0].body ?: throw AssertionError("no body")
        assertEquals(listOf("opted_in"), optOut.keys().asSequence().toList())
        assertFalse(optOut.getBoolean("opted_in"))
        assertEquals("""{"remove":["plan"]}""", h.net.calls[1].body.toString())
        assertEquals("{}", h.net.calls[2].body.toString())
        assertNull(h.serverExternalId)
        assertTrue(h.serverTags.isEmpty())
        assertEquals(
            "Consent withdrawn; opted out and unlinked on the server; its tags were cleared. SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
        // The app's files were written once, to the minimised state, and consent.json says "withdrawn".
        val after = fileContents()
        assertTrue(before.isNotEmpty() && after != before)
        val identity = JSONObject(h.identityJson())
        assertEquals("unknown", identity.getString("server_link"))
        assertTrue(identity.isNull("desired_external_id"))
        assertTrue(identity.isNull("linked_external_id"))
        val state = JSONObject(h.stateJson())
        assertEquals(0, state.getJSONObject("tags").getJSONObject("mirror").length())
        assertFalse(h.stateJson().contains("gold"))
        assertTrue(state.getJSONObject("registration").isNull("sent_fingerprint"))
        assertTrue(File(h.root, "sallapush/consent.json").readText().contains("\"given\":false"))
        // Idle: no more requests, and nothing is written for a call the app makes now.
        SallaPush.User.addTag("x", "1")
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(120.0)
        assertEquals(3, h.net.calls.size)
        assertEquals(after, fileContents())
    }

    @Test
    fun test_withdrawalBeforeInit_requiredThenWithdrawnThenInit() =
        withdrawalAtTheNextLaunch(Call.REQUIRED, Call.WITHDRAWN, Call.INIT)

    @Test
    fun test_withdrawalBeforeInit_withdrawnThenRequiredThenInit() =
        withdrawalAtTheNextLaunch(Call.WITHDRAWN, Call.REQUIRED, Call.INIT)

    @Test
    fun test_withdrawal_requiredThenInitThenWithdrawn() =
        withdrawalAtTheNextLaunch(Call.REQUIRED, Call.INIT, Call.WITHDRAWN)

    @Test
    fun test_withdrawal_withdrawnThenInitThenRequired() =
        withdrawalAtTheNextLaunch(Call.WITHDRAWN, Call.INIT, Call.REQUIRED)

    @Test
    fun test_withdrawal_initThenRequiredThenWithdrawn() =
        withdrawalAtTheNextLaunch(Call.INIT, Call.REQUIRED, Call.WITHDRAWN)

    @Test
    fun test_withdrawal_initThenWithdrawnThenRequired() =
        withdrawalAtTheNextLaunch(Call.INIT, Call.WITHDRAWN, Call.REQUIRED)

    @Test
    fun test_withdrawalBeforeInit_withTheConfigurationRequiringConsent() =
        withdrawalAtTheNextLaunch(Call.REQUIRED, Call.WITHDRAWN, Call.INIT, config = consentRequired)

    @Test
    fun test_withdrawalBeforeInit_withOnlyTheConfigurationRequiringConsent() =
        withdrawalAtTheNextLaunch(Call.WITHDRAWN, Call.INIT, config = consentRequired)

    @Test
    fun test_withdrawalBeforeInit_ofAConsentAlsoGivenInThisProcess() =
        withdrawalAtTheNextLaunch(Call.REQUIRED, Call.GIVEN, Call.WITHDRAWN, Call.INIT)

    @Test
    fun test_withdrawalBeforeInit_thenConsentAgainInTheSameProcess_optsInAndRelinks() {
        withdrawalAtTheNextLaunch(Call.REQUIRED, Call.WITHDRAWN, Call.INIT)
        h.net.clear()
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        // The calls made while consent was withdrawn run now, in order: the launch login goes out, and the next update
        // opts the device in again.
        assertEquals(listOf(Routes.LINK, Routes.DEVICE), h.net.calls.map { it.route })
        assertTrue(h.net.calls[1].body?.getBoolean("opted_in") ?: false)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        assertEquals("1001", SallaPush.User.externalId)
        // The withdrawal dropped the tags SallaPush held: only the tag set while it was withdrawn goes out.
        h.advance(15.0)
        assertEquals("""{"add":{"x":"1"}}""", h.net.calls(Routes.TAGS).single().body.toString())
        assertTrue(File(h.root, "sallapush/consent.json").readText().contains("\"given\":true"))
        assertTrue(h.identityJson().contains("\"server_link\":\"linked\""))
    }

    @Test
    fun test_withdrawalBeforeInit_ofAnUnregisteredDevice_sendsNothing_andSaysSo() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.advance(3.0)
        assertTrue(h.net.calls.isEmpty())
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        h.close()
        h = next.install()
        h.healthyServer()
        SallaPush.setConsentRequired(true)
        SallaPush.setConsentGiven(false)
        h.start()
        h.deliverToken()
        h.advance(30.0)
        assertTrue(h.net.calls.isEmpty())
        assertEquals(
            "Consent withdrawn; this device was not registered, so the server holds nothing to minimise. SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
    }

    // ── consent.json that holds no answer is reported, once, with the iOS SDK's codes ──

    /** consent.json holds [content] (a directory when null), then SallaPush starts with consent required. */
    private fun startWithConsentFile(content: String?) {
        val file = File(h.root, "sallapush/consent.json")
        if (content == null) {
            File(file, "unreadable").mkdirs()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        h.healthyServer()
        h.start(config = consentRequired)
        h.deliverToken()
        // A second initialisation reads a file that was read no second time, and reports a failure no second time.
        SallaPush.initWithContext(h.context, Harness.APP_ID, consentRequired)
        h.advance(5.0)
        assertTrue(h.net.calls.isEmpty())
        assertTrue(h.capture.has(LogCode.CONSENT_WAITING))
    }

    @Test
    fun test_corruptConsentFile_isReportedOnce_andCountsAsNotGivenUntilTheAppSetsIt() {
        startWithConsentFile("{\"v\":1,\"given\":tru")
        val reset = h.capture.of(LogCode.STORAGE_STATE_RESET).single()
        assertEquals("SallaPush consent (consent.json) was corrupt; it counts as not given until the app sets it again.", reset.message)
        assertTrue(h.console.toString(), h.console.contains("ERROR storage.state_reset: ${reset.message}"))
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertTrue(File(h.root, "sallapush/consent.json").readText().contains("\"given\":true"))
        assertEquals(1, h.capture.count(LogCode.STORAGE_STATE_RESET))
    }

    @Test
    fun test_consentFileOfAnotherVersion_orWithoutAnAnswer_isCorrupt() {
        startWithConsentFile("{\"v\":2,\"given\":true}")
        assertEquals(1, h.capture.count(LogCode.STORAGE_STATE_RESET))

        val other = Harness(h.context).install()
        File(other.root, "sallapush").mkdirs()
        File(other.root, "sallapush/consent.json").writeText("{\"v\":1}")
        other.healthyServer()
        other.start(config = consentRequired)
        other.deliverToken()
        other.advance(5.0)
        assertTrue(other.net.calls.isEmpty())
        assertEquals(1, other.capture.count(LogCode.STORAGE_STATE_RESET))
        other.close()
    }

    @Test
    fun test_unreadableConsentFile_isReportedOnce_withItsReason_andReadAgainLater() {
        startWithConsentFile(null)
        val failed = h.capture.of(LogCode.STORAGE_FAILED).single { it.message.contains("consent.json") }
        assertEquals(
            "Could not read SallaPush consent (consent.json: not readable); it is read again at the next foreground, " +
                "consent call or initWithContext.",
            failed.message,
        )
        assertTrue(h.console.toString(), h.console.contains("ERROR storage.failed: ${failed.message}"))
        // Still unreadable at the next foreground and consent call: SallaPush keeps waiting, and says it no second time.
        h.background()
        h.foreground()
        SallaPush.setConsentRequired(true)
        h.advance(5.0)
        assertTrue(h.net.calls.isEmpty())
        assertEquals(1, h.capture.of(LogCode.STORAGE_FAILED).count { it.message.contains("consent.json") })
        assertFalse(h.capture.has(LogCode.STORAGE_STATE_RESET))
    }

    // ── consent.json that cannot be read is never taken as an answer nor written over (as on iOS) ──

    /** consent.json holds [content] and cannot be read while [block] runs. */
    private fun withUnreadableConsentFile(content: String, block: () -> Unit): File {
        val file = File(h.root, "sallapush/consent.json")
        file.parentFile?.mkdirs()
        file.writeText(content)
        assertTrue(file.setReadable(false))
        try {
            block()
        } finally {
            file.setReadable(true)
        }
        assertEquals(content, file.readText())
        return file
    }

    @Test
    fun test_aConsentFileReadableOnlyLater_isNeverTakenAsAnAnswer_andItsConsentAppliesAtTheNextForeground() {
        val file = withUnreadableConsentFile("{\"v\":1,\"given\":true}") {
            h.healthyServer()
            h.start(config = consentRequired)
            h.deliverToken()
            h.advance(5.0)
            assertTrue(h.net.calls.isEmpty())
            assertTrue(h.capture.has(LogCode.CONSENT_WAITING))
        }
        h.background()
        h.foreground()
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        // The consent of an earlier launch, not a call of this one: consent.given is not logged, nothing is written.
        assertFalse(h.capture.has(LogCode.CONSENT_GIVEN))
        assertEquals("{\"v\":1,\"given\":true}", file.readText())
    }

    @Test
    fun test_aWithdrawalWhileTheConsentFileIsUnreadable_writesNothing_andIsCarriedOutOnceTheFileIsRead() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(5.0)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        val previous = h
        lateinit var file: File
        h = previous.relaunch(keepCalls = true) { next ->
            file = File(next.root, "sallapush/consent.json")
            assertTrue(file.setReadable(false))
            SallaPush.setConsentRequired(true)
            SallaPush.setConsentGiven(false)
        }
        previous.close()
        try {
            h.advance(30.0)
            // The stored answer is unknown: nothing is sent, and the file is not written over.
            assertTrue(h.net.calls.isEmpty())
            assertFalse(h.capture.has(LogCode.CONSENT_REVOKED))
        } finally {
            file.setReadable(true)
        }
        assertEquals("{\"v\":1,\"given\":true}", file.readText())
        // Readable at the next foreground: it held the consent the app withdrew, so the withdrawal is carried out.
        h.background()
        h.foreground()
        h.advance(30.0)
        assertEquals(listOf("PUT ${Routes.DEVICE}", "POST ${Routes.UNLINK}"), h.net.calls.map { "${it.method} ${it.route}" })
        assertFalse(h.net.calls[0].body?.getBoolean("opted_in") ?: true)
        assertEquals("{}", h.net.calls[1].body.toString())
        assertEquals(
            "Consent withdrawn; opted out and unlinked on the server. SallaPush is now idle.",
            h.capture.of(LogCode.CONSENT_REVOKED).single().message,
        )
        assertTrue(file.readText().contains("\"given\":false"))
    }

    @Test
    fun test_consentGivenWhileTheConsentFileIsUnreadable_startsAtOnce_andIsWrittenOnceTheFileIsRead() {
        val file = withUnreadableConsentFile("{\"v\":1,\"given\":false}") {
            h.healthyServer()
            h.start(config = consentRequired)
            h.deliverToken()
            SallaPush.setConsentGiven(true)
            h.advance(5.0)
            assertEquals(1, h.net.calls(Routes.REGISTER).size)
        }
        h.background()
        h.foreground()
        h.advance(5.0)
        assertTrue(file.readText().contains("\"given\":true"))
        assertFalse(h.capture.has(LogCode.CONSENT_REVOKED))
    }

    @Test
    fun test_aValidConsentFile_isNotReported() {
        startWithConsentFile("{\"v\":1,\"given\":false}")
        assertFalse(h.capture.has(LogCode.STORAGE_STATE_RESET))
        assertFalse(h.capture.has(LogCode.STORAGE_FAILED))
    }

    @Test
    fun test_consentNeverGiven_falseBeforeInit_isNoWithdrawal_justWaits() {
        SallaPush.setConsentRequired(true)
        SallaPush.setConsentGiven(false)
        h.healthyServer()
        h.start()
        h.deliverToken()
        h.advance(30.0)
        assertTrue(h.net.calls.isEmpty())
        assertFalse(h.capture.has(LogCode.CONSENT_REVOKED))
        assertEquals(1, h.capture.count(LogCode.CONSENT_WAITING))
        assertEquals(listOf("sallapush/consent.json"), storedFiles())
    }
}
