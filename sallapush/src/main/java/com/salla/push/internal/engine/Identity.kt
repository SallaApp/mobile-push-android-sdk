package com.salla.push.internal.engine

import com.salla.push.SallaPushLoginFailure
import com.salla.push.internal.api.ApiFailure
import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.api.TagsResult
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.SecretString

// Identity: login/logout, the collapse rules, the identity op log and the answers to
// its tag clearings, links and unlinks.

/** The identity values SallaPush sends with a link, checked on the caller thread and when identity.json is read. */
internal object IdentityRules {

    /** The longest external id push-service links, in UTF-16 units as it counts them. */
    private const val MAX_EXTERNAL_ID = 128

    /** The longest identity token SallaPush sends: beta.1's cap of the customer token. */
    private const val MAX_IDENTITY_TOKEN = 4096

    /** An external id as SallaPush sends it, trimmed: 1 to 128 UTF-16 units. */
    fun isExternalId(value: String): Boolean = value.length in 1..MAX_EXTERNAL_ID

    /** An identity token as SallaPush sends it, trimmed: 1 to 4,096 characters (code points). */
    fun isIdentityToken(value: String): Boolean = value.codePointCount(0, value.length) in 1..MAX_IDENTITY_TOKEN
}

/** The identity token of the app's last login() and the customer it is for. Memory only: never written or logged. */
internal class IdentityToken(val externalId: String, val token: SecretString)

/**
 * The arguments were checked on the caller thread. The token is kept in memory for this customer: a later login of the
 * same customer replaces it, so a link waiting for its retry, or the link after a re-registration, goes with the
 * freshest one. A login of another customer is an account switch: with clearTagsOnLogout, the previous customer's tags
 * leave the device before the new link, and their unsent changes and the local copy of the tags are dropped.
 */
internal fun Engine.login(externalId: String, token: SecretString) {
    epoch += 1
    applyTrigger()
    identityToken = IdentityToken(externalId, token)
    val previous = if (identity.desired == Desired.CUSTOMER) identity.desiredExternalId else null
    if (previous == externalId) {
        if (identity.ops.any { it.kind == IdentityOpKind.LOGIN && it.externalId == externalId }) {
            // The same link is queued or on the wire: it keeps its place and its waits, and goes with this token.
            logger.log(LogCode.LOGIN_QUEUED) { "Login already queued; this call adds no request." }
            return
        }
        if (identity.serverLink == ServerLink.LINKED && identity.linkedExternalId == externalId &&
            identity.ops.none { it.kind.isLogoutLike }
        ) {
            logger.log(LogCode.LOGIN_SKIPPED_SAME) { "Already linked to this customer; nothing to send." }
            return
        }
    }
    identity.desired = Desired.CUSTOMER
    identity.desiredExternalId = externalId
    identityDirty = true
    // The identity is known from now on: no launch logout of an unknown identity can follow.
    launchTags.clear()
    var tagKeys = emptyList<String>()
    if (previous != null && previous != externalId) {
        val clearing = host.clearTagsOnLogout()
        if (clearing) {
            // Computed before the changes are dropped: the keys the server may hold are those SallaPush holds now.
            tagKeys = tagClearing(emptyMap())
            dropTagChanges(emptyMap())
        }
        logger.log(LogCode.LOGIN_SWITCHED) {
            when {
                !clearing -> "Account switched; this device keeps its tags (clearTagsOnLogout is off)."
                tagKeys.isNotEmpty() -> "Account switched; the previous customer's tags are cleared before the new customer is linked."
                else -> "Account switched; this device holds none of the previous customer's tags."
            }
        }
    }
    enqueueIdentity(IdentityOp(allocateOpId(), IdentityOpKind.LOGIN, externalId, tagKeys.isNotEmpty(), sent = false, retry = Retry(nextAt = now() + Engine.DEBOUNCE_SECONDS), tagKeys = tagKeys))
    if (credentials == null) logger.log(LogCode.LOGIN_QUEUED) { "Login queued (waiting for registration)." }
}

