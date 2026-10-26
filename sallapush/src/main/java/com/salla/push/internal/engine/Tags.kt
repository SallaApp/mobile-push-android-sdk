package com.salla.push.internal.engine

import com.salla.push.internal.api.ApiFailure
import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.api.TagsResult
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.LogSanitizer
import com.salla.push.internal.log.Meta
import com.salla.push.internal.util.asciiLowercase
import com.salla.push.internal.util.truncatedUtf16

// Tags: normalised locally, sent as one merged PATCH, never while an identity op is pending. A 400 never
// drops valid pending tags.

private val TAG_KEY = Regex("^[a-z0-9_]{1,64}$")
private const val MAX_TAGS = 100
private const val MAX_VALUE_LENGTH = 255

/** How much of an invalid raw tag key a warning shows. */
private const val SHOWN_KEY_LENGTH = 64

/**
 * The tag rules as pure functions, shared by the engine and the getters' overlay of calls not applied yet, so
 * getTags() shows a change exactly as the engine will apply it.
 */
internal object TagRules {

    /** The key trimmed and ASCII-lowercased; null when it is still not a-z, 0-9, _ (max 64). */
    fun key(raw: String): String? = asciiLowercase(raw.trim()).takeIf { TAG_KEY.matches(it) }

    /** The value as stored: at most 255 UTF-16 units, cut on whole code points. */
    fun value(raw: String): String = truncatedUtf16(raw, MAX_VALUE_LENGTH)

    /** Whether a key not among [current] tags may be added: at most 100 tags. */
    fun roomFor(current: Int): Boolean = current < MAX_TAGS

    /**
     * Applies an addTags call to the effective tags: an empty value removes the key; extra keys beyond 100 are dropped.
     * [launch], when not null, gets the same changes the effective tags got: the tags this launch set.
     */
    fun add(tags: MutableMap<String, String>, changes: Map<String, String>, launch: MutableMap<String, String>?) {
        for ((rawKey, rawValue) in changes) {
            val key = key(rawKey) ?: continue
            if (rawValue.isEmpty()) {
                tags.remove(key)
                launch?.remove(key)
            } else if (key in tags || roomFor(tags.size)) {
                val value = value(rawValue)
                tags[key] = value
                launch?.put(key, value)
            }
        }
    }

    /** Applies a removeTags call to the effective tags, and to [launch] when not null (see [add]). */
    fun remove(tags: MutableMap<String, String>, keys: Collection<String>, launch: MutableMap<String, String>?) {
        for (raw in keys) {
            key(raw)?.let { key ->
                tags.remove(key)
                launch?.remove(key)
            }
        }
    }

    /**
     * Whether adding [value] needs no request: the mirror holds that value and is what the server holds
     * ([mirrorIsServerTruth]). The tags the app sees are the same either way, so the getters need no rule of their own.
     */
    fun addChangesNothing(mirrorValue: String?, value: String, mirrorIsServerTruth: Boolean): Boolean =
        mirrorIsServerTruth && mirrorValue == value

    /**
     * Whether removing a key needs no request: the key is not in the mirror, the server holds no tags at all, and no
     * tags request on the wire can add it.
     */
    fun removeChangesNothing(inMirror: Boolean, serverHoldsNoTags: Boolean, tagsRequestOnTheWire: Boolean): Boolean =
        !inMirror && serverHoldsNoTags && !tagsRequestOnTheWire
}

/**
 * Trims and lowercases a key; null (with a warning) when it is still not a-z, 0-9, _ (max 64). The app's raw key is
 * shown cleaned of control characters, so it can never break or forge a log line.
 */
internal fun Engine.normalizeTagKey(raw: String): String? {
    val key = TagRules.key(raw)
    if (key == null) {
        logger.log(LogCode.TAGS_INVALID_KEY) {
            val shown = LogSanitizer.clean(raw, SHOWN_KEY_LENGTH)
            "Tag key \"$shown\" is invalid after lowercasing (a-z, 0-9, _; max 64); dropped."
        }
        return null
    }
    if (key != raw) logger.log(LogCode.TAGS_NORMALIZED) { "Tag key ${LogSanitizer.clean(raw)} sent as $key." }
    return key
}

