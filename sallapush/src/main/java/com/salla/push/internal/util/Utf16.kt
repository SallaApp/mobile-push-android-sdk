package com.salla.push.internal.util

/**
 * Length limits are UTF-16 code units, the server's measure (JavaScript String.length). The longest prefix made of
 * whole code points whose UTF-16 length is at most [max]: a surrogate pair is never split.
 */
internal fun truncatedUtf16(value: String, max: Int): String {
    if (max <= 0) return ""
    if (value.length <= max) return value
    return if (Character.isHighSurrogate(value[max - 1])) value.take(max - 1) else value.take(max)
}

/**
 * Trims, truncates to [max] UTF-16 units, and trims what the cut left at the end (the iOS SDK's field rule); empty
 * results are null so they are omitted, never sent as "".
 */
internal fun cleanField(value: String?, max: Int): String? {
    if (value == null) return null
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val truncated = truncatedUtf16(trimmed, max).trimEnd()
    return if (truncated.isEmpty()) null else truncated
}

/** Lowercases ASCII letters only; every other character is kept as is. */
internal fun asciiLowercase(value: String): String {
    var changed = false
    val chars = CharArray(value.length)
    for (index in value.indices) {
        val c = value[index]
        if (c in 'A'..'Z') {
            chars[index] = c + ('a' - 'A')
            changed = true
        } else {
            chars[index] = c
        }
    }
    return if (changed) chars.concatToString() else value
}

/** Keeps printable ASCII (0x20-0x7E); everything else becomes '?'. Used for HTTP header values. */
internal fun printableAscii(value: String): String {
    val builder = StringBuilder(value.length)
    for (c in value) {
        builder.append(if (c in ' '..'~') c else '?')
    }
    return builder.toString()
}
