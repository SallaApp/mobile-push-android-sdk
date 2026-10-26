package com.salla.push.internal.util

import android.net.TrafficStats

/** The TrafficStats tag of every SallaPush socket ("SPSH"). */
internal const val SALLAPUSH_SOCKET_TAG = 0x53505348

/**
 * Runs [block] with the calling thread's sockets tagged as SallaPush's: StrictMode's untagged-socket check (part of
 * detectAll(), fatal with penaltyDeath in a host's debug build) and the system's data-usage accounting see them.
 */
internal inline fun <T> withSocketTag(block: () -> T): T {
    try {
        TrafficStats.setThreadStatsTag(SALLAPUSH_SOCKET_TAG)
    } catch (ignored: Throwable) {
        // Tagging is accounting only; the request goes out either way.
    }
    try {
        return block()
    } finally {
        try {
            TrafficStats.clearThreadStatsTag()
        } catch (ignored: Throwable) {
            // Tagging is accounting only.
        }
    }
}