internal fun Engine.addTags(tags: Map<String, String>) {
    val state = device.tags
    var limited = 0
    for ((rawKey, rawValue) in tags) {
        val key = normalizeTagKey(rawKey) ?: continue
        if (rawValue.isEmpty()) {
            removeTagKey(key)
            continue
        }
        val value = TagRules.value(rawValue)
        if (value.length != rawValue.length) {
            logger.log(LogCode.TAGS_VALUE_TRUNCATED) { "Tag value for \"$key\" was longer than 255 characters; truncated." }
        }
        val known = (key in state.mirror && key !in state.pendingRemove) || key in state.pendingAdd
        if (!known && !TagRules.roomFor(effectiveTagCount())) {
            limited += 1
            continue
        }
        recordLaunchTag(key, value)
        state.pendingRemove.remove(key)
        if (TagRules.addChangesNothing(state.mirror[key], value, mirrorIsServerTruth())) {
            // The server already holds this value: a pending change of the key is dropped, and nothing is sent for it.
            state.pendingAdd.remove(key)
        } else {
            state.pendingAdd[key] = PendingTag(value, epoch)
        }
    }
    if (limited > 0) {
        val additions = if (limited == 1) "addition" else "additions"
        logger.log(LogCode.TAGS_LIMIT, mapOf(Meta.COUNT to limited.toString())) {
            "More than 100 tags; $limited $additions dropped."
        }
    }
    stateDirty = true
    ensureTagsOp()
}

internal fun Engine.removeTags(keys: Collection<String>) {
    for (raw in keys) {
        val key = normalizeTagKey(raw) ?: continue
        removeTagKey(key)
    }
    stateDirty = true
    ensureTagsOp()
}

private fun Engine.removeTagKey(key: String) {
    val state = device.tags
    state.pendingAdd.remove(key)
    launchTags.remove(key)
    val changesNothing = TagRules.removeChangesNothing(
        inMirror = key in state.mirror,
        serverHoldsNoTags = identity.serverTags == ServerTags.NONE && tagsReachTheKnownRow(),
        tagsRequestOnTheWire = inFlight == "tags",
    )
    if (changesNothing) state.pendingRemove.remove(key) else state.pendingRemove[key] = epoch
}

/**
 * Whether the tag mirror is what the server holds, now and when the next tags request can go: the device is
 * registered ([tagsReachTheKnownRow]), the last tags answer reported tags, no tags request is on the wire, and no
 * identity op queued before that request still clears the server's tags (a logout, or a login of another customer,
 * with clearTagsOnLogout).
 */
private fun Engine.mirrorIsServerTruth(): Boolean =
    identity.serverTags == ServerTags.SOME && tagsReachTheKnownRow() && inFlight != "tags" &&
        identity.ops.none { it.clearsTagsNext }

/** Every tag key SallaPush holds, sorted: the server's last answer and the pending changes. */
internal fun Engine.heldKeys(): List<String> {
    val tags = device.tags
    return (tags.mirror.keys + tags.pendingAdd.keys + tags.pendingRemove.keys).distinct().sorted()
}

/**
 * The tag keys a logout or an account switch with clearTagsOnLogout removes from the server before its unlink or link,
 * except [kept]; none when it clears nothing. The server may hold tags when they are not known to be none,
 * or a tags request is on the wire. When the mirror is what the server holds, only its keys can be there; otherwise
 * every key SallaPush holds.
 */
internal fun Engine.tagClearing(kept: Map<String, String>): List<String> {
    if (!host.clearTagsOnLogout()) return emptyList()
    if (identity.serverTags == ServerTags.NONE && inFlight != "tags") return emptyList()
    val keys = if (mirrorIsServerTruth()) device.tags.mirror.keys.sorted() else heldKeys()
    return keys.filter { it !in kept }
}

/**
 * Records a tag the app set while the identity is unknown, for the launch logout(). At the limit the
 * tags the getters no longer show go first, so at most 100 are kept (as on iOS).
 */
private fun Engine.recordLaunchTag(key: String, value: String) {
    if (identity.desired != Desired.UNKNOWN) return
    if (key !in launchTags && !TagRules.roomFor(launchTags.size)) {
        val effective = effectiveTags()
        launchTags.entries.removeAll { effective[it.key] != it.value }
    }
    launchTags[key] = value
}

/**
 * Whether the next tags request reaches the row that server_tags and the mirror describe. While the device registers
 * again (its credentials are dropped until that is answered) the row is not known, and the answer sets server_tags
 * again. Until then neither suppression applies.
 */
private fun Engine.tagsReachTheKnownRow(): Boolean = credentials != null

private fun Engine.effectiveTagCount(): Int {
    val state = device.tags
    var count = 0
    for (key in state.mirror.keys) if (key !in state.pendingRemove && key !in state.pendingAdd) count += 1
    return count + state.pendingAdd.size
}

internal fun Engine.ensureTagsOp(delaySeconds: Double? = null) {
    if (mode.isDrain || withdrawal != null) return
    val state = device.tags
    if (state.pendingAdd.isEmpty() && state.pendingRemove.isEmpty()) {
        dropIdleTagsOp()
        return
    }
    val delay = delaySeconds ?: if (host.isForeground()) Engine.TAGS_DEBOUNCE_SECONDS else 0.0
    val existing = device.ops.firstOrNull { it.kind == DeviceOpKind.TAGS }
    if (existing == null) {
        device.ops.add(DeviceOp(allocateOpId(), DeviceOpKind.TAGS, Retry(nextAt = now() + delay)))
        stateDirty = true
    } else if (existing.retry.attempts == 0 && existing.retry.nextAt > now() + delay) {
        existing.retry.nextAt = now() + delay
        stateDirty = true
    }
}

