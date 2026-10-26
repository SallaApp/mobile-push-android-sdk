package com.salla.push.internal.log

import android.util.Log
import com.salla.push.SallaPushLogEvent
import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLogListener
import com.salla.push.internal.util.AppCallbacks
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.ThreadRunner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One log event, already redacted: messages are built only from redacted values. */
internal class LogRecord(
    val code: LogCode,
    val message: String,
    val metadata: Map<String, String>,
    val timestampMillis: Long,
)

/** A synchronous observer of every emitted record, whatever the levels. */
internal fun interface LogSink {
    fun accept(record: LogRecord)
}

/** Where console lines go. */
internal fun interface ConsoleWriter {
    fun write(level: SallaPushLogLevel, line: String)
}

internal object LogcatWriter : ConsoleWriter {

    /** At most 23 characters, so Log.isLoggable never rejects it on old releases. */
    private const val TAG = "SallaPush"

    override fun write(level: SallaPushLogLevel, line: String) {
        val priority = when (level) {
            SallaPushLogLevel.ERROR -> Log.ERROR
            SallaPushLogLevel.WARN -> Log.WARN
            SallaPushLogLevel.INFO -> Log.INFO
            SallaPushLogLevel.DEBUG -> Log.DEBUG
            else -> Log.VERBOSE
        }
        Log.println(priority, TAG, line)
    }
}

/**
 * Leveled, redacted logging with two independent sinks: the console (Logcat) and app listeners. An entry reaches a
 * sink iff the sink level is not OFF and the entry level is at most the sink level. The level check runs before the
 * message is built.
 *
 * Console level precedence: runtime setter > explicit configuration > manifest meta-data > WARN. Until one of them
 * is known (the meta-data is read on the engine thread), up to [EARLY_CAPACITY] console entries are held.
 *
 * As on iOS, the first [FIRST_LISTENER_CAPACITY] warnings and errors logged before any listener exists are kept for
 * the first listener added, which receives the ones its level admits before any later entry: a start-up problem (an
 * invalid app id, say) reaches a telemetry listener added right after initWithContext. Debug, info and verbose
 * entries are never kept.
 */
