package com.salla.push.internal.storage

import com.salla.push.internal.engine.EventsState
import com.salla.push.internal.engine.PendingEvent
import com.salla.push.internal.payload.MessageId
import com.salla.push.internal.util.arrayOrNull
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** events.json: {"v":1, "pending":[{"id","type","at","action_id"}], "seen":["msg_…|opened"]}. */
internal class EventStore(private val files: SdkFiles, directory: File) {

    private val file = File(directory, FILE_NAME)

    fun load(): Loaded<EventsState> {
        return when (val read = files.read(file)) {
            is FileRead.Missing -> Loaded.Missing
            is FileRead.Unavailable -> Loaded.Unavailable(read.reason)
            is FileRead.Data -> {
                val state = EventCodec.decode(read.bytes)
                if (state == null) {
                    files.moveAside(file, CORRUPT_NAME)
                    Loaded.Corrupt
                } else {
                    Loaded.Ok(state)
                }
            }
        }
    }

    fun save(state: EventsState): Boolean {
        val bytes = EventCodec.encode(state) ?: return false
        return files.write(file, bytes)
    }

    companion object {
        const val FILE_NAME = "events.json"
        const val CORRUPT_NAME = "events.corrupt.json"
        const val MAX_PENDING = 500
        const val MAX_SEEN = 1000
    }
}

internal object EventCodec {

    private val TYPES = setOf("received", "displayed", "opened", "dismissed")

    fun encode(state: EventsState): ByteArray? {
        return try {
            val pending = JSONArray()
            for (event in state.pending) {
                pending.put(
                    JSONObject()
                        .put("id", event.notificationId)
                        .put("type", event.type)
                        .put("at", event.at)
                        .putNullable("action_id", event.actionId),
                )
            }
            val seen = JSONArray()
            for (key in state.seen) seen.put(key)
            JSONObject().put("v", 1).put("pending", pending).put("seen", seen).toString().encodeToByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun decode(bytes: ByteArray): EventsState? {
        val json = parseJsonObject(bytes.decodeToString()) ?: return null
        if (json.optInt("v", 0) != 1) return null
        val state = EventsState()
        json.arrayOrNull("pending")?.let { array ->
            for (index in 0 until minOf(array.length(), EventStore.MAX_PENDING)) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.stringOrNull("id") ?: continue
                val type = entry.stringOrNull("type") ?: continue
                val at = entry.stringOrNull("at") ?: continue
                val seconds = EventTime.parse(at) ?: continue
                if (!MessageId.isValid(id) || type !in TYPES) continue
                // Written again from the parsed time, so an event queued by 1.0.0-beta.1 is sent with milliseconds.
                state.pending.add(PendingEvent(id, type, EventTime.format(seconds), seconds, entry.stringOrNull("action_id")))
            }
        }
        json.arrayOrNull("seen")?.let { array ->
            val start = maxOf(0, array.length() - EventStore.MAX_SEEN)
            for (index in start until array.length()) {
                (array.opt(index) as? String)?.let { state.seen.add(it) }
            }
        }
        return state
    }
}

/**
 * ISO-8601 UTC timestamps with milliseconds ("2026-10-02T10:00:00.123Z"), the form push-service needs: it cuts an
 * event's `at` at the first "." before it stores the event, so a time without a fraction keeps its "Z", storing
 * fails, and the 202 answer hides that the whole batch was lost. Parsing also takes the form without a
 * fraction ("2026-10-02T10:00:00Z"), which 1.0.0-beta.1 wrote.
 */
internal object EventTime {

    private val milliseconds = formatter("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private val wholeSeconds = formatter("yyyy-MM-dd'T'HH:mm:ss'Z'")

    fun format(epochSeconds: Double): String = milliseconds.get()?.format(Date(Math.round(epochSeconds * 1000.0))) ?: ""

    fun parse(text: String): Double? = parse(text, milliseconds) ?: parse(text, wholeSeconds)

    private fun parse(text: String, formatter: ThreadLocal<SimpleDateFormat>): Double? {
        val format = formatter.get() ?: return null
        val position = ParsePosition(0)
        val date = format.parse(text, position) ?: return null
        if (position.index != text.length) return null
        return date.time / 1000.0
    }

    private fun formatter(pattern: String): ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }
}