/**
 * Removes a tags op left with nothing to send, unless its request is on the wire (its answer still updates the mirror)
 * or it waits after a failure ([tagsOpWaits]).
 */
private fun Engine.dropIdleTagsOp() {
    if (inFlight == "tags") return
    val op = device.ops.firstOrNull { it.kind == DeviceOpKind.TAGS } ?: return
    if (tagsOpWaits(op, now())) return
    device.ops.remove(op)
    clearAttempts(op.id)
    tagSplit = null
    stateDirty = true
}

/**
 * A tags op waiting after a failure (a backoff, a 429 Retry-After, a day after a client error) is kept even when
 * nothing is left to send, until the wait ends: a tag change made meanwhile waits for it too, so the wait is never
 * shortened.
 */
private fun Engine.tagsOpWaits(op: DeviceOp, now: Double): Boolean =
    op.retry.lastFailure != null && !opReady(op.retry, op.id, now)

/**
 * When changes left pending by a tags answer go out: in the foreground at most every 10 s after the previous tags
 * request, at once in the background or during a WorkManager run.
 */
private fun Engine.tagsNextSendAt(now: Double): Double {
    val last = lastTagsSentAt ?: return now
    return if (host.isForeground() && !workerAttached) maxOf(now, last + Engine.TAGS_DEBOUNCE_SECONDS) else now
}

/** On background: pending tags flush at once. */
internal fun Engine.flushTagsNow() {
    val op = device.ops.firstOrNull { it.kind == DeviceOpKind.TAGS } ?: return
    if (op.retry.attempts == 0 && op.retry.nextAt > now()) {
        op.retry.nextAt = now()
        stateDirty = true
    }
}

internal fun Engine.tagsReady(now: Double): Boolean {
    val op = device.ops.firstOrNull { it.kind == DeviceOpKind.TAGS } ?: return false
    val state = device.tags
    if (state.pendingAdd.isEmpty() && state.pendingRemove.isEmpty()) {
        if (tagsOpWaits(op, now)) return false
        device.ops.remove(op)
        clearAttempts(op.id)
        tagSplit = null
        stateDirty = true
        return false
    }
    return opReady(op.retry, op.id, now)
}

/** Sends the merged (or the single isolated) tag change; false when there was nothing to send. */
internal fun Engine.sendTags(credentials: Credentials): Boolean {
    val op = device.ops.firstOrNull { it.kind == DeviceOpKind.TAGS } ?: return false
    val state = device.tags
    val add = LinkedHashMap<String, String>()
    val remove = ArrayList<String>()
    val split = tagSplit
    var single: String? = null
    if (split != null) {
        while (split.isNotEmpty() && single == null) {
            val key = split.first()
            val pendingValue = state.pendingAdd[key]
            when {
                pendingValue != null -> {
                    add[key] = pendingValue.value
                    single = key
                }
                key in state.pendingRemove -> {
                    remove.add(key)
                    single = key
                }
                else -> split.removeFirst()
            }
        }
        if (single == null) tagSplit = null
    }
    if (single == null) {
        for ((key, tag) in state.pendingAdd) add[key] = tag.value
        remove.addAll(state.pendingRemove.keys)
    }
    if (add.isEmpty() && remove.isEmpty()) return false
    inFlight = "tags"
    lastTagsSentAt = now()
    val generationNow = generation
    val singleKey = single
    api.tags(credentials, add, remove) { result ->
        if (generationNow == generation) {
            inFlight = null
            task { handleTagsResult(op, result, add, remove, singleKey) }
        }
    }
    return true
}

