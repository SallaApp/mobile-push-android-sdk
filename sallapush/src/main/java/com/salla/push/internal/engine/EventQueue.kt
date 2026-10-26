package com.salla.push.internal.engine

import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.EventsResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.internal.payload.MessageId
import com.salla.push.internal.storage.EventStore
import com.salla.push.internal.storage.EventTime

// Events: received / displayed / opened / dismissed, deduplicated by (id, type), batched, and sent only
// while registered. Events older than 6.5 days are dropped at flush. Events recorded outside the foreground, `opened`
// excepted, are also handed to WorkManager (Engine.scheduleEventsWork).

internal const val EVENT_RECEIVED = "received"
internal const val EVENT_DISPLAYED = "displayed"
internal const val EVENT_OPENED = "opened"
internal const val EVENT_DISMISSED = "dismissed"

private const val MAX_AGE_SECONDS = 6.5 * 86_400.0

internal fun Engine.recordEvent(notificationId: String, type: String, atSeconds: Double, actionId: String?) {
    if (!MessageId.isValid(notificationId)) return
    val key = "$notificationId|$type"
    if (!events.seen.add(key)) return
    while (events.seen.size > EventStore.MAX_SEEN) {
        val oldest = events.seen.iterator()
        if (oldest.hasNext()) {
            oldest.next()
            oldest.remove()
        }
    }
    if (events.pending.size >= EventStore.MAX_PENDING) {
        events.pending.removeAt(0)
        if (!queueFullLogged) {
            // Once per full queue: each later event drops the oldest one too, until events are sent.
            queueFullLogged = true
            logger.log(LogCode.EVENTS_QUEUE_FULL, mapOf(Meta.COUNT to "1")) {
                "Event queue full (500); the oldest event was dropped, and one more is dropped for each new event " +
                    "until events are sent."
            }
        }
    } else {
        queueFullLogged = false
    }
    events.pending.add(PendingEvent(notificationId, type, EventTime.format(atSeconds), atSeconds, actionId))
    if (eventsFirstPendingAt == null) eventsFirstPendingAt = now()
    eventsDirty = true
    // Outside the foreground the 5 s wake may never fire (Android 15+ cuts a cached app's network seconds after FCM's
    // service ends, and the freezer stops it): WorkManager gets the saved queue too. A tap's `opened` is left to the
    // foreground the tap brings, which sends it at once.
    if (type != EVENT_OPENED && !host.isForeground()) postEventsWork()
}

internal fun Engine.requestEventsFlush() {
    if (events.pending.isNotEmpty()) eventsFlushRequested = true
}

internal fun Engine.eventsReady(now: Double): Boolean {
    if (events.pending.isEmpty()) {
        eventsFlushRequested = false
        eventsFirstPendingAt = null
        return false
    }
    if (!opReady(eventsRetry, Engine.EVENTS_KEY, now)) return false
    if (eventsFlushRequested || events.pending.size >= Engine.EVENTS_BATCH_SIZE) return true
    val first = eventsFirstPendingAt ?: now
    return now >= first + Engine.EVENTS_DEBOUNCE_SECONDS
}

/** Drops events older than 6.5 days, then sends one batch; false when nothing was left to send. */
internal fun Engine.sendEvents(credentials: Credentials): Boolean {
    val now = now()
    val before = events.pending.size
    events.pending.removeAll { now - it.atSeconds > MAX_AGE_SECONDS }
    val expired = before - events.pending.size
    if (expired > 0) {
        eventsDirty = true
        logger.log(LogCode.EVENTS_DROPPED, mapOf(Meta.COUNT to expired.toString())) { "$expired events older than 6.5 days were dropped." }
    }
    if (events.pending.isEmpty()) {
        eventsFlushRequested = false
        eventsFirstPendingAt = null
        eventsRetry = Retry()
        clearAttempts(Engine.EVENTS_KEY)
        return false
    }
    val batch = ArrayList(events.pending.take(Engine.EVENTS_BATCH_SIZE))
    inFlight = "events"
    val generationNow = generation
    api.events(credentials, batch) { result ->
        if (generationNow == generation) {
            inFlight = null
            task { handleEventsResult(batch, result) }
        }
    }
    return true
}

private fun Engine.handleEventsResult(batch: List<PendingEvent>, result: ApiResult<EventsResult>) {
    when (result) {
        is ApiResult.Ok -> {
            removeBatch(batch)
            eventsRetry = Retry()
            clearAttempts(Engine.EVENTS_KEY)
            // push-service answers 202 {}: the batch's size is all there is to report.
            logger.log(LogCode.EVENTS_FLUSHED, mapOf(Meta.COUNT to batch.size.toString())) {
                if (batch.size == 1) "1 event sent." else "${batch.size} events sent."
            }
        }
        is ApiResult.Err -> {
            val failure = result.failure
            if (handleDeviceAnswer(failure)) return
            if (failure.failureClass == FailureClass.CLIENT) {
                removeBatch(batch)
                logger.log(LogCode.NET_CLIENT_ERROR, failureMetadata(failure)) { "${failure.describe()} was rejected; ${batch.size} events dropped." }
                return
            }
            applyFailure(eventsRetry, Engine.EVENTS_KEY, failure)
        }
    }
}

private fun Engine.removeBatch(batch: List<PendingEvent>) {
    val sent = HashSet<PendingEvent>(batch)
    events.pending.removeAll { it in sent }
    eventsDirty = true
    if (events.pending.isEmpty()) {
        eventsFlushRequested = false
        eventsFirstPendingAt = null
    }
}
