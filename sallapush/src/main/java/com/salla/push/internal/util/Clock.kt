package com.salla.push.internal.util

/** Wall-clock time. Persisted times are seconds since the epoch, as doubles (the format shared with the iOS SDK). */
internal interface Clock {
    fun nowMillis(): Long
}

internal object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

internal fun Clock.nowSeconds(): Double = nowMillis() / 1000.0

/** Uniform random numbers in [0, 1), for retry jitter and the spread of the background events work. */
internal fun interface Jitter {
    fun next(): Double
}

internal object RandomJitter : Jitter {
    override fun next(): Double = kotlin.random.Random.Default.nextDouble()
}
