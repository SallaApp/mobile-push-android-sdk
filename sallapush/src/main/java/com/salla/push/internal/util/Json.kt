package com.salla.push.internal.util

import com.salla.push.internal.payload.DepthScan
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Deepest JSON nesting SallaPush ever parses; org.json is recursive and is never fed deeper documents. */
internal const val MAX_JSON_DEPTH = 32

/**
 * Parses a JSON object once [DepthScan] accepted the text as strict JSON within [maxDepth]; null for anything else
 * (malformed, lenient syntax, too deep, not an object). Nothing org.json throws, StackOverflowError included, escapes.
 */
internal fun parseJsonObject(text: String?, maxDepth: Int = MAX_JSON_DEPTH): JSONObject? {
    if (text == null || text.isEmpty()) return null
    if (!DepthScan.isSafe(text, maxDepth)) return null
    return try {
        JSONObject(text)
    } catch (t: Throwable) {
        null
    }
}

internal fun JSONObject.stringOrNull(key: String): String? {
    if (isNull(key)) return null
    return opt(key) as? String
}

internal fun JSONObject.booleanOrNull(key: String): Boolean? = opt(key) as? Boolean

/** A finite number, or null. */
internal fun JSONObject.doubleOrNull(key: String): Double? {
    val number = opt(key) as? Number ?: return null
    val value = number.toDouble()
    return if (value.isNaN() || value.isInfinite()) null else value
}

internal fun JSONObject.longOrNull(key: String): Long? {
    val number = opt(key) as? Number ?: return null
    val value = number.toDouble()
    if (value.isNaN() || value.isInfinite()) return null
    if (value != Math.floor(value)) return null
    if (value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()) return null
    return number.toLong()
}

internal fun JSONObject.objectOrNull(key: String): JSONObject? = opt(key) as? JSONObject

internal fun JSONObject.arrayOrNull(key: String): JSONArray? = opt(key) as? JSONArray

/** Key names of a JSONObject, read once. */
internal fun JSONObject.keyList(): List<String> {
    val result = ArrayList<String>(length())
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (key != null) result.add(key)
    }
    return result
}

internal object Hashing {

    private const val HEX = "0123456789abcdef"

    fun sha256(bytes: ByteArray): ByteArray? {
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return chars.concatToString()
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [text]; null only if the platform has no SHA-256. */
    fun sha256Hex(text: String): String? {
        val digest = sha256(text.encodeToByteArray()) ?: return null
        return hex(digest)
    }
}
