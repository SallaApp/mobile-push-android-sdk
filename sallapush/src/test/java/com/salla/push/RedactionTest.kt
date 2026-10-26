package com.salla.push

import android.Manifest
import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Redact
import com.salla.push.internal.log.SecretString
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File

class RedactionTest : SdkTestBase() {

    @Test
    fun test_secretsNeverInLogsListenersOrFiles_acrossAFullSession() {
        shadowOf(h.context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SallaPush.Debug.logLevel = SallaPushLogLevel.VERBOSE
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_aa")))
        h.net.on("POST", Routes.LINK) { Http.error(503, "server_error", message = "secret ${Harness.CUSTOMER_TOKEN}\nnext") }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.advance(5.0)
        SallaPush.logout()
        h.advance(10.0)

        val files = File(h.root, "sallapush").walkTopDown().filter { it.isFile && it.name != "credentials.bin" }
            .joinToString("\n") { it.readText() }
        val logs = h.capture.allText() + "\n" + h.console.joinToString("\n")
        for (secret in listOf(Http.SECRET, Harness.CUSTOMER_TOKEN, Harness.CUSTOMER_TOKEN_2, Harness.FCM_TOKEN)) {
            assertFalse("log leaked a secret", logs.contains(secret))
        }
        for (secret in listOf(Http.SECRET, Harness.CUSTOMER_TOKEN, Harness.CUSTOMER_TOKEN_2)) {
            assertFalse("file leaked a secret", files.contains(secret))
        }
        assertFalse(logs.contains("1001"))
        assertTrue(logs.contains(Redact.pushToken(Harness.FCM_TOKEN)))
        assertTrue(h.capture.has(LogCode.TOKEN_RECEIVED))
    }

    @Test
    fun test_secretString_printsRedacted() {
        val secret = SecretString("top-secret")
        assertEquals("<redacted>", secret.toString())
        assertEquals("<redacted>", "$secret")
        assertEquals("top-secret", secret.reveal())
        assertEquals("…klmnop", Redact.pushToken("abcdefghijklmnop"))
        assertEquals("none", Redact.pushToken(null))
    }

    @Test
    fun test_metadataKeys_limitedToTheCatalogue() {
        h.logger.log(LogCode.NET_RETRY, mapOf("route" to "/devices/{id}", "token" to "abc", "request_id" to "r1")) { "x" }
        val record = h.capture.of(LogCode.NET_RETRY).single()
        assertEquals(setOf("route", "request_id"), record.metadata.keys)
    }
}