/**
 * A logout now is free: already signed out and not linked, with no login queued, sent or possibly applied.
 * The guest keeps its tags and nothing is sent.
 */
internal fun Engine.logoutIsFree(): Boolean =
    identity.desired == Desired.GUEST && identity.serverLink == ServerLink.NONE && !unknownOutcomeLogin &&
        identity.ops.none { it.kind == IdentityOpKind.LOGIN }

/**
 * Whether a logout now unlinks the device: it may be linked (a link not known to be gone, or a login sent, in flight or
 * of unknown outcome). Unsent logins do not count: the logout removes them first.
 */
internal fun Engine.logoutUnlinks(): Boolean =
    identity.serverLink != ServerLink.NONE || unknownOutcomeLogin ||
        identity.ops.any { it.kind == IdentityOpKind.LOGIN && it.sent }

internal fun Engine.logout() {
    epoch += 1
    applyTrigger()
    identityToken = null
    if (logoutIsFree()) {
        // A guest's launch logout: the server holds no link to clear, so the guest keeps its tags and
        // nothing is sent. A logout that is still pending stays queued.
        logger.log(LogCode.LOGOUT_SKIPPED) { "Already signed out and not linked; logout needs no request." }
        return
    }
    // The identity is unknown (a first launch, a reinstall, a lost identity file): this is the launch logout, which
    // keeps the tags the app set earlier in this launch.
    val kept = if (identity.desired == Desired.UNKNOWN) keptLaunchTags() else emptyMap()
    // Computed before the changes are dropped: the keys the server may hold are those SallaPush holds now.
    val tagKeys = tagClearing(kept)
    identity.desired = Desired.GUEST
    identity.desiredExternalId = null
    identityDirty = true
    if (host.clearTagsOnLogout()) dropTagChanges(kept)
    // Collapse rules: every unsent login goes, and a tag clearing it had still to send passes to this logout.
    val replaced = removeUnsent(IdentityOpKind.LOGIN).toMutableList()
    val unlinks = logoutUnlinks()
    val clearTags = tagKeys.isNotEmpty() || replaced.any { it.clearsTagsNext }
    if ((unlinks || clearTags) && coversLogout(identity.ops.lastOrNull(), clearTags)) {
        // The log already ends with a logout that does what this call would (one that was attempted, is on the wire or
        // waits, or a pending confirmation): it keeps its place and its waits (a 429 or a 24 h client-error wait is
        // never shortened), and a repeated logout costs no request.
        persistDirty()
        logger.log(LogCode.LOGOUT_QUEUED) { "Logout already queued; this call adds no request." }
        return
    }
    replaced += removeUnsent(IdentityOpKind.LOGOUT)
    if (!unlinks && tagKeys.isEmpty() && replaced.none { it.clearsTagsNext }) {
        logger.log(LogCode.LOGOUT_SKIPPED) { "Not linked; logout needs no request." }
        return
    }
    val op = IdentityOp(allocateOpId(), IdentityOpKind.LOGOUT, null, tagKeys.isNotEmpty(), sent = false, retry = Retry(nextAt = now() + Engine.DEBOUNCE_SECONDS), tagKeys = tagKeys)
    for (gone in replaced) op.absorb(gone)
    enqueueIdentity(op)
    // The logout must reach disk before any request can go out.
    persistDirty()
    logLogoutQueued()
}

/**
 * Whether [existing], the last op of the identity log, already does what a new logout would: a logout (or its
 * confirmation) that clears the tags, or that the new one would not ask to clear them; a login covers nothing (the
 * logout must go after it).
 */
private fun coversLogout(existing: IdentityOp?, clearTags: Boolean): Boolean =
    existing != null && existing.kind.isLogoutLike && (existing.clearTags || !clearTags)

/** INFO logout.queued when the new logout cannot be sent at once, saying why. */
private fun Engine.logLogoutQueued() {
    val reason = when {
        credentials == null ->
            "Logout queued until this device is registered; the registration answer settles whether it is still needed."
        deviceRoutesDormant(now()) -> "Logout queued while the SallaPush device routes are closed; it is sent when they reopen."
        inFlight != null -> "Logout queued behind the request in flight."
        else -> return
    }
    logger.log(LogCode.LOGOUT_QUEUED) { reason }
}

