package com.salla.push.internal.engine

import com.salla.push.internal.log.LogCode

// A new device row: push-service answers every registration with a new row, without a link or tags.

/**
 * After every registration answer: the new row has no link and no tags. A queued logout has nothing left to do there,
 * and neither has a tag clearing; a link sent on the previous row no longer matters, so the login ops are sent again.
 * Unless consent is withdrawn, the asserted customer is linked again (with the identity token in memory; without one,
 * the login waits for the app's login), and the tags SallaPush holds are sent again. Tags only go once no identity op
 * is pending, so after that link.
 */
internal fun Engine.afterRegistration() {
    identity.serverLink = ServerLink.NONE
    identity.linkedExternalId = null
    identity.serverTags = ServerTags.NONE
    identityDirty = true
    unknownOutcomeLogin = false
    val settled = identity.ops.filter { it.kind.isLogoutLike }
    for (op in settled) {
        identity.ops.remove(op)
        clearAttempts(op.id)
    }
    for (op in identity.ops) {
        op.sent = false
        if (op.clearsTagsNext) {
            op.tagsCleared = true
            op.tagKeys = emptyList()
        }
    }
    if (settled.isNotEmpty()) {
        logger.log(LogCode.LOGOUT_SKIPPED) { "This device is registered without a link or tags to clear; the queued logout needs no request." }
    }
    if (withdrawal != null) return
    val customer = identity.desiredExternalId
    if (identity.desired == Desired.CUSTOMER && customer != null && identity.ops.none { it.kind == IdentityOpKind.LOGIN }) {
        enqueueIdentity(IdentityOp(allocateOpId(), IdentityOpKind.LOGIN, customer, clearTags = false, sent = false, retry = Retry(nextAt = now())))
    }
    val tags = device.tags
    for ((key, value) in tags.mirror) {
        if (key !in tags.pendingAdd && key !in tags.pendingRemove) tags.pendingAdd[key] = PendingTag(value, epoch)
    }
    tags.mirror.clear()
    tags.pendingRemove.clear()
    stateDirty = true
    ensureTagsOp()
}
