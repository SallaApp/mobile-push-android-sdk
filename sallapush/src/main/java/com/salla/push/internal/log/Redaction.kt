package com.salla.push.internal.log

/**
 * Holds a secret (device secret, customer token, installation id). Its string form is always "<redacted>", so it
 * can be passed around and even interpolated by mistake without leaking; only [reveal] returns the value.
 */
internal class SecretString(private val value: String) {

    fun reveal(): String = value

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is SecretString && other.value == value

    override fun hashCode(): Int = value.hashCode()

    private companion object {
        const val REDACTED = "<redacted>"
    }
}

internal object Redact {

    /** A push token as "…" plus its last 6 characters. */
    fun pushToken(token: String?): String {
        if (token == null || token.isEmpty()) return "none"
        return "…" + token.takeLast(6)
    }
}
