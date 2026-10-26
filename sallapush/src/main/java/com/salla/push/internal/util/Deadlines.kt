package com.salla.push.internal.util

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Hard deadlines for blocking socket I/O. A socket timeout bounds each read, not a whole exchange, so a server that
 * trickles bytes could hold a connection for hours; an armed deadline runs its action (closing the connection, which
 * makes the blocked read fail) on a private daemon thread when the time is up.
 */
internal object Deadlines {

    private const val IDLE_SECONDS = 30L

    private val executor: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "SallaPush-deadline").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    /** Runs [action] after [delayMillis] unless cancelled first. A deadline that cannot be armed is a no-op. */
    fun arm(delayMillis: Long, action: Runnable): Cancellable {
        return try {
            val guarded = Runnable {
                try {
                    action.run()
                } catch (ignored: Throwable) {
                    // A deadline action only closes a connection; its failure changes nothing.
                }
            }
            val future = executor.schedule(guarded, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            Cancellable { future.cancel(false) }
        } catch (t: Throwable) {
            Cancellable { }
        }
    }
}