/**
 * The collapse rules for unsent identity ops, then the append: [op] takes on the tag clearing an op it replaces
 * had still to send.
 */
internal fun Engine.enqueueIdentity(op: IdentityOp) {
    val replaced = when (op.kind) {
        IdentityOpKind.LOGIN -> removeUnsent(IdentityOpKind.LOGIN)
        IdentityOpKind.LOGOUT -> removeUnsent(IdentityOpKind.LOGIN, IdentityOpKind.LOGOUT)
        IdentityOpKind.LOGOUT_CONFIRM -> emptyList()
    }
    for (gone in replaced) op.absorb(gone)
    identity.ops.add(op)
    identityDirty = true
    scheduleIdentityWork()
}

/**
 * Removes the unsent ops of the given kinds and returns them. A sent op may have changed server state and always stays,
 * in order.
 */
private fun Engine.removeUnsent(vararg kinds: IdentityOpKind): List<IdentityOp> {
    val removed = identity.ops.filter { !it.sent && it.kind in kinds }
    if (removed.isEmpty()) return removed
    identity.ops.removeAll { op -> removed.any { it === op } }
    identityDirty = true
    return removed
}

/**
 * Removes a login op that will never be sent (a later identity call superseded it, or push-service refused it). A
 * request of it that may have reached the server without a usable answer (failed in transit, or in flight when the
 * process died) may still be applied late: that is remembered, so the next logout unlinks and is confirmed. A
 * login the server answered and applied is removed by its answer, never here.
 */
internal fun Engine.dropUnsendableLogin(op: IdentityOp) {
    if (op.sent) unknownOutcomeLogin = true
    identity.ops.remove(op)
    clearAttempts(op.id)
    identityDirty = true
}

/**
 * The tags a logout() keeps while the identity is unknown (as on iOS): those the app set in this launch
 * ([Engine.launchTags]) that the getters still show (an answer or a rejected key may have removed one since).
 */
internal fun Engine.keptLaunchTags(): Map<String, String> {
    if (launchTags.isEmpty()) return emptyMap()
    val effective = effectiveTags()
    val kept = LinkedHashMap<String, String>()
    for ((key, value) in launchTags) if (effective[key] == value) kept[key] = value
    return kept
}

/**
 * What a sign-out or an account switch with clearTagsOnLogout does to SallaPush's copy of the tags: the mirror and
 * every unsent change go, except [kept], the tags the app set in this launch while the identity was unknown.
 * Those are sent again after the identity op. A tags request on the wire may carry them: its op is replaced, so
 * its answer neither settles them nor fills the mirror again.
 */
internal fun Engine.dropTagChanges(kept: Map<String, String>) {
    val tags = device.tags
    tags.mirror.clear()
    tags.pendingAdd.clear()
    tags.pendingRemove.clear()
    for ((key, value) in kept) tags.pendingAdd[key] = PendingTag(value, epoch)
    launchTags.clear()
    if (inFlight == "tags" || kept.isEmpty()) {
        device.ops.removeAll { it.kind == DeviceOpKind.TAGS }
        tagSplit = null
    }
    stateDirty = true
    ensureTagsOp()
}

/**
 * Drops the login ops a later login or logout superseded: only the customer the app asserts now is linked. One that may
 * have reached the server is remembered as an unknown outcome ([dropUnsendableLogin]).
 */
internal fun Engine.pruneIdentityOps() {
    val superseded = identity.ops.filter { op ->
        op.kind == IdentityOpKind.LOGIN && (identity.desired != Desired.CUSTOMER || op.externalId != identity.desiredExternalId)
    }
    for (op in superseded) dropUnsendableLogin(op)
}

