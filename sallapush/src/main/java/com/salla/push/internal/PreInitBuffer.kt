package com.salla.push.internal

import com.salla.push.internal.log.SecretString

/** A public call made before the engine could run (not initialised yet, or waiting for consent). */
internal sealed class BufferedCall {
    /** A login of [externalId], with its identity token. */
    class Login(val externalId: String, val token: SecretString) : BufferedCall()

    class Logout : BufferedCall()

    class AddTags(val tags: Map<String, String>) : BufferedCall()

    class RemoveTags(val keys: List<String>) : BufferedCall()

    class Language(val language: String?) : BufferedCall()

    class OptedOut(val optedOut: Boolean) : BufferedCall()

    /** Tag, language and opt-in calls may be dropped on overflow; identity calls never are. */
    val isDroppable: Boolean
        get() = this is AddTags || this is RemoveTags || this is Language || this is OptedOut

    /** Whether a getter shows this call before the engine applies it ([PendingWrites]). */
    val changesGetters: Boolean
        get() = this !is Language
}

internal class BufferedEvent(val notificationId: String, val type: String, val atSeconds: Double, val actionId: String?)

/**
 * The bounded in-memory buffer used before the engine runs: the last push token, up to 20 taps,
 * up to 50 received/displayed/dismissed events, and up to 100 ordered calls, replayed in order when the engine starts.
 * On overflow the oldest tag/language/opt-in calls are dropped; identity calls are collapsed with the identity collapse
 * rules and never dropped. Touched on the engine thread only.
 */
internal class PreInitBuffer {

    val calls = ArrayList<BufferedCall>()
    var token: String? = null
    val events = ArrayDeque<BufferedEvent>()
    val opens = ArrayDeque<BufferedEvent>()

    /** Tag, language and opt-in calls dropped since the buffer was last taken; reported once, when it is replayed. */
    var droppedCalls = 0
        private set

    /** Taps and received/displayed/dismissed events dropped since the buffer was last taken. */
    var droppedEvents = 0
        private set

    /** Buffers [call]; returns the calls dropped or collapsed to make room for it. */
    fun add(call: BufferedCall): List<BufferedCall> {
        val removed = if (calls.size >= MAX_CALLS) makeRoom() else emptyList()
        calls.add(call)
        return removed
    }

    fun addEvent(event: BufferedEvent) {
        val target = if (event.type == OPENED) opens else events
        val max = if (event.type == OPENED) MAX_OPENS else MAX_EVENTS
        target.addLast(event)
        while (target.size > max) {
            target.removeFirst()
            droppedEvents += 1
        }
    }

    fun clear() {
        calls.clear()
        token = null
        events.clear()
        opens.clear()
        droppedCalls = 0
        droppedEvents = 0
    }

    private fun makeRoom(): List<BufferedCall> {
        val droppable = calls.indexOfFirst { it.isDroppable }
        if (droppable >= 0) {
            droppedCalls += 1
            return listOf(calls.removeAt(droppable))
        }
        return collapseIdentityCalls()
    }

    /** A login removes earlier logins; a logout removes earlier logins and logouts. Returns the removed calls. */
    private fun collapseIdentityCalls(): List<BufferedCall> {
        val result = ArrayList<BufferedCall>(calls.size)
        val removed = ArrayList<BufferedCall>()
        for (call in calls) {
            val superseded: (BufferedCall) -> Boolean = when (call) {
                is BufferedCall.Login -> { earlier -> earlier is BufferedCall.Login }
                is BufferedCall.Logout -> { earlier -> earlier is BufferedCall.Login || earlier is BufferedCall.Logout }
                else -> { _ -> false }
            }
            val iterator = result.iterator()
            while (iterator.hasNext()) {
                val earlier = iterator.next()
                if (superseded(earlier)) {
                    iterator.remove()
                    removed.add(earlier)
                }
            }
            result.add(call)
        }
        calls.clear()
        calls.addAll(result)
        return removed
    }

    companion object {
        const val MAX_CALLS = 100
        const val MAX_EVENTS = 50
        const val MAX_OPENS = 20
        const val OPENED = "opened"
    }
}
