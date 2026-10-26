package com.salla.push.internal

import com.salla.push.internal.engine.Snapshot
import com.salla.push.internal.engine.TagRules

/**
 * The public calls the engine has not applied yet that change what the getters show: login, logout, tag changes and
 * opt-in/opt-out. The getters apply them, in call order, on top of the engine's last published snapshot, so a value read
 * right after a call already reflects it, as if the call had changed the state on the caller's thread. Each rule
 * mirrors the engine's own handling of the call, from what the snapshot publishes, so a getter's value does not change
 * when the engine applies the call. A call leaves this list once the engine has applied it and published the result,
 * or when it is dropped (a full pre-init buffer, SallaPush disabled in this process).
 */
internal class PendingWrites {

    private val lock = Any()
    private val writes = ArrayList<BufferedCall>()

    /** Records [call] before it is handed to the engine thread. Calls that no getter shows are not recorded. */
    fun add(call: BufferedCall) {
        if (!call.changesGetters) return
        synchronized(lock) { writes.add(call) }
    }

    /** The engine applied [call] and published the snapshot that contains it, or [call] was dropped. */
    fun remove(call: BufferedCall) {
        if (!call.changesGetters) return
        synchronized(lock) {
            val iterator = writes.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() === call) {
                    iterator.remove()
                    return
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) { writes.clear() }
    }

    /**
     * The getters' state: [base] with the pending calls applied. The calls are read before the snapshot, so a call the
     * engine removes meanwhile is already in the snapshot that is read. While the identity is unknown (a first launch,
     * a reinstall, or the state not loaded yet), a logout keeps the tags this launch set: the snapshot's
     * [Snapshot.launchTags] with the tag calls made before the logout.
     */
    fun view(base: () -> Snapshot, clearTagsOnLogout: Boolean): Snapshot {
        val pending = synchronized(lock) { if (writes.isEmpty()) null else ArrayList(writes) }
        val snapshot = base()
        if (pending == null) return snapshot
        var loggedIn = snapshot.isLoggedIn
        var externalId = snapshot.externalId
        var logoutIsFree = snapshot.logoutIsFree
        var optedOut = snapshot.userOptedOut
        val tags = LinkedHashMap(snapshot.tags)
        var launchTags = snapshot.launchTags?.let { LinkedHashMap(it) }
        for (call in pending) {
            when (call) {
                is BufferedCall.Login -> {
                    // A login of another customer than the one logged in is an account switch: with clearTagsOnLogout
                    // it drops the tags.
                    if (clearTagsOnLogout && loggedIn && externalId != null && externalId != call.externalId) tags.clear()
                    loggedIn = true
                    externalId = call.externalId
                    logoutIsFree = false
                    launchTags = null
                }
                is BufferedCall.Logout -> if (!logoutIsFree) {
                    loggedIn = false
                    externalId = null
                    if (clearTagsOnLogout) {
                        tags.clear()
                        launchTags?.let { tags.putAll(it) }
                    }
                    // A logout that does not unlink leaves the device signed out and unlinked: the next one is free.
                    logoutIsFree = !snapshot.logoutSends
                    launchTags = null
                }
                is BufferedCall.AddTags -> TagRules.add(tags, call.tags, launchTags)
                is BufferedCall.RemoveTags -> TagRules.remove(tags, call.keys, launchTags)
                is BufferedCall.OptedOut -> optedOut = call.optedOut
                is BufferedCall.Language -> Unit
            }
        }
        return snapshot.copy(
            isLoggedIn = loggedIn,
            externalId = externalId,
            tags = tags,
            userOptedOut = optedOut,
            optedIn = snapshot.permission && !optedOut && snapshot.token != null,
        )
    }
}
