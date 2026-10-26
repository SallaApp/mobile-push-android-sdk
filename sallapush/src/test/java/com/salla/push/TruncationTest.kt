package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogSanitizer
import com.salla.push.internal.util.cleanField
import com.salla.push.internal.util.truncatedUtf16
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TruncationTest : SdkTestBase() {

    /** "Arabic with harakat": every letter carries a combining mark, so graphemes and UTF-16 units differ. */
    private val arabic = "مُحَمَّد"
    private val emoji = "😀"

    @Test
    fun test_arabicWithHarakat_at254_255_256Units() {
        val text = arabic.repeat(40)
        for (limit in listOf(254, 255, 256)) {
            val cut = truncatedUtf16(text, limit)
            assertEquals(limit, cut.length)
            assertTrue(text.startsWith(cut))
        }
    }

    @Test
    fun test_surrogatePairAtTheBoundary_isNeverSplit() {
        val text = "a".repeat(254) + emoji + "b"
        assertEquals(256, (("a".repeat(254)) + emoji).length)
        val cut = truncatedUtf16(text, 255)
        assertEquals(254, cut.length)
        assertTrue(cut.all { !Character.isSurrogate(it) })
        assertEquals("a".repeat(254) + emoji, truncatedUtf16(text, 256))
        assertEquals("", truncatedUtf16(emoji, 1))
        assertEquals(emoji, truncatedUtf16(emoji, 2))
    }

    @Test
    fun test_cleanField_trimsTruncatesAndOmitsEmpty() {
        assertNull(cleanField("   ", 10))
        assertNull(cleanField(null, 10))
        assertEquals("abc", cleanField("  abc  ", 10))
        assertEquals("ab", cleanField("abc", 2))
    }

    @Test
    fun test_registerMetadata_isTruncatedInUtf16Units() {
        h.device.model = emoji.repeat(40)
        h.device.appVersion = "1.2.3-" + "x".repeat(40)
        h.device.zone = "Zone/" + "y".repeat(80)
        h.registered()
        val body = h.net.calls(Routes.REGISTER).single().body ?: throw AssertionError()
        assertEquals(64, body.getString("device_model").length)
        assertTrue(body.getString("device_model").all { it.isSurrogate() })
        assertTrue(body.getString("app_version").length <= 32)
        assertTrue(body.getString("timezone").length <= 64)
    }

    @Test
    fun test_tagValues_truncatedTo255Units_onWholeScalars() {
        h.registered()
        SallaPush.User.addTag("bio", "a" + emoji.repeat(200))
        h.advance(11.0)
        val value = h.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("bio") ?: ""
        assertEquals(255, value.length)
        assertTrue(!Character.isHighSurrogate(value.last()))
    }

    @Test
    fun test_serverText_isSanitizedAndCapped() {
        assertEquals("a b", LogSanitizer.serverText("a\n\r\tb"))
        assertEquals(200, LogSanitizer.serverText("z ".repeat(250))?.length)
        assertEquals("token <redacted> end", LogSanitizer.serverText("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln end"))
        assertNull(LogSanitizer.serverText("\u0000\u0001"))
        assertEquals("ab c", LogSanitizer.serverText("a\u0007b\u200B \u2028c"))
    }

    @Test
    fun test_serverText_keepsPackageClassAndHostNames() {
        val texts = listOf(
            "This app id belongs to another app (expected com.salla.exampleperfumes.store, got com.salla.dev.debug)",
            "This app id belongs to another app (expected sa.com.salla.store.exampleperfumesstores, got com.salla.dev.debug)",
            "SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
            "com.google.firebase.messaging.FirebaseMessagingService did not answer",
            "Unable to resolve host \"push.example.com\": No address associated with hostname",
            "CLEARTEXT communication to 10.0.2.2 not permitted by network security policy",
        )
        for (text in texts) assertEquals(text, LogSanitizer.serverText(text))
    }

    @Test
    fun test_serverText_masksEverySecretShape() {
        val secrets = listOf(
            // A customer JWT and a PASETO v4.public token.
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAxIiwiaWF0IjoxNzkwMDAwMDAwfQ.c2lnbmF0dXJl",
            "v4.public.eyJzdWIiOiIxMDAxIiwiaWF0IjoiMjAyNi0xMC0wM1QwMDowMDowMFoifQ5vYLnvG0rsR1MhKjzOTQx1C2B0J8nGv6",
            // A device secret (32 bytes, base64url), an installation id, an FCM token and an APNs token.
            "Q3fG8kVw2xZt9LmNp4sRb7HcJd1EaYuToIkPl6WqXzA",
            "7d3f1c2a-9b4e-4c1d-8f2a-3e5b6c7d8e9f",
            "dGVzdC1mY20tdG9rZW4:APA91bHPRgkF3JUikC4ENAHEeMrd41Zxv3hVZjC9KtT8OvPVGJ-hQMRKRrZuJAEcl7B338qju59zJMjw2DELjzE",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
            // Shorter base64 with a digit, and a long run without one.
            "c2VjcmV0LXNlY3JldC1zZWNyZXQ",
            "QWxhZGRpbjpvcGVuc2VzYW1lQWxhZGRpbjpvcGVu",
        )
        for (secret in secrets) {
            assertEquals(secret, "seen <redacted> here", LogSanitizer.serverText("seen $secret here"))
        }
    }

    /**
     * The whole text is masked before the 200-unit cap, as on iOS, so no cap
     * cuts a secret into a fragment the rule cannot judge, however much the runs before it shrink when masked.
     */
    @Test
    fun test_serverText_masksTheWholeTextBeforeTheCap_soNoFragmentOfASecretShows() {
        val secret = "Ab3dEf5hIj7lMn9pQr1tUv3xYz5bCd7fGh9jKl1nOp3"
        val probe = "echo " + "a1".repeat(150) + " " + "w ".repeat(37) + "w!" + secret + " tail"
        assertEquals(382, probe.indexOf(secret))
        assertEquals("echo <redacted> " + "w ".repeat(37) + "w!<redacted> tail", LogSanitizer.serverText(probe))
        for (offset in 340..460) {
            val text = "x".repeat(300) + " " + "y ".repeat((offset - 301) / 2) + secret + " end"
            val out = LogSanitizer.serverText(text) ?: ""
            assertTrue("offset $offset: $out", !out.contains(secret.take(4)))
            assertTrue(out.length <= 200)
        }
        // A word after a long masked run is judged whole, wherever it sits in the original text.
        assertEquals("<redacted> hello world", LogSanitizer.serverText("x".repeat(390) + " hello world"))
    }

    /** Only text longer than the largest answer (256 KB) is cut, and a run that cut ends inside is masked whole. */
    @Test
    fun test_serverText_beyondTheLargestAnswer_aRunTheInputCapCutsIsMaskedWhole() {
        // 262,145 characters are read. Here the last one read is a space: the word before it is whole and kept.
        val ended = "x".repeat(262_000) + " " + "abc ".repeat(36) + "tail"
        assertEquals("<redacted> " + "abc ".repeat(35) + "abc", LogSanitizer.serverText(ended))
        // One character later the last one read is a letter: its word may go on, so it is masked whole.
        val cut = "x".repeat(262_001) + " " + "abc ".repeat(36) + "tail"
        assertEquals("<redacted> " + "abc ".repeat(35) + "<redacted>", LogSanitizer.serverText(cut))
        // A secret that the input cap cuts never shows a piece.
        val secret = "Ab3dEf5hIj7lMn9pQr1tUv3xYz5bCd7fGh9jKl1nOp3"
        for (shift in 0..44) {
            val out = LogSanitizer.serverText("x".repeat(262_100 + shift) + " " + secret + " end") ?: ""
            assertTrue("shift $shift: $out", !out.contains(secret.take(4)))
        }
    }

    @Test
    fun test_clean_removesControlCharacters_withoutMasking() {
        assertEquals("first name INFO forged", LogSanitizer.clean("first\u0007 name\nINFO forged"))
        assertEquals("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0", LogSanitizer.clean("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"))
        assertEquals(64, LogSanitizer.clean("k".repeat(100), 64).length)
        assertEquals("a", LogSanitizer.clean("a" + emoji, 2))
    }
}
