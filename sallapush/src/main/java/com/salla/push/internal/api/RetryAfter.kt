package com.salla.push.internal.api

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses Retry-After as delay seconds or an HTTP-date, clamped to 1 s…24 h. */
internal object RetryAfter {

    const val DEFAULT_RATE_LIMIT_SECONDS = 60.0
    private const val MIN_SECONDS = 1.0
    private const val MAX_SECONDS = 86_400.0

    private val DATE_PATTERNS = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )

    /** Seconds to wait, or null when the header is absent or unreadable. */
    fun seconds(header: String?, nowMillis: Long): Double? {
        val value = header?.trim() ?: return null
        if (value.isEmpty()) return null
        value.toDoubleOrNull()?.let { seconds ->
            if (seconds.isNaN() || seconds.isInfinite()) return null
            return clamp(seconds)
        }
        for (pattern in DATE_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.isLenient = false
            val position = ParsePosition(0)
            val date = format.parse(value, position) ?: continue
            if (position.index != value.length) continue
            return clamp((date.time - nowMillis) / 1000.0)
        }
        return null
    }

    fun clamp(seconds: Double): Double = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
}