/**
 * Whether [op] can be sent now. A login needs the identity token of its customer in memory. Without one (a login queued
 * before a relaunch) it waits, never dropped, for the app's login at this launch (Android's rule: the engine pumps once
 * before that call reaches it). Once the app is in the foreground its launch login has run, so a login still without a
 * token says once per launch what is missing; the start-up pump, a process FCM started and a WorkManager run stay
 * silent.
 */
internal fun Engine.identityReady(op: IdentityOp, now: Double): Boolean {
    if (!opReady(op.retry, op.id, now)) return false
    if (op.kind != IdentityOpKind.LOGIN || identityToken?.externalId == op.externalId) return true
    if (mode != EngineMode.WORKER && host.isForeground()) logTokenNeeded()
    return false
}

/** WARN identity.token_needed, at most once per launch for this app id and API host (as on iOS). */
internal fun Engine.logTokenNeeded() {
    logger.logOnce("token_needed.$key", LogCode.IDENTITY_TOKEN_NEEDED) {
        "SallaPush keeps the identity token in memory only and has none for the signed-in customer, so this device is " +
            "not linked to them; call login(externalId, identityToken) at every launch while the customer is signed in."
    }
}

/**
 * Sends [op]'s next request: its tag clearing, then a login's link or a logout's unlink. False (nothing sent) when a
 * login has no token in memory, or when a logout was left with nothing to send (it is removed).
 */
internal fun Engine.sendIdentity(op: IdentityOp, credentials: Credentials): Boolean {
    val link = if (op.kind == IdentityOpKind.LOGIN) {
        identityToken?.takeIf { it.externalId == op.externalId } ?: return false
    } else {
        null
    }
    // The server is known to hold no tags: there is nothing to clear.
    if (op.clearsTagsNext && identity.serverTags == ServerTags.NONE) finishTagClearing(op)
    if (op.clearsTagsNext) {
        sendTagClearing(op, credentials)
        return true
    }
    if (op.kind == IdentityOpKind.LOGOUT && !logoutUnlinks()) {
        // The device is known to be linked to no customer: the logout had only the tags to clear.
        identity.ops.remove(op)
        clearAttempts(op.id)
        identityDirty = true
        logger.log(LogCode.LOGOUT_SKIPPED) { "Not linked; the logout needs no unlink." }
        return false
    }
    // Whether an earlier request of this op may have changed server state: an answer that applied nothing restores it.
    val sentBefore = op.sent
    op.sent = true
    identityDirty = true
    persistDirty()
    inFlight = op.kind.wire
    val generationNow = generation
    if (link != null) {
        api.link(credentials, link.externalId, link.token) { result ->
            if (generationNow == generation) {
                inFlight = null
                task { handleLinkResult(op, result, sentBefore, link.token) }
            }
        }
    } else {
        api.unlink(credentials) { result ->
            if (generationNow == generation) {
                inFlight = null
                task { handleUnlinkResult(op, result, sentBefore) }
            }
        }
    }
    return true
}

/**
 * Removes the device's tags before [op]'s link or unlink: the keys SallaPush held when the op was queued, or,
 * for an op that names none (beta.1's), the keys it holds now. Its request is not the tags op's ("clear-tags").
 */
private fun Engine.sendTagClearing(op: IdentityOp, credentials: Credentials) {
    val remove = op.tagKeys.ifEmpty { heldKeys() }
    inFlight = "clear-tags"
    val generationNow = generation
    api.tags(credentials, emptyMap(), remove) { result ->
        if (generationNow == generation) {
            inFlight = null
            task { handleTagClearingResult(op, result) }
        }
    }
}

/**
 * The answer to [op]'s tag clearing: the mirror becomes what the server holds, and the link or unlink goes next. A
 * refusal never holds the link or the unlink back. An op a later identity call replaced meanwhile passed its clearing
 * on to that call's op, so only what the server now holds is recorded.
 */
