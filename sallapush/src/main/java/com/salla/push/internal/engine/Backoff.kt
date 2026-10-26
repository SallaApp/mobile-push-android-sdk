package com.salla.push.internal.engine

import com.salla.push.internal.util.Jitter

/**
 * Retry delays: delay = min(3600, 2 × 2^(attempt−1)) × U(0.5, 1.0) seconds, with the exponent clamped
 * at 12 and computed in floating point. After [MAX_ATTEMPTS_PER_PROCESS] attempts in one process an op waits for the
 * next trigger (launch, foreground, connectivity) instead, keeping its attempt count.
 */
internal object Backoff {

    const val MAX_ATTEMPTS_PER_PROCESS = 6
    const val TRIGGER_GAP_SECONDS = 2.0
    private const val MAX_SECONDS = 3600.0
    private const val MAX_EXPONENT = 12

    fun delaySeconds(attempt: Int, jitter: Jitter): Double {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        val base = minOf(MAX_SECONDS, 2.0 * Math.pow(2.0, exponent.toDouble()))
        val factor = 0.5 + 0.5 * jitter.next().coerceIn(0.0, 1.0)
        return base * factor
    }
}