internal class Logger(
    private val clock: Clock,
    private val console: ConsoleWriter,
) {

    private class Listener(val listener: SallaPushLogListener, val level: SallaPushLogLevel)

    /** A record waiting for the listeners; [only] limits it to one registration (the first listener's early ones). */
    private class Queued(val record: LogRecord, val only: Listener? = null)

    @Volatile
    private var runtimeLevel: SallaPushLogLevel? = null

    @Volatile
    private var configLevel: SallaPushLogLevel? = null

    @Volatile
    private var manifestLevel: SallaPushLogLevel? = null

    @Volatile
    private var manifestResolved = false

    private val earlyLock = Any()
    private val early = ArrayDeque<LogRecord>()

    /** Changed under [listenersLock] only; read without it (a copy-on-write snapshot). */
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val listenersLock = Any()
    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val dispatcher = ListenerDispatcher()

    /** The warnings and errors kept for the first listener, oldest first; null once a listener was added. */
    private var forFirstListener: ArrayList<LogRecord>? = ArrayList()

    /** Whether another warning or error may still be kept for the first listener. Changed under [listenersLock]. */
    @Volatile
    private var keepingForFirstListener = true

    /** The keys [logOnce] has seen in this process. */
    private val loggedOnce: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** The effective console level. */
    val consoleLevel: SallaPushLogLevel
        get() = runtimeLevel ?: configLevel ?: manifestLevel ?: SallaPushLogLevel.WARN

    private val levelKnown: Boolean
        get() = runtimeLevel != null || configLevel != null || manifestResolved

    fun setRuntimeLevel(level: SallaPushLogLevel) {
        runtimeLevel = level
        flushEarly()
    }

    /** The level a configuration set explicitly; null when the current configuration set none. */
    fun setConfigLevel(level: SallaPushLogLevel?) {
        configLevel = level
        if (levelKnown) flushEarly()
    }

    fun setManifestLevel(level: SallaPushLogLevel?) {
        manifestLevel = level
        manifestResolved = true
        flushEarly()
    }

    /**
     * Adds [listener] at [level], replacing an earlier registration of the same listener. Any thread. The first
     * listener of the process also receives the kept warnings and errors its level admits, before any later entry.
     */
    fun addListener(listener: SallaPushLogListener, level: SallaPushLogLevel) {
        synchronized(listenersLock) {
            removeListenerEntries(listener)
            val entry = Listener(listener, level)
            listeners.add(entry)
            val kept = forFirstListener ?: return
            forFirstListener = null
            keepingForFirstListener = false
            // Queued under the lock that every record for the listeners takes: no later entry can go before them.
            for (record in kept) if (admits(level, record.code.level)) dispatcher.enqueue(Queued(record, entry))
        }
    }

    /** Removes every registration of [listener]. Any thread. */
    fun removeListener(listener: SallaPushLogListener) {
        synchronized(listenersLock) { removeListenerEntries(listener) }
    }

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    inline fun log(code: LogCode, metadata: Map<String, String> = emptyMap(), message: () -> String) {
        if (!wants(code.level)) return
        emit(code, metadata, message())
    }

    /**
     * Logs [code] the first time [key] is seen in this process (a launch), as iOS's logOnce does; later calls with the
     * same key log nothing. The key names the entry and what it concerns, for example the app id and API host.
     */
    fun logOnce(key: String, code: LogCode, message: () -> String) {
        if (!loggedOnce.add(key)) return
        log(code, emptyMap(), message)
    }

    fun wants(level: SallaPushLogLevel): Boolean {
        val rank = level.ordinal
        if (rank == SallaPushLogLevel.OFF.ordinal) return false
        if (!sinks.isEmpty()) return true
        if (!levelKnown) return true
        if (admits(consoleLevel, level)) return true
        if (keepingForFirstListener && keptForFirstListener(level)) return true
        return listenerWants(level)
    }

    fun emit(code: LogCode, metadata: Map<String, String>, message: String) {
        val record = LogRecord(code, oneLine(message), allowedMetadata(metadata), clock.nowMillis())
        notifySinks(record)
        if (levelKnown) writeConsole(record) else holdEarly(record)
        toListeners(record)
    }

    /**
     * Queues [record] for the listeners that take it, or keeps it for the first listener when none exists yet. Without
     * listeners and once nothing more is kept (the usual state), this takes no lock.
     */
    private fun toListeners(record: LogRecord) {
        val level = record.code.level
        if (!keepingForFirstListener && !listenerWants(level)) return
        synchronized(listenersLock) {
            if (listenerWants(level)) {
                dispatcher.enqueue(Queued(record))
                return
            }
            val kept = forFirstListener ?: return
            if (!keptForFirstListener(level) || kept.size >= FIRST_LISTENER_CAPACITY) return
            kept.add(record)
            if (kept.size >= FIRST_LISTENER_CAPACITY) keepingForFirstListener = false
        }
    }

    /**
     * Whether some listener takes entries of [level]: read from the current registrations each time, so a concurrent
     * add or remove can never leave a registered listener without its entries.
     */
    private fun listenerWants(level: SallaPushLogLevel): Boolean {
        for (entry in listeners) {
            if (admits(entry.level, level)) return true
        }
        return false
    }

    private fun notifySinks(record: LogRecord) {
        for (sink in sinks) {
            try {
                sink.accept(record)
            } catch (ignored: Throwable) {
                // A sink must never break logging.
            }
        }
    }

    private fun writeConsole(record: LogRecord) {
        if (!admits(consoleLevel, record.code.level)) return
        try {
            console.write(record.code.level, consoleLine(record))
        } catch (ignored: Throwable) {
            // The console is best effort.
        }
    }

    /**
     * "<code>: <message>", plus the HTTP status, error code and X-Request-Id of a failure when the message does not
     * already name them: support finds a request by its id, and Logcat has no other place for the metadata.
     */
    private fun consoleLine(record: LogRecord): String {
        val base = "${record.code.code}: ${record.message}"
        val metadata = record.metadata
        if (metadata.isEmpty()) return base
        val details = ArrayList<String>(3)
        metadata[Meta.HTTP_STATUS]?.let { if (!record.message.contains(it)) details.add("HTTP $it") }
        metadata[Meta.ERROR_CODE]?.let { if (!record.message.contains(it)) details.add(it) }
        metadata[Meta.REQUEST_ID]?.let { if (!record.message.contains(it)) details.add("request $it") }
        return if (details.isEmpty()) base else "$base [${details.joinToString(", ")}]"
    }

    private fun holdEarly(record: LogRecord) {
        synchronized(earlyLock) {
            if (early.size >= EARLY_CAPACITY) early.removeFirst()
            early.addLast(record)
        }
        if (levelKnown) flushEarly()
    }

    private fun flushEarly() {
        val pending = synchronized(earlyLock) {
            if (early.isEmpty()) return
            val copy = ArrayList(early)
            early.clear()
            copy
        }
        for (record in pending) writeConsole(record)
    }

    private fun removeListenerEntries(listener: SallaPushLogListener) {
        // Each remove is atomic for readers; removeIf would need API 24.
        for (entry in listeners) {
            if (entry.listener === listener) listeners.remove(entry)
        }
    }

    private fun allowedMetadata(metadata: Map<String, String>): Map<String, String> {
        if (metadata.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((key, value) in metadata) {
            if (key in Meta.ALLOWED) result[key] = oneLine(value)
        }
        return result
    }

    private fun oneLine(text: String): String {
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return text
        return text.replace('\n', ' ').replace('\r', ' ')
    }

    /**
     * Delivers records to app listeners on a private thread, behind a queue of [QUEUE_CAPACITY]. On overflow the
     * oldest records are dropped and one log.dropped record with the count is delivered.
     */
    private inner class ListenerDispatcher {

        private val lock = Any()
        private val queue = ArrayDeque<Queued>()
        private var dropped = 0
        private var draining = false
        private val runner by lazy { ThreadRunner("SallaPush-log") { } }

        fun enqueue(queued: Queued) {
            var start = false
            synchronized(lock) {
                if (queue.size >= QUEUE_CAPACITY) {
                    queue.removeFirst()
                    dropped += 1
                }
                queue.addLast(queued)
                if (!draining) {
                    draining = true
                    start = true
                }
            }
            if (start && !runner.execute(Runnable { drain() })) {
                synchronized(lock) { draining = false }
            }
        }

        private fun drain() {
            while (true) {
                var notice: LogRecord? = null
                var next: Queued? = null
                synchronized(lock) {
                    if (dropped > 0) {
                        notice = droppedRecord(dropped)
                        dropped = 0
                    }
                    next = queue.removeFirstOrNull()
                    if (next == null && notice == null) {
                        draining = false
                        return
                    }
                }
                notice?.let {
                    notifySinks(it)
                    writeConsole(it)
                    deliver(Queued(it))
                }
                next?.let { deliver(it) }
            }
        }

        private fun droppedRecord(count: Int): LogRecord = LogRecord(
            LogCode.LOG_DROPPED,
            "The log listener fell behind; $count ${if (count == 1) "entry was" else "entries were"} dropped.",
            mapOf(Meta.COUNT to count.toString()),
            clock.nowMillis(),
        )

        private fun deliver(queued: Queued) {
            val record = queued.record
            val event = SallaPushLogEvent(
                record.code.level,
                record.code.category,
                record.code.code,
                record.message,
                record.metadata,
                record.timestampMillis,
            )
            for (entry in listeners) {
                if (queued.only != null && entry !== queued.only) continue
                if (!admits(entry.level, record.code.level)) continue
                try {
                    entry.listener.onLogEvent(event)
                } catch (t: Throwable) {
                    // A failing app listener must not stop delivery to the others.
                    reportListenerFailure(t)
                }
            }
        }

        /**
         * ERROR api.callback_threw, once per process: the report goes through the listeners too, so a listener that
         * throws at every entry would otherwise report itself without end. Nothing here may throw: this thread drains
         * the queue.
         */
        private fun reportListenerFailure(failure: Throwable) {
            try {
                logOnce(LISTENER_FAILURE_KEY, LogCode.API_CALLBACK_THREW) {
                    "The listener passed to Debug.addLogListener threw ${AppCallbacks.failure(failure)}; the other " +
                        "listeners still ran. Reported once per launch."
                }
            } catch (ignored: Throwable) {
                // The report is best effort; delivery goes on.
            }
        }
    }

    private companion object {
        const val EARLY_CAPACITY = 50
        const val QUEUE_CAPACITY = 500
        const val FIRST_LISTENER_CAPACITY = 100
        const val LISTENER_FAILURE_KEY = "log_listener_threw"

        /** The sink rule: a sink at [sink] takes an entry at [entry] iff it is not OFF nor above it. */
        fun admits(sink: SallaPushLogLevel, entry: SallaPushLogLevel): Boolean =
            sink != SallaPushLogLevel.OFF && entry.ordinal <= sink.ordinal

        /** Only warnings and errors are kept for the first listener (the default listener level). */
        fun keptForFirstListener(entry: SallaPushLogLevel): Boolean = admits(SallaPushLogLevel.WARN, entry)
    }
}