private fun Engine.handleTagClearingResult(op: IdentityOp, result: ApiResult<TagsResult>) {
    if (result is ApiResult.Ok) {
        identity.serverTags = if (result.value.tags.isEmpty()) ServerTags.NONE else ServerTags.SOME
        identityDirty = true
    }
    if (identity.ops.none { it === op }) return
    when (result) {
        is ApiResult.Ok -> {
            // While consent is withdrawn the getters show no tags, so the mirror waits for consent.
            if (withdrawal == null) {
                device.tags.mirror.clear()
                device.tags.mirror.putAll(result.value.tags)
                stateDirty = true
            }
            finishTagClearing(op)
            logger.log(LogCode.TAGS_FLUSHED) {
                if (op.kind == IdentityOpKind.LOGIN) "Tags cleared before the new customer is linked." else "Tags cleared before the unlink."
            }
        }
        is ApiResult.Err -> {
            val failure = result.failure
            if (handleDeviceAnswer(failure)) return
            if (failure.failureClass == FailureClass.CLIENT) {
                finishTagClearing(op)
                logger.log(LogCode.NET_CLIENT_ERROR, failureMetadata(failure)) {
                    "${failure.method} ${failure.route} was rejected (${failure.summary()}); the device's tags were not cleared."
                }
                return
            }
            applyFailure(op.retry, op.id, failure)
            identityDirty = true
        }
    }
}

/** [op]'s tag clearing is done: its link or unlink goes next, with a retry state of its own. */
private fun Engine.finishTagClearing(op: IdentityOp) {
    op.tagsCleared = true
    op.tagKeys = emptyList()
    op.retry.attempts = 0
    op.retry.nextAt = now()
    op.retry.lastFailure = null
    op.retry.dormantUntil = null
    clearAttempts(op.id)
    identityDirty = true
}

/** A 200 links the device to the op's customer, whom the app may have replaced meanwhile: the op behind it follows. */
private fun Engine.handleLinkResult(op: IdentityOp, result: ApiResult<Unit>, sentBefore: Boolean, token: SecretString) {
    when (result) {
        is ApiResult.Ok -> {
            identity.ops.remove(op)
            clearAttempts(op.id)
            identity.serverLink = ServerLink.LINKED
            identity.linkedExternalId = op.externalId
            identityDirty = true
            logger.log(LogCode.LOGIN_OK) { "Customer linked." }
        }
        is ApiResult.Err -> onLinkFailed(op, result.failure, sentBefore, token)
    }
}

/**
 * The link answers push-service gives, matched on status and error code: 403 invalid_identity_token
 * and a coded 400 are final, so the login is reported and not sent again. A 403 of a token the app replaced for that
 * customer while it was on the wire sends the link again with the newer one. Any other coded 4xx is a client error:
 * the op waits 24 h, then is sent again. A 4xx without a code never gets here (it is retried with backoff).
 */
private fun Engine.onLinkFailed(op: IdentityOp, failure: ApiFailure, sentBefore: Boolean, token: SecretString) {
    if (handleDeviceAnswer(failure)) {
        op.sent = false
        identityDirty = true
        return
    }
    if (failure.failureClass != FailureClass.CLIENT) {
        // A transport failure may have reached the server, and a 2xx with an unreadable body was applied: the op stays
        // sent (dropping it later remembers the unknown outcome). An answer that applied nothing changes nothing.
        if (!failure.mayHaveApplied) op.sent = sentBefore
        applyFailure(op.retry, op.id, failure)
        identityDirty = true
        return
    }
    op.sent = sentBefore
    when {
        failure.status == 403 && failure.code == "invalid_identity_token" -> {
            val latest = identityToken?.takeIf { it.externalId == op.externalId }?.token
            if (latest != null && latest != token) {
                op.retry.nextAt = now()
                identityDirty = true
                logger.log(LogCode.LOGIN_QUEUED) {
                    "The identity token was rejected, and a newer one came since; the link is sent again with it."
                }
                return
            }
            dropUnsendableLogin(op)
            if (identityToken?.token == token) identityToken = null
            logger.log(LogCode.LOGIN_REJECTED, failureMetadata(failure)) {
                "The identity token was rejected (403 invalid_identity_token); this device is not linked to the customer. " +
                    "Call login(externalId, identityToken) with a fresh token."
            }
            host.onLoginFailure(this, SallaPushLoginFailure.TOKEN_REJECTED)
        }
        failure.status == 400 -> {
            dropUnsendableLogin(op)
            logger.log(LogCode.IDENTITY_CLIENT_ERROR, failureMetadata(failure)) {
                "${failure.method} ${failure.route} was rejected (${failure.summary()}); the login is not sent again."
            }
            host.onLoginFailure(this, SallaPushLoginFailure.INVALID_REQUEST)
        }
        else -> identityClientError(op, failure)
    }
    identityDirty = true
}

