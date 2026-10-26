package com.salla.push.internal.util

import com.salla.push.internal.Sdk
import com.salla.push.internal.log.LogCode

/**
 * Wraps the body of every public entry point. Kotlin's null-check intrinsics are disabled for this library, so a Java
 * null reaches this block instead of throwing before it; nothing thrown inside ever reaches the host app.
 */
internal inline fun entry(api: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        EntryGuard.report(api, t)
    }
}

/** [entry] for public functions that return a value: [fallback] is returned when the body fails. */
internal inline fun <T> entryValue(api: String, fallback: T, block: () -> T): T {
    return try {
        block()
    } catch (t: Throwable) {
        EntryGuard.report(api, t)
        fallback
    }
}

/** True when a parameter declared non-null received a Java null. */
internal fun isNull(value: Any?): Boolean = value == null

internal object EntryGuard {

    /**
     * A throwable that escaped a public entry, logged as ERROR internal.error. Every entry checks its reference
     * parameters for Java nulls itself ([nullArgument]), so whatever reaches this point is a SallaPush or platform
     * failure, never the app's argument.
     */
    fun report(api: String, failure: Throwable) {
        try {
            Sdk.core.logger.log(LogCode.INTERNAL_ERROR) {
                "SallaPush hit an internal error in $api (${InternalFailure.describe(failure)}); the call was ignored."
            }
        } catch (ignored: Throwable) {
            // Logging is best effort; the guard itself must never throw.
        }
    }

    fun nullArgument(api: String, parameter: String) {
        nullArgument(api, parameter, "ignored")
    }

    fun nullArgument(api: String, parameter: String, outcome: String) {
        try {
            Sdk.core.logger.log(LogCode.API_NULL_ARGUMENT) { "$api was called with a null $parameter (from Java); $outcome." }
        } catch (ignored: Throwable) {
            // Logging is best effort; the guard itself must never throw.
        }
    }

    fun invalidArgument(api: String, reason: String) {
        try {
            Sdk.core.logger.log(LogCode.API_INVALID_ARGUMENT) { "$api ignored: $reason." }
        } catch (ignored: Throwable) {
            // Logging is best effort; the guard itself must never throw.
        }
    }
}
