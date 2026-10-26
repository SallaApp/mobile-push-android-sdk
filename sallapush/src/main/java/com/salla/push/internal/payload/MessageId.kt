package com.salla.push.internal.payload

/** SallaPush message ids: "msg_" plus 1-64 lowercase hex characters. Only these produce events. */
internal object MessageId {

    private val PATTERN = Regex("^msg_[0-9a-f]{1,64}$")

    fun isValid(value: String?): Boolean = value != null && PATTERN.matches(value)
}