/** A 4xx on an identity route: identity ops are never dropped; the op sleeps 24 h, then is retried. */
private fun Engine.identityClientError(op: IdentityOp, failure: ApiFailure) {
    op.retry.dormantUntil = now() + Engine.DAY_SECONDS
    op.retry.lastFailure = FailureKind.CLIENT
    identityDirty = true
    logger.log(LogCode.IDENTITY_CLIENT_ERROR, failureMetadata(failure)) { "${failure.describe()} was rejected; retrying in 24 h." }
}

/** A logout's unlink, or its confirmation, answered: the device is linked to no customer. */
private fun Engine.handleUnlinkResult(op: IdentityOp, result: ApiResult<Unit>, sentBefore: Boolean) {
    when (result) {
        is ApiResult.Ok -> {
            val position = identity.ops.indexOf(op)
            identity.ops.remove(op)
            clearAttempts(op.id)
            linkCleared()
            logger.log(LogCode.LOGOUT_OK) { "Customer unlinked." }
            if (op.kind == IdentityOpKind.LOGOUT && unknownOutcomeLogin) {
                // A login whose outcome was unknown may be processed late: confirm the logout once, 60 s later. The
                // minute is a deliberate wait (a dormant wait), so no trigger or worker flush sends it early. A late link
                // brings no tags, so the confirmation clears none.
                unknownOutcomeLogin = false
                val confirmAt = now() + LOGOUT_CONFIRM_SECONDS
                insertLogoutConfirm(
                    IdentityOp(allocateOpId(), IdentityOpKind.LOGOUT_CONFIRM, null, clearTags = false, sent = false, retry = Retry(nextAt = confirmAt, dormantUntil = confirmAt)),
                    position,
                )
            }
        }
        is ApiResult.Err -> identityFailure(op, result.failure, sentBefore)
    }
}

/**
 * Puts a logout confirmation where the logout it confirms was ([confirmedAt]), and after any unlinking op still
 * pending: a login the app asked for after that logout stays last, so the server's last word is still the app's last
 * call and the confirmation never signs out the customer who logged in again.
 */
private fun Engine.insertLogoutConfirm(confirm: IdentityOp, confirmedAt: Int) {
    val afterUnlinking = identity.ops.indexOfLast { it.kind.isLogoutLike } + 1
    val position = maxOf(confirmedAt, afterUnlinking).coerceIn(0, identity.ops.size)
    identity.ops.add(position, confirm)
    identityDirty = true
    scheduleIdentityWork()
}

private fun Engine.identityFailure(op: IdentityOp, failure: ApiFailure, sentBefore: Boolean) {
    if (handleDeviceAnswer(failure)) {
        op.sent = false
        identityDirty = true
        return
    }
    // An answer that applied nothing (identity routes are transactional) leaves the op as it was before this request:
    // the collapse rules may replace an op no request of which reached the server.
    if (!failure.mayHaveApplied) op.sent = sentBefore
    identityDirty = true
    if (failure.failureClass == FailureClass.CLIENT) {
        identityClientError(op, failure)
        return
    }
    applyFailure(op.retry, op.id, failure)
}

/** The server confirmed this device is linked to no customer. Its tags are not touched: an unlink keeps them. */
internal fun Engine.linkCleared() {
    identity.serverLink = ServerLink.NONE
    identity.linkedExternalId = null
    identityDirty = true
}

private const val LOGOUT_CONFIRM_SECONDS = 60.0
