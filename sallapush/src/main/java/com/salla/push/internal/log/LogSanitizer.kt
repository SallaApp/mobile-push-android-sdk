package com.salla.push.internal.log

import com.salla.push.internal.util.truncatedUtf16

/**
 * Makes text that came from outside SallaPush safe for one log line. [clean] removes control and format characters,
 * collapses whitespace and caps the length (app-supplied text, as on iOS); [serverText] also masks what looks like a
 * secret, since a server or an exception may echo one.
 */
internal object LogSanitizer {

    private const val MAX_LENGTH = 200

    /**
     * Control, format, surrogate, private-use and unassigned characters removed, every whitespace run one space,
     * trimmed, and at most [max] UTF-16 units on whole code points.
     */
    fun clean(text: String, max: Int = MAX_LENGTH): String = truncatedUtf16(collapse(text, max).text, max).trimEnd()

    /**
     * [clean]ed server or exception text with its secret-looking runs masked; null when nothing is left. Masking runs
     * on the whole text, and only then is it capped at 200 units, so no cap ever cuts a secret before it is masked (as
     * on iOS). The text is at most an answer body, which the transport caps at 256 KB; longer text is cut there, and a
     * run that cut ends inside is masked whole, since a cut secret is a fragment the rule cannot judge.
     */
    fun serverText(text: String?): String? {
        if (text == null) return null
        val collapsed = collapse(text, MAX_INPUT)
        val body = collapsed.text
        val masked = TOKEN_RUN.replace(body) { run ->
            val cut = collapsed.cut && run.range.last == body.length - 1
            if (cut || looksLikeASecret(run.value)) REDACTED else run.value
        }
        val cleaned = truncatedUtf16(masked, MAX_LENGTH)
        return if (cleaned.isEmpty()) null else cleaned
    }

    /** Collapsed text; [cut] when the cap stopped inside the input right after a non-space: its last run may be cut. */
    private class Collapsed(val text: String, val cut: Boolean)

    private fun collapse(text: String, cap: Int): Collapsed {
        val builder = StringBuilder(minOf(text.length, cap + 1))
        var lastWasSpace = false
        var index = 0
        while (index < text.length && builder.length <= cap) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!lastWasSpace && builder.isNotEmpty()) builder.append(' ')
                lastWasSpace = true
            } else if (Character.getType(codePoint) !in REMOVED_TYPES) {
                builder.appendCodePoint(codePoint)
                lastWasSpace = false
            }
        }
        return Collapsed(builder.toString().trim(), index < text.length && !lastWasSpace)
    }

    /**
     * A run of token characters is a secret when one of its segments (split on '.' and ':', the separators of JWTs,
     * PASETO tokens and FCM tokens) is at least 32 characters long, or at least 20 with a digit in it. Device secrets,
     * installation ids, push tokens and the segments of customer tokens all have such a segment; package names, class
     * names and host names normally do not, so a log line keeps naming them.
     */
    private fun looksLikeASecret(run: String): Boolean {
        for (segment in run.split('.', ':')) {
            if (segment.length >= SECRET_LENGTH) return true
            if (segment.length >= SECRET_LENGTH_WITH_DIGIT && segment.any { it in '0'..'9' }) return true
        }
        return false
    }

    private const val REDACTED = "<redacted>"

    /** The most server text masked: the largest answer body the transport reads (256 KB). */
    private const val MAX_INPUT = 262_144
    private const val SECRET_LENGTH = 32
    private const val SECRET_LENGTH_WITH_DIGIT = 20

    /** The characters JWTs, PASETO tokens, base64 secrets, UUIDs and push tokens are made of. */
    private val TOKEN_RUN = Regex("[A-Za-z0-9._~+/=:-]+")

    private val REMOVED_TYPES = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
    )
}
