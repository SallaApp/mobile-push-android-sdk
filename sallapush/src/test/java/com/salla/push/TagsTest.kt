package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import com.salla.push.testing.keysList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TagsTest : SdkTestBase() {

    @Test
    fun test_tagsAreNormalized_mergedAndSentWithPatch_after10sDebounce() {
        h.registered()
        // The server holds promo, so its removal is sent with the additions.
        SallaPush.User.addTag("promo", "spring")
        h.advance(11.0)
        assertEquals(mapOf("promo" to "spring"), h.serverTags)
        h.net.clear()
        SallaPush.User.addTag("ADD_TO_CART", "1790000000000")
        SallaPush.User.addTags(mapOf(" Last_Login " to "x", "plan" to "gold"))
        SallaPush.User.removeTag("promo")
        h.advance(9.0)
        assertEquals(0, h.net.calls(Routes.TAGS).size)
        h.advance(2.0)
        val calls = h.net.calls(Routes.TAGS)
        assertEquals(1, calls.size)
        assertEquals("PATCH", calls[0].method)
        val body = calls[0].body ?: throw AssertionError()
        val add = body.getJSONObject("add")
        assertEquals(setOf("add_to_cart", "last_login", "plan"), add.keysList().toSet())
        assertEquals("promo", body.getJSONArray("remove").getString(0))
        assertTrue(h.capture.has(LogCode.TAGS_NORMALIZED))
        assertTrue(h.capture.has(LogCode.TAGS_FLUSHED))
        assertEquals(mapOf("add_to_cart" to "1790000000000", "last_login" to "x", "plan" to "gold"), SallaPush.User.getTags())
    }

    @Test
    fun test_invalidKey_droppedWithWarning() {
        h.registered()
        SallaPush.User.addTag("first name", "x")
        SallaPush.User.addTag("", "x")
        SallaPush.User.addTag("ok", "1")
        h.advance(11.0)
        assertTrue(h.capture.has(LogCode.TAGS_INVALID_KEY))
        assertEquals(setOf("ok"), h.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.keysList()?.toSet())
    }

    /**
     * An empty or blank key is an invalid argument of every tag call, as addTag and the iOS SDK say
     * (WARN api.invalid_argument), never a tags.invalid_key; the call's other entries go on.
     */
    @Test
    fun test_emptyOrBlankKeys_areInvalidArguments_inEveryTagCall_andTheOtherEntriesGoOn() {
        h.registered()
        SallaPush.User.addTag("ok", "1")
        h.advance(11.0)
        h.capture.clear()
        SallaPush.User.addTag("  ", "x")
        SallaPush.User.addTags(mapOf("" to "1"))
        SallaPush.User.addTags(mapOf("" to "1", " " to "2"))
        SallaPush.User.addTags(mapOf(" " to "1", "plan" to "gold"))
        SallaPush.User.removeTag("")
        SallaPush.User.removeTags(listOf("", "\t"))
        SallaPush.User.removeTags(listOf(" ", "ok"))
        h.advance(11.0)
        val ignored = h.capture.of(LogCode.API_INVALID_ARGUMENT)
        assertEquals(
            listOf(
                "addTag ignored: the key is empty.",
                "addTags ignored: the key is empty.",
                "addTags ignored: the keys are empty.",
                "addTags ignored 1 entry: the key is empty.",
                "removeTag ignored: the key is empty.",
                "removeTags ignored: the keys are empty.",
                "removeTags ignored 1 entry: the key is empty.",
            ),
            ignored.map { it.message },
        )
        assertEquals(listOf("1", "1", "2", "1", "1", "2", "1"), ignored.map { it.metadata[Meta.COUNT] })
        assertTrue(ignored.all { it.code.level == SallaPushLogLevel.WARN })
        assertFalse(h.capture.has(LogCode.TAGS_INVALID_KEY))
        val body = h.net.calls(Routes.TAGS).last().body ?: throw AssertionError("no tags request")
        assertEquals(setOf("plan"), body.getJSONObject("add").keysList().toSet())
        assertEquals("ok", body.getJSONArray("remove").getString(0))
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
    }

    /**
     * The same rule as the iOS SDK: a tags request that got no usable answer may have changed the
     * server's tags, so the mirror no longer stands for them. Setting a value back is then sent, never taken as held.
     */
    @Test
    fun test_aTagsRequestWithoutAUsableAnswer_leavesTheServersTagsUnknown_soTheSameValueIsSentAgain() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)
        // The server applies the removal, but no answer arrives.
        h.net.on("PATCH", Routes.TAGS) { call ->
            h.tagsAfter(call)
            Http.timeout()
        }
        SallaPush.User.removeTag("plan")
        h.advance(11.0)
        assertTrue(h.serverTags.isEmpty())
        assertEquals(ServerTags.UNKNOWN, h.engine().identity.serverTags)
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        SallaPush.User.addTag("plan", "gold")
        h.advance(60.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
    }

    @Test
    fun test_emptyValue_removesTag() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        SallaPush.User.addTag("plan", "")
        h.advance(11.0)
        val body = h.net.calls(Routes.TAGS).last().body ?: throw AssertionError()
        assertFalse(body.has("add"))
        assertEquals("plan", body.getJSONArray("remove").getString(0))
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    // ── A tag change the server already reflects sends nothing (the iOS SDK's rules) ──

    @Test
    fun test_addingTheValueTheServerHolds_sendsNothing_andDropsAPendingChangeOfTheKey() {
        h.registered()
        SallaPush.User.addTags(mapOf("plan" to "gold", "city" to "riyadh"))
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)
        SallaPush.User.addTags(mapOf("plan" to "gold", "city" to "riyadh"))
        // A change of a key, then its value as the server holds it: the pending change goes, and nothing is sent.
        SallaPush.User.addTag("plan", "silver")
        SallaPush.User.addTag("plan", "gold")
        SallaPush.User.removeTag("city")
        SallaPush.User.addTag("city", "riyadh")
        h.advance(30.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertTrue(h.engine().device.tags.pendingAdd.isEmpty())
        assertTrue(h.engine().device.tags.pendingRemove.isEmpty())
        assertTrue(h.engine().device.ops.none { it.kind == com.salla.push.internal.engine.DeviceOpKind.TAGS })
        assertEquals(mapOf("plan" to "gold", "city" to "riyadh"), SallaPush.User.getTags())
    }

    @Test
    fun test_aChangeTakenBackWhileAnotherRequestIsOnTheWire_leavesNoTagsOpBehind() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        // An update is on the wire, so the engine does not look at its other ops until it is answered.
        h.net.on("PUT", Routes.DEVICE) { null }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        val update = h.net.held().single()
        SallaPush.User.addTag("plan", "silver")
        SallaPush.User.addTag("plan", "gold")
        h.settle()
        // Nothing is left to send: the tags op goes at once, not when the engine next looks at its ops.
        assertTrue(h.engine().device.ops.none { it.kind == com.salla.push.internal.engine.DeviceOpKind.TAGS })
        update.respond(Http.empty())
        h.advance(30.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_removingATagTheServerCannotHold_sendsNothing() {
        // A row registered fresh holds no tags at all.
        h.registered()
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
        SallaPush.User.removeTag("promo")
        SallaPush.User.removeTags(listOf("a", "b"))
        SallaPush.User.addTag("gone", "")
        h.advance(30.0)
        assertTrue(h.net.calls(Routes.TAGS).isEmpty())
        assertTrue(h.engine().device.tags.pendingRemove.isEmpty())
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_staticTagsSetAtEveryLaunch_costNoRequest() {
        h.registered()
        SallaPush.User.addTags(mapOf("store_theme" to "dark", "platform" to "android"))
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        repeat(3) {
            val next = h.relaunch(keepCalls = true) {
                SallaPush.User.addTags(mapOf("store_theme" to "dark", "platform" to "android"))
            }
            next.advance(60.0)
            next.background()
            assertEquals(emptyList<String>(), next.net.calls.map { "${it.method} ${it.route}" })
            assertEquals(mapOf("store_theme" to "dark", "platform" to "android"), SallaPush.User.getTags())
            h.close()
            h = next
        }
    }

    @Test
    fun test_theSameValueAsThePreviousCustomers_setForTheNextCustomerDuringTheSwitch_isStillSent() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        assertEquals(mapOf("tier" to "gold"), h.serverTags)
        // The switch drops A's tags: the mirror no longer says what the server will hold for B.
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(2.0)
        val login = h.net.held().single()
        SallaPush.User.addTag("tier", "gold")
        login.respond(Http.empty())
        h.advance(15.0)
        assertEquals("gold", h.net.calls(Routes.TAGS).last().body?.getJSONObject("add")?.getString("tier"))
        assertEquals(mapOf("tier" to "gold"), h.serverTags)
        assertEquals(mapOf("tier" to "gold"), SallaPush.User.getTags())
    }

    @Test
    fun test_aRemoveOfATagThatARequestOnTheWireAdds_isStillSent() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("promo", "spring")
        h.advance(11.0)
        val first = h.net.held().single()
        // The server held no tags when the request left, but it will hold this one: the remove must follow.
        SallaPush.User.removeTag("promo")
        first.respond(Http.tags(h.tagsAfter(first)))
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        h.advance(15.0)
        assertEquals("promo", h.net.calls(Routes.TAGS).last().body?.getJSONArray("remove")?.getString(0))
        assertTrue(h.serverTags.isEmpty())
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_anAddRevertingAValueThatARequestOnTheWireChanges_isStillSent() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("plan", "silver")
        h.advance(11.0)
        val second = h.net.held().single()
        // The mirror still says gold, but the request on the wire makes it silver: going back to gold must be sent.
        SallaPush.User.addTag("plan", "gold")
        second.respond(Http.tags(h.tagsAfter(second)))
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        h.advance(15.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        assertEquals(3, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_a429WaitOfTags_holdsForAChangeMadeAfterNothingWasLeftToSend() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        h.net.on("PATCH", Routes.TAGS) { Http.error(429, "rate_limited", retryAfter = "120") }
        SallaPush.User.addTag("plan", "silver")
        h.advance(11.0)
        assertEquals(2, h.net.calls(Routes.TAGS).size)
        // Back to what the server holds (nothing left to send), then a new change: it waits for the 429 too.
        SallaPush.User.addTag("plan", "gold")
        SallaPush.User.addTag("city", "riyadh")
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        h.advance(60.0)
        assertEquals(2, h.net.calls(Routes.TAGS).size)
        h.advance(70.0)
        assertEquals(3, h.net.calls(Routes.TAGS).size)
        assertEquals(mapOf("plan" to "gold", "city" to "riyadh"), h.serverTags)
    }

    @Test
    fun test_theServersValue_setAgainWhileTheDeviceRegistersAgain_isStillSent() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)
        // The secret is refused: until the new registration is answered, the row the tags will reach is not known.
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        h.net.on("POST", Routes.REGISTER) { null }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        val register = h.net.held().single()
        SallaPush.User.addTag("plan", "gold")
        // It is a fresh row, which holds no tags.
        h.serverTags.clear()
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        register.respond(Http.registered(deviceId = Http.DEVICE_ID_2))
        h.advance(15.0)
        assertEquals(2, h.net.calls(Routes.TAGS).size)
        assertEquals("gold", h.net.calls(Routes.TAGS).last().body?.getJSONObject("add")?.getString("plan"))
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
    }

    @Test
    fun test_aRemoveWhileTheDeviceRegistersAgain_needsNoRequest_onTheNewRow() {
        h.registered()
        SallaPush.User.addTag("promo", "spring")
        h.advance(11.0)
        assertEquals(mapOf("promo" to "spring"), h.serverTags)
        // The secret is refused: the device registers again, and the row the tags will reach is not known meanwhile.
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        h.net.on("POST", Routes.REGISTER) { null }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        val register = h.net.held().single()
        SallaPush.User.removeTag("promo")
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        // Every registration answer is a new row, which holds no tags: the removal has nothing to remove there.
        h.serverTags.clear()
        register.respond(Http.registered(deviceId = Http.DEVICE_ID_2))
        h.advance(15.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertTrue(h.engine().device.tags.pendingRemove.isEmpty())
        assertTrue(h.serverTags.isEmpty())
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_appTagKeys_areShownWithoutControlCharacters() {
        h.registered()
        SallaPush.User.addTag("bad\u0007key\nINFO forged.line: x", "1")
        SallaPush.User.addTag(" Plan\u200B ", "gold")
        h.advance(1.0)
        val invalid = h.capture.of(LogCode.TAGS_INVALID_KEY).map { it.message }
        assertEquals(2, invalid.size)
        assertEquals("Tag key \"badkey INFO forged.line: x\" is invalid after lowercasing (a-z, 0-9, _; max 64); dropped.", invalid[0])
        assertTrue(invalid.all { message -> message.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } })
        assertTrue(h.console.toString(), h.console.contains("WARN tags.invalid_key: ${invalid[0]}"))
        SallaPush.User.addTag("  Last_Login\t", "x")
        h.advance(1.0)
        assertEquals("Tag key Last_Login sent as last_login.", h.capture.of(LogCode.TAGS_NORMALIZED).single().message)
    }

    @Test
    fun test_valueTruncatedTo255Utf16Units() {
        h.registered()
        SallaPush.User.addTag("note", "x".repeat(300))
        h.advance(11.0)
        assertEquals(255, h.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("note")?.length)
        assertTrue(h.capture.has(LogCode.TAGS_VALUE_TRUNCATED))
    }

    @Test
    fun test_limit_extraAddsDropped() {
        h.registered()
        val many = (1..103).associate { "k$it" to "v" }
        SallaPush.User.addTags(many)
        h.advance(11.0)
        assertEquals(100, h.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.length())
        assertTrue(h.capture.has(LogCode.TAGS_LIMIT))
        assertEquals("3", h.capture.of(LogCode.TAGS_LIMIT).first().metadata["count"])
    }

    @Test
    fun test_backgroundFlushesTagsAtOnce() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.advance(1.0)
        h.background()
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_400OnMergedOp_offendingKeyDropped_validKeysKept() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { call ->
            val add = call.body?.optJSONObject("add")
            if (add != null && add.has("blocked")) Http.error(400, "invalid_request", message = "Tag limit exceeded (max 100)") else Http.tags(h.tagsAfter(call))
        }
        SallaPush.User.addTags(mapOf("plan" to "gold", "blocked" to "x", "city" to "riyadh"))
        h.advance(11.0)
        h.advance(5.0)
        assertTrue(h.capture.has(LogCode.TAGS_REJECTED_KEY))
        val tags = SallaPush.User.getTags()
        assertEquals("gold", tags["plan"])
        assertEquals("riyadh", tags["city"])
        assertFalse(tags.containsKey("blocked"))
        assertTrue(h.engine().device.tags.pendingAdd.isEmpty())
        assertTrue(h.net.calls(Routes.TAGS).size >= 4)
    }

    @Test
    fun test_logoutClearsTagMirrorAndPendingChanges() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        SallaPush.User.addTag("pending", "1")
        SallaPush.logout()
        h.advance(15.0)
        assertTrue(SallaPush.User.getTags().isEmpty())
        // The unsent change is dropped; the logout's clearing removes the tag the server holds.
        assertEquals(2, h.net.calls(Routes.TAGS).size)
        assertEquals("""{"remove":["plan"]}""", h.net.calls(Routes.TAGS).last().body.toString())
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertTrue(h.serverTags.isEmpty())
    }

    @Test
    fun test_clearTagsOnLogoutFalse_keepsTags() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setClearTagsOnLogout(false).build())
        h.deliverToken()
        h.advance(2.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        SallaPush.logout()
        h.advance(5.0)
        assertEquals("gold", SallaPush.User.getTags()["plan"])
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
    }

    @Test
    fun test_switchDropsPreviousCustomersUnsentTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("a_only", "1")
        h.advance(12.0)
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(30.0)
        assertFalse(SallaPush.User.getTags().containsKey("a_only"))
        assertTrue(h.net.calls(Routes.TAGS).none { it.body?.optJSONObject("add")?.has("a_only") == true && it.completed && h.engine().device.tags.mirror.containsKey("a_only") })
    }

    @Test
    fun test_tagsInFlightAtLogout_neverRestoreThePreviousCustomersTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("add_to_cart", "1")
        h.advance(15.0)
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("first_name", "Customer A")
        h.advance(11.0)
        val inFlight = h.net.held().single()
        SallaPush.logout()
        h.settle()
        assertTrue(SallaPush.User.getTags().isEmpty())

        // The tags answer lands before the logout's own requests: customer A's tags must not come back meanwhile, neither
        // through getTags() nor in state.json. The logout clears them, the one on the wire included, then unlinks.
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        h.net.on("POST", Routes.UNLINK) { null }
        inFlight.respond(Http.tags(h.tagsAfter(inFlight)))
        h.advance(2.0)
        assertEquals("""{"remove":["add_to_cart","first_name"]}""", h.net.calls(Routes.TAGS).last().body.toString())
        assertTrue(h.serverTags.isEmpty())
        val logout = h.net.held().single()
        assertEquals(Routes.UNLINK, logout.route)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertTrue(h.engine().device.tags.mirror.isEmpty())
        assertFalse(h.stateJson().contains("Customer A"))

        h.serverExternalId = null
        logout.respond(Http.empty())
        h.advance(5.0)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertTrue(h.engine().device.tags.mirror.isEmpty())
        assertFalse(h.stateJson().contains("Customer A"))

        // A purged row is re-registered and its tag mirror replayed: none of customer A's tags go to the new row.
        h.net.on("PUT", Routes.DEVICE) { Http.error(404, "unknown_device") }
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2) }
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        SallaPush.User.setLanguage("en")
        h.advance(30.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertTrue(h.net.calls(Routes.TAGS).none { it.completed && it.body?.optJSONObject("add")?.has("first_name") == true && it !== inFlight })
    }

    @Test
    fun test_logoutWhileTheFirstTagsAreOnTheWire_dropsThemFromTheGetters_andSendsNoUnlink() {
        h.registered()
        assertEquals(ServerTags.NONE, h.engine().identity.serverTags)
        h.net.on("PATCH", Routes.TAGS) { null }
        SallaPush.User.addTag("first_name", "Customer A")
        h.advance(11.0)
        val inFlight = h.net.held().single()
        // A login that never left the device, then a logout: no link, but the server is about to hold a tag.
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.logout()
        h.settle()
        inFlight.respond(Http.tags(h.tagsAfter(inFlight)))
        h.advance(5.0)
        assertEquals(0, h.net.calls(Routes.LINK).size)
        assertEquals(0, h.net.calls(Routes.UNLINK).size)
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_switchedWithoutClearing_keepsTheTags() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setClearTagsOnLogout(false).build())
        h.deliverToken()
        h.advance(2.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals("gold", SallaPush.User.getTags()["plan"])
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        h.advance(3.0)
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        assertEquals(ServerTags.SOME, h.engine().identity.serverTags)
        assertEquals(
            "Account switched; this device keeps its tags (clearTagsOnLogout is off).",
            h.capture.of(LogCode.LOGIN_SWITCHED).single().message,
        )
    }

    @Test
    fun test_switchedWithClearing_dropsThePreviousCustomersTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        assertTrue(SallaPush.User.getTags().isEmpty())
        h.advance(3.0)
        assertEquals(Harness.CUSTOMER_ID_2, h.serverExternalId)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertTrue(h.serverTags.isEmpty())
        assertEquals(
            "Account switched; the previous customer's tags are cleared before the new customer is linked.",
            h.capture.of(LogCode.LOGIN_SWITCHED).single().message,
        )
    }

    // ── In the foreground, tags go out at most every 10 s ──

    @Test
    fun test_aTagChangedWhileATagsRequestIsOnTheWire_waitsTenSecondsAfterThatRequest_inTheForeground() {
        h.registered()
        var held: com.salla.push.testing.FakeCall? = null
        h.net.on("PATCH", Routes.TAGS) { call ->
            if (held == null) {
                held = call
                null
            } else {
                Http.tags(h.tagsAfter(call))
            }
        }
        SallaPush.User.addTag("a", "1")
        h.advance(10.5)
        val first = held ?: throw AssertionError("no tags request")
        val sentAt = h.clock.millis - 500
        SallaPush.User.addTag("b", "2")
        h.advance(3.0)
        first.respond(Http.tags(h.tagsAfter(first)))
        h.advance(0.5)
        assertEquals("one tags request per 10 s in the foreground", 1, h.net.calls(Routes.TAGS).size)
        h.advance((sentAt + 10_000 - h.clock.millis) / 1000.0 - 0.1)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        h.advance(0.2)
        val second = h.net.calls(Routes.TAGS)
        assertEquals(2, second.size)
        assertEquals("2", second[1].body?.getJSONObject("add")?.getString("b"))
        assertEquals(mapOf("a" to "1", "b" to "2"), SallaPush.User.getTags())
    }

    @Test
    fun test_aTagChangedWhileATagsRequestIsOnTheWire_goesRightAfterIt_inTheBackground() {
        h.registered()
        var held: com.salla.push.testing.FakeCall? = null
        h.net.on("PATCH", Routes.TAGS) { call ->
            if (held == null) {
                held = call
                null
            } else {
                Http.tags(h.tagsAfter(call))
            }
        }
        SallaPush.User.addTag("a", "1")
        h.background()
        val first = held ?: throw AssertionError("no tags request")
        SallaPush.User.addTag("b", "2")
        h.settle()
        first.respond(Http.tags(h.tagsAfter(first)))
        h.settle()
        assertEquals(2, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_aTagsAnswerWithNothingLeft_endsTheTagsOp() {
        h.registered()
        SallaPush.User.addTag("a", "1")
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertTrue(h.engine().device.ops.none { it.kind == com.salla.push.internal.engine.DeviceOpKind.TAGS })
    }

    @Test
    fun test_aCrashBetweenTheWritesOfASwitchingLogin_neverSendsThePreviousCustomersTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals(Harness.CUSTOMER_ID, h.serverExternalId)
        // Customer A's tag change waits for the 10 s foreground debounce when the app switches to customer B.
        SallaPush.User.addTag("a_only", "1")
        h.settle()
        // The process dies right after the first file write of the switching login's task: the next launch starts
        // from what reached the disk by then.
        val crashImage = File(h.root, "crash-image")
        var writes = 0
        h.onFileAccess = {
            writes += 1
            if (writes == 2) File(h.root, "sallapush").copyRecursively(File(crashImage, "sallapush"), overwrite = true)
        }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.settle()
        h.onFileAccess = null
        assertEquals("the switching login's task writes state.json and identity.json", 2, writes)

        val next = Harness(h.context)
        File(crashImage, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 60_000L
        next.serverExternalId = Harness.CUSTOMER_ID
        next.install()
        next.healthyServer()
        // The launch contract: the app, signed in as B, asserts B again, here before initWithContext. The relaunched
        // engine then sends that login first, and the tag changes wait behind it.
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        next.start()
        next.deliverToken()
        next.advance(60.0)
        assertEquals(1, next.net.calls(Routes.LINK).size)
        assertEquals(Routes.LINK, next.net.calls.first { it.route == Routes.LINK || it.route == Routes.TAGS }.route)
        // Customer A's tag change, dropped by the switch before its op reached the disk, never reaches the device
        // linked to customer B.
        assertTrue(next.net.calls.none { it.route == Routes.TAGS && it.body?.optJSONObject("add")?.has("a_only") == true })
        assertFalse(next.serverTags.containsKey("a_only"))
        assertFalse(SallaPush.User.getTags().containsKey("a_only"))
        next.close()
    }
}
