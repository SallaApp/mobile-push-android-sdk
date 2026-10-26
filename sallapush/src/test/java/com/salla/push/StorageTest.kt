package com.salla.push

import com.salla.push.internal.CoreEnv
import com.salla.push.internal.CorePlatform
import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.Credentials
import com.salla.push.internal.engine.Desired
import com.salla.push.internal.engine.IdentityOp
import com.salla.push.internal.engine.IdentityOpKind
import com.salla.push.internal.engine.IdentityState
import com.salla.push.internal.engine.Retry
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.SecretString
import com.salla.push.internal.storage.CredentialStore
import com.salla.push.internal.storage.CredentialsRead
import com.salla.push.internal.storage.FileRead
import com.salla.push.internal.storage.GlobalStore
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.storage.IdentityCodec
import com.salla.push.internal.storage.KeystoreSecretBox
import com.salla.push.internal.storage.LastInit
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import com.salla.push.testing.keysList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageTest : SdkTestBase() {

    @Test
    fun test_filesUnderNoBackupDir() {
        val platform = CorePlatform.production(h.context, CoreEnv.production()) { null }
        val root = platform.files.root() ?: throw AssertionError("no root")
        assertEquals(File(h.context.noBackupFilesDir, "sallapush").canonicalPath, root.canonicalPath)
    }

    @Test
    fun test_layout_perAppAndHostTag() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val directory = h.appDirectory()
        assertEquals(setOf("identity.json", "state.json", "credentials.bin"), directory.list()?.filter { !it.startsWith(".") }?.toSet()?.minus("events.json"))
        assertTrue(File(h.root, "sallapush/last_init.json").readText().contains("\"app_id\":\"st_42_android\""))
        assertEquals(8, h.key.hostTag.length)
    }

    @Test
    fun test_hostTag_isStableAndHostScoped() {
        val default = HostTag.of("https://push.salla.dev")
        assertEquals(default, HostTag.of("HTTPS://PUSH.SALLA.DEV/"))
        assertEquals(default, HostTag.of("https://push.salla.dev/prefix"))
        assertNotEquals(default, HostTag.of("https://push.salla.dev:8443"))
        assertNotEquals(default, HostTag.of("https://push.example.com"))
        assertTrue(default?.matches(Regex("^[0-9a-f]{8}$")) == true)
        assertEquals("https://push.salla.dev:8443", HostTag.origin("https://push.salla.dev:8443/x"))
    }

    @Test
    fun test_corruptStateFile_keepsPendingLogout() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(2.0)
        File(h.appDirectory(), "state.json").writeText("{ not json")
        val next = h.relaunch(keepCalls = true)
        assertTrue(next.capture.has(LogCode.STORAGE_STATE_RESET))
        assertTrue(File(next.appDirectory(), "state.corrupt.json").isFile)
        next.advance(10.0)
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        assertEquals(0, next.net.calls(Routes.REGISTER).size)
        next.close()
    }

    @Test
    fun test_corruptIdentityFile_restartsUnknown_andTheAppsNextCallSetsItAgain() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        File(h.appDirectory(), "identity.json").writeText("[]")
        val next = h.relaunch()
        assertEquals(
            "SallaPush identity state was corrupt; it was reset (the app's next login or logout sets it again).",
            next.capture.of(LogCode.STORAGE_STATE_RESET).single().message,
        )
        assertEquals(Desired.UNKNOWN, next.engine().identity.desired)
        next.advance(15.0)
        // Nothing asks the server about the link: no update goes, and nothing else.
        assertFalse(next.capture.has(LogCode.UPDATE_SENT))
        assertTrue(next.net.calls.isEmpty())
        // The link is unknown: the app's logout unlinks.
        SallaPush.logout()
        next.advance(5.0)
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        assertEquals(ServerLink.NONE, next.engine().identity.serverLink)
        next.close()
    }

    @Test
    fun test_cryptoFailure_reRegisters() {
        h.registered()
        val next = h.relaunch(keepCalls = true) { it.secretBox.failOpen = true }
        assertTrue(next.capture.has(LogCode.STORAGE_CRYPTO_RESET))
        next.advance(5.0)
        val registers = next.net.calls(Routes.REGISTER)
        assertEquals(1, registers.size)
        assertEquals(1, next.secretBox.resets)
        next.close()
    }

    @Test
    fun test_sealFailure_credentialsInMemory_nextLaunchRegistersAgain() {
        h.secretBox.failSeal = true
        h.registered()
        val failure = h.capture.of(LogCode.STORAGE_FAILED).single()
        assertTrue(failure.message, failure.message.contains("credentials.bin"))
        // storage.crypto_reset means stored credentials could not be decrypted at load: not a failed write.
        assertFalse(h.capture.has(LogCode.STORAGE_CRYPTO_RESET))
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        val next = h.relaunch(keepCalls = true)
        next.advance(3.0)
        assertEquals(1, next.net.calls(Routes.REGISTER).size)
        next.close()
    }

    @Test
    fun test_credentialStore_rejectsOtherAppOrHost() {
        val files = SdkFiles { File(h.root, "sallapush") }
        val file = File(h.root, "sallapush/creds.bin")
        val box = com.salla.push.testing.FakeSecretBox()
        CredentialStore(files, box, file, "st_1", "https://a.example").save(Credentials(Http.DEVICE_ID, SecretString(Http.SECRET)))
        assertTrue(CredentialStore(files, box, file, "st_1", "https://a.example").load() is CredentialsRead.Found)
        assertTrue(CredentialStore(files, box, file, "st_1", "https://b.example").load() is CredentialsRead.Reset)
        assertFalse(file.exists())
        assertTrue(CredentialStore(files, box, file, "st_1", "https://a.example").load() is CredentialsRead.None)
    }

    @Test
    fun test_keystoreBox_withoutKeystore_failsWithoutThrowing() {
        val box = KeystoreSecretBox("sallapush_test_alias")
        assertNull(box.seal(byteArrayOf(1, 2, 3)))
        assertNull(box.open(ByteArray(40)))
        box.reset()
    }

    @Test
    fun test_atomicFiles_roundTripAndFailures() {
        val files = SdkFiles { File(h.root, "sallapush") }
        val file = File(h.root, "sallapush/a/b/state.json")
        assertTrue(files.read(file) is FileRead.Missing)
        assertTrue(files.write(file, "hello".encodeToByteArray()))
        assertEquals("hello", (files.read(file) as FileRead.Data).bytes.decodeToString())
        val blocked = File(h.root, "sallapush/blocked")
        blocked.writeText("x")
        assertFalse(files.write(File(blocked, "child.json"), "x".encodeToByteArray()))
        val directoryAsFile = File(h.root, "sallapush/dir.json")
        directoryAsFile.mkdirs()
        assertTrue(files.read(directoryAsFile) is FileRead.Unavailable)
    }

    @Test
    fun test_unreadableIdentity_runsWithoutNetworkRegistration() {
        h.registered()
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        val identity = File(next.appDirectory(), "identity.json")
        identity.delete()
        identity.mkdirs()
        next.install()
        next.healthyServer()
        next.start()
        next.deliverToken()
        SallaPush.User.setLanguage("en")
        next.advance(10.0)
        assertTrue(next.capture.has(LogCode.STORAGE_FAILED))
        assertTrue(next.net.calls.isEmpty())
        next.close()
    }

    /** identity.json holds what the server's routes need, and never a value the SDK could not have written. */
    @Test
    fun test_identityFile_roundTrips_andDropsWhatTheSdkCouldNotHaveWritten() {
        val state = IdentityState()
        state.desired = Desired.CUSTOMER
        state.desiredExternalId = Harness.CUSTOMER_ID
        state.serverLink = ServerLink.LINKED
        state.linkedExternalId = Harness.CUSTOMER_ID
        state.serverTags = ServerTags.SOME
        state.ops.add(IdentityOp(7, IdentityOpKind.LOGOUT, null, clearTags = true, sent = true, retry = Retry(nextAt = 1.0), tagKeys = listOf("plan")))
        state.ops.add(IdentityOp(8, IdentityOpKind.LOGIN, Harness.CUSTOMER_ID_2, clearTags = false, sent = false, retry = Retry()))
        val saved = IdentityCodec.encode(state, Harness.APP_ID, Routes.DEFAULT_BASE_URL) ?: throw AssertionError()
        val json = JSONObject(saved.decodeToString())
        assertEquals(
            setOf("v", "app_id", "api_base_url", "desired", "desired_external_id", "server_link", "linked_external_id", "server_tags", "ops"),
            json.keysList().toSet(),
        )
        assertEquals(
            setOf(
                "id", "kind", "external_id", "clear_tags", "tag_keys", "tags_cleared", "sent", "attempts", "next_at", "last_attempt_at",
                "last_failure", "dormant_until",
            ),
            json.getJSONArray("ops").getJSONObject(1).keysList().toSet(),
        )
        val loaded = IdentityCodec.decode(saved) ?: throw AssertionError()
        assertEquals(Desired.CUSTOMER, loaded.desired)
        assertEquals(Harness.CUSTOMER_ID, loaded.desiredExternalId)
        assertEquals(Harness.CUSTOMER_ID, loaded.linkedExternalId)
        assertEquals(ServerTags.SOME, loaded.serverTags)
        assertEquals(listOf(IdentityOpKind.LOGOUT, IdentityOpKind.LOGIN), loaded.ops.map { it.kind })
        assertEquals(Harness.CUSTOMER_ID_2, loaded.ops[1].externalId)
        assertEquals(listOf("plan"), loaded.ops[0].tagKeys)
        assertTrue(loaded.ops[0].clearsTagsNext)

        // A customer without a valid id reads as unknown; a login without one is skipped.
        for (invalid in listOf(JSONObject.NULL, "", "x".repeat(129))) {
            val bytes = JSONObject(json.toString()).put("desired_external_id", invalid)
            bytes.getJSONArray("ops").getJSONObject(1).put("external_id", invalid)
            val decoded = IdentityCodec.decode(bytes.toString().encodeToByteArray()) ?: throw AssertionError()
            assertEquals(Desired.UNKNOWN, decoded.desired)
            assertNull(decoded.desiredExternalId)
            assertEquals(listOf(IdentityOpKind.LOGOUT), decoded.ops.map { it.kind })
        }
        // An id kept while no customer is asserted, or no link is known, is dropped.
        val signedOut = JSONObject(json.toString()).put("desired", "guest").put("server_link", "none")
        val decoded = IdentityCodec.decode(signedOut.toString().encodeToByteArray()) ?: throw AssertionError()
        assertNull(decoded.desiredExternalId)
        assertNull(decoded.linkedExternalId)
    }

    // ── A file that cannot be read is never replaced by an empty state ──

    @Test
    fun test_unreadableIdentityFile_staysByteIdentical_andItsPendingLogoutIsSentOnceReadable() {
        val pending = launchWithAPendingLogout()
        val next = copyOf(h)
        val identity = File(next.appDirectory(), "identity.json")
        val state = File(next.appDirectory(), "state.json")
        val identityBytes = identity.readBytes()
        val stateBytes = state.readBytes()
        assertTrue(identity.setReadable(false))
        try {
            exerciseALaunch(next)
            assertTrue(next.capture.has(LogCode.STORAGE_FAILED))
            assertTrue(next.net.calls.isEmpty())
        } finally {
            identity.setReadable(true)
        }
        assertTrue(identityBytes.contentEquals(identity.readBytes()))
        assertTrue(stateBytes.contentEquals(state.readBytes()))
        assertTrue(pending.contains("\"kind\":\"logout\""))
        next.close()

        val readable = copyOf(h)
        readable.start()
        readable.deliverToken()
        readable.advance(5.0)
        assertEquals("{}", readable.net.calls(Routes.UNLINK).single().body.toString())
        readable.close()
    }

    @Test
    fun test_unreadableStateFile_staysByteIdentical_keepingItsDeviceAndLastDeviceId() {
        launchWithAPendingLogout()
        val next = copyOf(h)
        val identity = File(next.appDirectory(), "identity.json")
        val state = File(next.appDirectory(), "state.json")
        val identityBytes = identity.readBytes()
        val stateBytes = state.readBytes()
        assertTrue(state.setReadable(false))
        try {
            exerciseALaunch(next)
            assertTrue(next.net.calls.isEmpty())
        } finally {
            state.setReadable(true)
        }
        assertTrue(identityBytes.contentEquals(identity.readBytes()))
        assertTrue(stateBytes.contentEquals(state.readBytes()))
        assertTrue(state.readText().contains(Http.DEVICE_ID))
        next.close()
    }

    @Test
    fun test_unreadableEventsFile_isNeverWritten_whileTheRestWorks() {
        h.registered()
        h.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        SallaPush.Notifications.handleFirebaseMessage(h.context, com.salla.push.testing.Messages.remote(com.salla.push.testing.Messages.native(id = "msg_a1")))
        h.advance(6.0)
        val next = copyOf(h)
        val events = File(next.appDirectory(), "events.json")
        val eventsBytes = events.readBytes()
        assertTrue(events.setReadable(false))
        try {
            next.start()
            next.deliverToken()
            SallaPush.Notifications.handleFirebaseMessage(next.context, com.salla.push.testing.Messages.remote(com.salla.push.testing.Messages.native(id = "msg_b2")))
            SallaPush.User.setLanguage("en")
            next.advance(10.0)
            assertEquals(1, next.net.calls(Routes.DEVICE).size)
            assertEquals(1, next.net.calls(Routes.EVENTS).size)
            next.engine().stop()
        } finally {
            events.setReadable(true)
        }
        assertTrue(eventsBytes.contentEquals(events.readBytes()))
        next.close()
    }

    @Test
    fun test_writeFailures_logStorageFailedOncePerFile_untilAWriteSucceeds() {
        h.registered()
        val blocker = File(h.appDirectory(), "state.json.new")
        File(blocker, "x").mkdirs()
        repeat(20) { index ->
            SallaPush.User.addTag("k$index", "v")
            h.settle()
        }
        assertEquals(listOf("state.json"), h.capture.of(LogCode.STORAGE_FAILED).map { Regex("\\(([a-z.]+)\\)").find(it.message)?.groupValues?.get(1) })
        blocker.deleteRecursively()
        SallaPush.User.addTag("ok", "1")
        h.settle()
        assertTrue(h.stateJson().contains("\"ok\""))
        File(blocker, "x").mkdirs()
        SallaPush.User.addTag("again", "1")
        h.settle()
        assertEquals(2, h.capture.count(LogCode.STORAGE_FAILED))
    }

    @Test
    fun test_credentialsWriteFailure_isAnErrorStorageFailed_oncePerFile_untilAWriteSucceeds() {
        h.secretBox.failSeal = true
        h.registered()
        val failure = h.capture.of(LogCode.STORAGE_FAILED).single()
        assertEquals(SallaPushLogLevel.ERROR, failure.code.level)
        assertEquals("Could not write SallaPush state (credentials.bin); the device credentials are kept in memory, and the next launch registers again.", failure.message)
        assertTrue(h.console.any { it.startsWith("ERROR storage.failed:") && it.contains("credentials.bin") })

        // Another failed write of the same file is not logged again.
        h.net.on("PUT", Routes.DEVICE) { Http.error(401, "invalid_device_secret") }
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2) }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertEquals(1, h.capture.count(LogCode.STORAGE_FAILED))

        // A write that succeeds re-arms it.
        h.secretBox.failSeal = false
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID) }
        SallaPush.User.setLanguage("fr")
        h.advance(3700.0)
        assertEquals(3, h.net.calls(Routes.REGISTER).size)
        assertTrue(File(h.appDirectory(), "credentials.bin").isFile)
        h.secretBox.failSeal = true
        SallaPush.User.setLanguage("de")
        h.advance(7300.0)
        assertEquals(4, h.net.calls(Routes.REGISTER).size)
        assertEquals(2, h.capture.count(LogCode.STORAGE_FAILED))
    }

    // ── last_init.json is written only when it changes (near-zero launch cost) ──

    @Test
    fun test_lastInit_roundTrip_withAndWithoutTheBadgeSetting() {
        val store = GlobalStore(h.files)
        assertTrue(store.saveLastInit(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, "warn", false)))
        assertEquals(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, "warn", false), store.loadLastInit())
        assertTrue(store.saveLastInit(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, null, true)))
        assertEquals(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, null, true), store.loadLastInit())
        assertTrue(store.saveLastInit(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, "info", null)))
        assertEquals(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, "info", null), store.loadLastInit())
        // As a version without the setting wrote it: no auto_update_badge.
        File(h.root, "sallapush/last_init.json").writeText(
            """{"v":1,"app_id":"${Harness.APP_ID}","api_base_url":"${Routes.DEFAULT_BASE_URL}","log_level":"warn"}""",
        )
        assertEquals(LastInit(Harness.APP_ID, Routes.DEFAULT_BASE_URL, "warn", null), store.loadLastInit())
        // A value that isn't a boolean counts as none.
        File(h.root, "sallapush/last_init.json").writeText(
            """{"v":1,"app_id":"${Harness.APP_ID}","api_base_url":"${Routes.DEFAULT_BASE_URL}","auto_update_badge":"false"}""",
        )
        assertNull(store.loadLastInit()?.autoUpdateBadge)
    }

    @Test
    fun test_lastInit_isNotRewrittenAtALaunchThatChangesNothing_andIsWhenTheLevelChanges() {
        h.registered()
        assertTrue(File(h.root, "sallapush/last_init.json").readText().contains("\"log_level\":\"warn\""))
        val old = 1_000_000_000_000L
        val same = h.relaunch { next -> assertTrue(File(next.root, "sallapush/last_init.json").setLastModified(old)) }
        val written = File(same.root, "sallapush/last_init.json")
        assertEquals("a launch with the same app id, API base URL and level writes nothing", old, written.lastModified())

        val verbose = Harness(h.context)
        File(same.root, "sallapush").copyRecursively(File(verbose.root, "sallapush"), overwrite = true)
        verbose.install()
        verbose.healthyServer()
        verbose.start(config = SallaPushConfig.Builder().setLogLevel(SallaPushLogLevel.VERBOSE).build())
        assertTrue(File(verbose.root, "sallapush/last_init.json").readText().contains("\"log_level\":\"verbose\""))
        same.close()
        verbose.close()
    }

    /** Registered, logged in with a tag, then a logout that cannot reach the server: identity.json holds it. */
    private fun launchWithAPendingLogout(): String {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(2.0)
        return h.identityJson()
    }

    /** The next launch of the app on the same phone: the launch contract's calls, a token, then the end of the process. */
    private fun exerciseALaunch(next: Harness) {
        next.start()
        next.deliverToken()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        SallaPush.logout()
        SallaPush.User.addTag("plan", "silver")
        next.advance(70.0)
        for (engine in next.core.registry.all()) engine.stop()
    }

    private fun copyOf(from: Harness): Harness {
        val next = Harness(from.context)
        File(from.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = from.clock.millis
        next.install()
        next.healthyServer()
        return next
    }

    @Test
    fun test_futureTimesInFiles_areClamped() {
        h.registered()
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        SallaPush.logout()
        h.advance(2.0)
        val identity = File(h.appDirectory(), "identity.json")
        identity.writeText(identity.readText().replace(Regex("\"next_at\":[0-9.E]+"), "\"next_at\":9.9E12"))
        val next = h.relaunch(keepCalls = true)
        next.advance(10.0)
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        next.close()
    }
}
