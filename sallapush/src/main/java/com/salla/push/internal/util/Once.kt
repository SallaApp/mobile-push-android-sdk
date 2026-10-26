package com.salla.push.internal.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards a callback or continuation that must run at most once: app-supplied completions, system callbacks and
 * suspended continuations all go through one of these.
 */
internal class Once {

    private val claimed = AtomicBoolean(false)

    /** True for the first caller only. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
