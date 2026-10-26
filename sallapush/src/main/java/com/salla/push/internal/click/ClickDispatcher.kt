package com.salla.push.internal.click

import com.salla.push.SallaPushClickEvent
import com.salla.push.SallaPushClickListener
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import com.salla.push.internal.log.Meta
import com.salla.push.internal.util.AppCallbacks
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.MainThread
import java.util.concurrent.CopyOnWriteArrayList

/** A tap waiting for a click listener. */
internal class QueuedClick(
    val event: SallaPushClickEvent,
    val nonce: String,
    val atMillis: Long,
    val payload: Map<String, String>,
    val androidNotificationId: Int,
)

/**
 * Delivers taps to click listeners on the main thread. Deduplicated by the per-notification nonce
 * (a tap delivered twice navigates once). With no listener, at most 10 taps are kept for 5 minutes (persisted across
 * process death through [persist]) and delivered once, to the first listener added.
 */
internal class ClickDispatcher(
    private val mainThread: MainThread,
    private val clock: Clock,
    private val logger: Logger,
    private val persist: (List<QueuedClick>) -> Unit,
) {

    private val listeners = CopyOnWriteArrayList<SallaPushClickListener>()

    // Main thread only.
    private val queue = ArrayDeque<QueuedClick>()
    private val recentNonces = LinkedHashSet<String>()

    /**
     * Adds [listener] once, from any thread (atomic: two threads adding the same listener register it once). The taps
     * kept for a listener are delivered on the main thread; that does nothing when none are kept.
     */
    fun addListener(listener: SallaPushClickListener) {
        if (!listeners.addIfAbsent(listener)) return
        mainThread.post(Runnable { deliverQueued() })
    }

    fun removeListener(listener: SallaPushClickListener) {
        listeners.remove(listener)
    }

    /** Must run on the main thread. */
    fun dispatch(click: QueuedClick) {
        if (!rememberNonce(click.nonce)) return
        if (listeners.isEmpty()) {
            queue.addLast(click)
            while (queue.size > MAX_QUEUED) queue.removeFirst()
            logger.log(LogCode.NOTIF_CLICK_QUEUED) { "Click kept until a click listener is added." }
            persist(ArrayList(queue))
            return
        }
        deliver(click)
    }

    /** Taps restored from state.json at engine start. Must run on the main thread. */
    fun restore(clicks: List<QueuedClick>) {
        val known = queue.map { it.nonce }.toHashSet()
        for (click in clicks) {
            if (click.nonce in known || !rememberNonce(click.nonce)) continue
            queue.addLast(click)
        }
        while (queue.size > MAX_QUEUED) queue.removeFirst()
        if (listeners.isNotEmpty()) deliverQueued() else persist(ArrayList(queue))
    }

    private fun deliverQueued() {
        if (queue.isEmpty() || listeners.isEmpty()) return
        val now = clock.nowMillis()
        val pending = ArrayList(queue)
        queue.clear()
        persist(emptyList())
        for (click in pending) {
            if (now - click.atMillis <= TTL_MILLIS) deliver(click)
        }
    }

    private fun deliver(click: QueuedClick) {
        var delivered = 0
        for (listener in listeners) {
            try {
                listener.onClick(click.event)
                delivered += 1
            } catch (t: Throwable) {
                logger.log(LogCode.API_CALLBACK_THREW) {
                    "The listener passed to Notifications.addClickListener threw ${AppCallbacks.failure(t)}; the tap still went to the other listeners."
                }
            }
        }
        logger.log(LogCode.NOTIF_CLICK_DELIVERED, mapOf(Meta.COUNT to delivered.toString())) {
            "Click delivered to $delivered listener${if (delivered == 1) "" else "s"}."
        }
    }

    /** False when this nonce was already dispatched. */
    private fun rememberNonce(nonce: String): Boolean {
        if (!recentNonces.add(nonce)) return false
        while (recentNonces.size > MAX_RECENT) {
            val iterator = recentNonces.iterator()
            iterator.next()
            iterator.remove()
        }
        return true
    }

    companion object {
        const val MAX_QUEUED = 10
        const val TTL_MILLIS = 300_000L
        private const val MAX_RECENT = 50
    }
}