private fun Engine.handleTagsResult(
    op: DeviceOp,
    result: ApiResult<TagsResult>,
    sentAdd: Map<String, String>,
    sentRemove: List<String>,
    singleKey: String?,
) {
    if (result is ApiResult.Err && result.failure.mayHaveApplied) tagsMayHaveChanged(addedAny = sentAdd.isNotEmpty())
    if (device.ops.none { it === op }) {
        // The op was dropped while its request was on the wire (a logout cleared the tags, or consent was withdrawn):
        // the answer must never restore the previous customer's tags. Only what the server now holds is recorded.
        if (result is ApiResult.Ok) {
            identity.serverTags = if (result.value.tags.isEmpty()) ServerTags.NONE else ServerTags.SOME
            identityDirty = true
        }
        return
    }
    val state = device.tags
    when (result) {
        is ApiResult.Ok -> {
            for ((key, value) in sentAdd) if (state.pendingAdd[key]?.value == value) state.pendingAdd.remove(key)
            for (key in sentRemove) state.pendingRemove.remove(key)
            state.mirror.clear()
            state.mirror.putAll(result.value.tags)
            identity.serverTags = if (result.value.tags.isEmpty()) ServerTags.NONE else ServerTags.SOME
            identityDirty = true
            if (singleKey != null) tagSplit?.remove(singleKey)
            if (tagSplit?.isEmpty() == true) tagSplit = null
            clearAttempts(op.id)
            if (state.pendingAdd.isEmpty() && state.pendingRemove.isEmpty()) {
                device.ops.remove(op)
                tagSplit = null
            } else {
                op.retry.attempts = 0
                op.retry.lastFailure = null
                // Isolating a rejected key goes on at once; changes made meanwhile wait for the foreground debounce.
                op.retry.nextAt = if (tagSplit != null) now() else tagsNextSendAt(now())
            }
            stateDirty = true
            logger.log(LogCode.TAGS_FLUSHED) { "Tags updated (+${sentAdd.size} −${sentRemove.size})." }
        }
        is ApiResult.Err -> {
            val failure = result.failure
            if (handleDeviceAnswer(failure)) return
            when {
                failure.failureClass == FailureClass.CLIENT && failure.status == 400 -> onTagsRejected(op, failure, sentAdd, sentRemove, singleKey)
                failure.failureClass == FailureClass.CLIENT -> {
                    op.retry.dormantUntil = now() + Engine.DAY_SECONDS
                    op.retry.lastFailure = FailureKind.CLIENT
                    stateDirty = true
                    logger.log(LogCode.NET_CLIENT_ERROR, failureMetadata(failure)) { "${failure.describe()} was rejected; retrying in 24 h." }
                }
                else -> {
                    applyFailure(op.retry, op.id, failure)
                    stateDirty = true
                }
            }
        }
    }
}

/**
 * A tags request got no usable answer (a transport failure, a timeout, an unreadable 2xx): it may have changed the
 * server's tags (the rule of both SDKs). Tags it may have added mean "none" is no longer known, and any change means
 * the mirror no longer stands for what the server holds: either way they are unknown until a tags answer says, so a
 * logout that clears tags (a sign-out, a consent withdrawal) still goes, and no change is taken as already held. A
 * request that only removed tags cannot have added any. An answered error applied nothing.
 */
private fun Engine.tagsMayHaveChanged(addedAny: Boolean) {
    if (identity.serverTags != ServerTags.SOME && !addedAny) return
    identity.serverTags = ServerTags.UNKNOWN
    identityDirty = true
}

/**
 * A 400 on tags: first re-validate what was sent and drop only invalid keys or values. If everything is valid
 * locally, split once into single-key requests to isolate the key the server refuses, and drop only that key.
 */
private fun Engine.onTagsRejected(op: DeviceOp, failure: ApiFailure, sentAdd: Map<String, String>, sentRemove: List<String>, singleKey: String?) {
    val state = device.tags
    if (singleKey != null) {
        state.pendingAdd.remove(singleKey)
        state.pendingRemove.remove(singleKey)
        tagSplit?.remove(singleKey)
        if (tagSplit?.isEmpty() == true) tagSplit = null
        logger.log(LogCode.TAGS_REJECTED_KEY, failureMetadata(failure)) { "The server rejected tag \"$singleKey\"; it was dropped and the other tags were kept." }
    } else {
        var dropped = 0
        for ((key, value) in sentAdd) {
            if (!TAG_KEY.matches(key) || value.isEmpty() || value.length > MAX_VALUE_LENGTH) {
                state.pendingAdd.remove(key)
                dropped += 1
                logger.log(LogCode.TAGS_REJECTED_KEY, failureMetadata(failure)) {
                    "Tag \"${LogSanitizer.clean(key, SHOWN_KEY_LENGTH)}\" is not valid; it was dropped and the other " +
                        "tags were kept."
                }
            }
        }
        for (key in sentRemove) {
            if (!TAG_KEY.matches(key)) {
                state.pendingRemove.remove(key)
                dropped += 1
                logger.log(LogCode.TAGS_REJECTED_KEY, failureMetadata(failure)) {
                    "Tag \"${LogSanitizer.clean(key, SHOWN_KEY_LENGTH)}\" is not valid; it was dropped and the other " +
                        "tags were kept."
                }
            }
        }
        if (dropped == 0) {
            val keys = ArrayDeque<String>()
            keys.addAll(sentAdd.keys)
            keys.addAll(sentRemove)
            tagSplit = keys
        }
    }
    op.retry.nextAt = now()
    stateDirty = true
}
