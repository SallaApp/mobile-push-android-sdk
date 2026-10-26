package com.salla.push.internal.engine

import com.salla.push.internal.api.ApiFailure
import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.log.LogCode
import org.json.JSONObject

/**
 * Consent. Required = setConsentRequired(true) was called in this process, or the
 * configuration requires it; initialisation never lowers it, and setConsentRequired(false) lowers only what an
 * earlier call raised. The given flag is persisted globally (consent.json). Touched on the engine thread only.
 */
internal class ConsentState {
    var requiredByCall = false
    var requiredByConfig = false

    /** null until loaded from consent.json or set in this process. */
    var given: Boolean? = null

    /** consent.json was read (an answer, no file, or a corrupt one); nothing is written to it before. */
    var loaded = false
    var waitingLogged = false

    val required: Boolean
        get() = requiredByCall || requiredByConfig

    val satisfied: Boolean
        get() = !required || given == true
}

/** The final data-minimising requests of a consent withdrawal, named as log lines name them. */
internal enum class WithdrawalStep(val label: String) {
    OPT_OUT("opt-out"),
    CLEAR_TAGS("tag clearing"),
    UNLINK("unlink"),
}

/**
 * A consent withdrawal, in memory: one opt-out PUT when registered, then one PATCH removing [tagKeys] when the device
 * may have tags, then one unlink when it may be linked, each retried in this process only; then the engine stays idle
 * until consent is given again ([finished]). The tag clearing and the unlink are decided only when nothing else is on
 * the wire, so a login or a tags request that was in flight at the withdrawal counts once it is answered. What the
 * server applied and what it refused make the consent.revoked line. [registeredAtWithdrawal]: the device had
 * credentials when consent was withdrawn (an answer that lands meanwhile may drop them). [tagKeys]: the keys SallaPush
 * held at the withdrawal, with those an identity op had still to clear.
 */
internal class Withdrawal(val registeredAtWithdrawal: Boolean, val tagKeys: List<String>) {
    /** Cleared once answered or refused for good; a withdrawal that runs without credentials has nothing to opt out. */
    var optOutPending = true
    var stepsDecided = false
    var clearTagsPending = false
    var unlinkPending = false

    /** Whether the tag clearing and the unlink were needed when they were decided. */
    var clearTagsNeeded = false
    var unlinkNeeded = false
    var optedOut = false
    var tagsCleared = false
    var unlinked = false

    /** The steps the server refused, or that could not be sent in this process, each with why. */
    val problems = ArrayList<String>()

    /** The last transient failure of the step being retried, named if this process runs out of retries. */
    var lastFailure: String? = null
    var finished = false
    val retry = Retry()
}

/**
 * Consent withdrawn after it was given: drop what is unsent, write the minimised state once, then
 * minimise what the server holds. The rule: while consent is withheld nothing is written but
 * consent.json, except the one minimised state a withdrawal saves before its requests (this write).
 *
 * Only the written copy assumes the withdrawal's requests took effect (as iOS's persistCopies): a link and tags that
 * may remain are written as unknown, so a later launch never skips its login and resends what it needs. In memory the
 * link and the server's tags stay as known, so the withdrawal's unlink is decided from what the server answers to the
 * requests on the wire: one answered with an error applied nothing, one that got no usable answer may have applied.
 */
internal fun Engine.withdrawConsent() {
    if (withdrawal != null) return
    // No WorkManager run may send anything after the withdrawal, in this process or a new one.
    env.work.cancel(key.appId, key.hostTag)
    flushWorkDueAt = null
    // Counted before anything is dropped: a request on the wire may still link the device or leave tags.
    val mayBeLinked = mayBeLinked()
    val mayHaveTags = mayHaveTags()
    val pendingClearing = identity.ops.filter { it.clearsTagsNext }.flatMap { it.tagKeys }
    val tagKeys = (heldKeys() + pendingClearing).distinct().sorted()
    dropForWithdrawal()
    cancelTimers()
    // The withdrawal's one write: personal data the server may no longer hold leaves the disk, and a later launch, with
    // consent given again or no longer required, starts from what the withdrawal leaves on the server.
    persistWithdrawalState(withdrawnIdentity(mayBeLinked, mayHaveTags))
    withdrawal = Withdrawal(registeredAtWithdrawal = credentials != null, tagKeys = tagKeys)
}

/**
 * What the withdrawal drops from memory and disk alike: unsent non-identity ops, tag changes, the tag mirror, pending
 * events and the sent fingerprint (a later consent sends opted_in again). The register op (and a re-registration hold
 * on it) and the identity ops stay for a later consent. The getters show no external id ([Engine.visibleExternalId])
 * and no tags at once.
 */
private fun Engine.dropForWithdrawal() {
    device.ops.removeAll { it.kind != DeviceOpKind.REGISTER }
    device.tags.pendingAdd.clear()
    device.tags.pendingRemove.clear()
    device.tags.mirror.clear()
    launchTags.clear()
    tagSplit = null
    device.registration.sentFingerprint = null
    events.pending.clear()
    eventsFlushRequested = false
    eventsFirstPendingAt = null
    identityDirty = true
    stateDirty = true
    eventsDirty = true
}

/**
 * identity.json as the withdrawal writes it: memory's, with a link and tags that may remain unknown, and without the
 * customer's external id: the desired one and the logins that carry it leave the disk (a sent login is kept as a link
 * that may remain). After a relaunch, a customer without an id reads as unknown. Memory keeps both, so consent given
 * again in this process links the customer again.
 */
private fun Engine.withdrawnIdentity(mayBeLinked: Boolean, mayHaveTags: Boolean): IdentityState {
    val written = identity.copyForWrite()
    written.desiredExternalId = null
    written.ops.removeAll { it.kind == IdentityOpKind.LOGIN }
    if (mayBeLinked) {
        written.serverLink = ServerLink.UNKNOWN
        written.linkedExternalId = null
    }
    if (mayHaveTags) written.serverTags = ServerTags.UNKNOWN
    return written
}

/**
 * The server may hold a link of this device: a link that is not known to be gone, or a login that may have reached it
 * without a usable answer (it is still marked sent, or its op was dropped as an unknown outcome). A login answered
 * with an error applied nothing, and a registration makes a new row, which has no link.
 */
private fun Engine.mayBeLinked(): Boolean =
    identity.serverLink != ServerLink.NONE || unknownOutcomeLogin ||
        identity.ops.any { it.kind == IdentityOpKind.LOGIN && it.sent }

/**
 * The server may hold tags of this device: they are not known to be none (a tags request that got no usable answer
 * left them unknown), or a tags request is on the wire. A registration makes a new row, which has no tags.
 */
private fun Engine.mayHaveTags(): Boolean =
    identity.serverTags != ServerTags.NONE || inFlight == "tags"

internal fun Engine.pumpWithdrawal(state: Withdrawal, now: Double) {
    if (state.finished) {
        answerWaiters(FlushOutcome.SUCCESS)
        return
    }
    val creds = credentials
    if (creds == null) {
        finishWithdrawal(state)
        return
    }
    if (!state.optOutPending && !state.stepsDecided) {
        // Nothing is on the wire here: decided from what the server answered since the withdrawal.
        state.stepsDecided = true
        state.clearTagsNeeded = mayHaveTags()
        state.clearTagsPending = state.clearTagsNeeded
        state.unlinkNeeded = mayBeLinked()
        state.unlinkPending = state.unlinkNeeded
    }
    val step = when {
        state.optOutPending -> WithdrawalStep.OPT_OUT
        state.clearTagsPending -> WithdrawalStep.CLEAR_TAGS
        state.unlinkPending -> WithdrawalStep.UNLINK
        else -> null
    }
    if (step == null) {
        finishWithdrawal(state)
        return
    }
    if (!opReady(state.retry, Engine.WITHDRAWAL_KEY, now)) {
        if (Engine.WITHDRAWAL_KEY in exhausted) {
            // The retries of this process ran out: the withdrawal ends here, and says what was not sent.
            val laterSteps = when (step) {
                WithdrawalStep.OPT_OUT -> mayHaveTags() || mayBeLinked()
                WithdrawalStep.CLEAR_TAGS -> state.unlinkPending
                WithdrawalStep.UNLINK -> false
            }
            state.problems.add(
                "the ${step.label} could not be sent in this process (${state.lastFailure ?: "no answer"})" +
                    if (laterSteps) ", so the later steps were not sent either" else "",
            )
            finishWithdrawal(state)
        } else {
            scheduleWake(state.retry.nextAt)
        }
        return
    }
    val generationNow = generation
    when (step) {
        WithdrawalStep.OPT_OUT -> {
            inFlight = "withdraw-update"
            api.update(creds, JSONObject().put("opted_in", false)) { result ->
                if (generationNow == generation) {
                    inFlight = null
                    task { onWithdrawalAnswer(state, step, (result as? ApiResult.Err)?.failure) }
                }
            }
        }
        WithdrawalStep.CLEAR_TAGS -> {
            inFlight = "withdraw-tags"
            api.tags(creds, emptyMap(), state.tagKeys) { result ->
                if (generationNow == generation) {
                    inFlight = null
                    task {
                        if (result is ApiResult.Ok) {
                            identity.serverTags = if (result.value.tags.isEmpty()) ServerTags.NONE else ServerTags.SOME
                            identityDirty = true
                        }
                        onWithdrawalAnswer(state, step, (result as? ApiResult.Err)?.failure)
                    }
                }
            }
        }
        WithdrawalStep.UNLINK -> {
            inFlight = "withdraw-unlink"
            api.unlink(creds) { result ->
                if (generationNow == generation) {
                    inFlight = null
                    task { onWithdrawalAnswer(state, step, (result as? ApiResult.Err)?.failure) }
                }
            }
        }
    }
}

private fun Engine.onWithdrawalAnswer(state: Withdrawal, step: WithdrawalStep, failure: ApiFailure?) {
    if (failure == null) applyWithdrawalStep(state, step)
    // Consent came back while this request was on the wire: its effect is applied, nothing else happens.
    if (withdrawal !== state) return
    if (failure != null && failure.failureClass != FailureClass.CLIENT) {
        state.lastFailure = failure.summary()
        applyFailure(state.retry, Engine.WITHDRAWAL_KEY, failure)
        return
    }
    // Done, or refused for good: nothing more can be minimised without registering again, which consent rules out.
    if (failure != null) withdrawalRefused(state, step, failure)
    when (step) {
        WithdrawalStep.OPT_OUT -> state.optOutPending = false
        WithdrawalStep.CLEAR_TAGS -> state.clearTagsPending = false
        WithdrawalStep.UNLINK -> state.unlinkPending = false
    }
    state.lastFailure = null
    state.retry.attempts = 0
    state.retry.nextAt = 0.0
    state.retry.lastFailure = null
    clearAttempts(Engine.WITHDRAWAL_KEY)
}

/**
 * The server refused a withdrawal step for good (a 4xx with an error code): logged with what support needs, as any
 * refused request is (routes closed is a WARN, anything else an ERROR), and named in consent.revoked.
 */
private fun Engine.withdrawalRefused(state: Withdrawal, step: WithdrawalStep, failure: ApiFailure) {
    state.problems.add("the ${step.label} was refused (${failure.summary()})")
    val metadata = failureMetadata(failure)
    if (failure.status == 404 && failure.code == "not_found") {
        logger.log(LogCode.NET_ROUTES_CLOSED, metadata) {
            "SallaPush device routes are closed (404 not_found); the consent withdrawal's ${step.label} was not " +
                "applied."
        }
    } else {
        logger.log(LogCode.NET_CLIENT_ERROR, metadata) {
            "${failure.describe()} was rejected; the consent withdrawal's ${step.label} was not applied."
        }
    }
}

/** The server applied a withdrawal step: the in-memory state follows, and is written once consent is given again. */
private fun Engine.applyWithdrawalStep(state: Withdrawal, step: WithdrawalStep) {
    when (step) {
        WithdrawalStep.OPT_OUT -> {
            state.optedOut = true
            device.registration.sentFingerprint = null
            stateDirty = true
        }
        WithdrawalStep.CLEAR_TAGS -> state.tagsCleared = true
        WithdrawalStep.UNLINK -> {
            state.unlinked = true
            linkCleared()
        }
    }
}

private fun Engine.finishWithdrawal(state: Withdrawal) {
    if (state.finished) return
    state.finished = true
    cancelTimers()
    answerWaiters(FlushOutcome.SUCCESS)
    logger.log(LogCode.CONSENT_REVOKED) { "Consent withdrawn; ${withdrawalSummary(state)}. SallaPush is now idle." }
}

/**
 * What the server was told, in words (the iOS summary): what it applied, then each step it refused or that could not
 * be sent. "Opted out" is said only when the opt-out PUT was answered 2xx, "unlinked" only for the unlink, "its tags
 * were cleared" only for the tag clearing.
 */
private fun Engine.withdrawalSummary(state: Withdrawal): String {
    if (credentials == null) {
        // An answer that landed during the withdrawal dropped the credentials (the server no longer knew them).
        return if (state.registeredAtWithdrawal) {
            "the server refused this device's credentials meanwhile, so it could not be told"
        } else {
            "this device was not registered, so the server holds nothing to minimise"
        }
    }
    val applied = ArrayList<String>(2)
    if (state.optedOut) applied.add("opted out")
    if (state.unlinked) applied.add("unlinked")
    val parts = ArrayList<String>()
    if (applied.isNotEmpty()) parts.add(applied.joinToString(" and ") + " on the server")
    if (state.tagsCleared) parts.add("its tags were cleared")
    if (state.optedOut && state.stepsDecided && !state.clearTagsNeeded && !state.unlinkNeeded) {
        parts.add("it held no link or tags to clear")
    }
    parts.addAll(state.problems)
    return if (parts.isEmpty()) "the server could not be told in this process" else parts.joinToString("; ")
}

/**
 * Consent given again in this process: the same engine resumes with its in-memory state, which reflects every answer
 * the server gave since the withdrawal. Memory then becomes what the withdrawal wrote, with what the server confirmed
 * since (as on iOS): a link and tags that may remain are unknown (a withdrawal request still on the wire may land
 * after this, and so may the request that was on the wire before it), and the next update re-sends opted_in.
 * Everything is written out again, and the signed-in customer is logged in again unless the link is known.
 */
internal fun Engine.resumeAfterConsent() {
    if (withdrawal == null) return
    withdrawal = null
    minimiseAfterWithdrawal()
    processAttempts.remove(Engine.WITHDRAWAL_KEY)
    exhausted.remove(Engine.WITHDRAWAL_KEY)
    val current = credentials
    if (current == null) {
        // An answer during the withdrawal dropped them; the file still holds them, since nothing was deleted meanwhile.
        stores?.credentials?.delete()
    } else if (credentialsUnsaved) {
        saveCredentials(current)
    }
    credentialsUnsaved = false
    identityDirty = true
    stateDirty = true
    eventsDirty = true
    ensureRegisterOp()
    ensureUpdateOp()
    relinkAfterConsent()
}

/**
 * Consent given again after a launch that read consent.json as withdrawn: the files predate that withdrawal, which
 * may have opted this device out and unlinked it without anything being written. Its local effects are applied again
 * and every server effect is treated as possible: the next update re-sends every field, the app's next login is not
 * skipped, and the tag mirror waits for the server.
 */
internal fun Engine.reconcileAfterWithdrawal() {
    device.ops.removeAll { it.kind != DeviceOpKind.REGISTER }
    device.tags.pendingAdd.clear()
    device.tags.pendingRemove.clear()
    launchTags.clear()
    tagSplit = null
    events.pending.clear()
    eventsFlushRequested = false
    eventsFirstPendingAt = null
    device.registration.sentFingerprint = null
    stateDirty = true
    eventsDirty = true
    val mayBeLinked = identity.serverLink != ServerLink.NONE || identity.ops.any { it.kind == IdentityOpKind.LOGIN && it.sent }
    if (mayBeLinked || identity.serverTags != ServerTags.NONE || device.tags.mirror.isNotEmpty()) assumeLinkMayBeCleared(mayBeLinked)
    ensureUpdateOp()
}

/** In memory, what the withdrawal wrote: no sent fingerprint, and a link and tags that may remain are unknown. */
private fun Engine.minimiseAfterWithdrawal() {
    val mayBeLinked = mayBeLinked()
    val mayHaveTags = mayHaveTags()
    device.registration.sentFingerprint = null
    if (mayBeLinked) {
        identity.serverLink = ServerLink.UNKNOWN
        identity.linkedExternalId = null
    }
    if (mayHaveTags) {
        identity.serverTags = ServerTags.UNKNOWN
        device.tags.mirror.clear()
    }
    identityDirty = true
    stateDirty = true
}

/** A withdrawal may have cleared this device's link and tags: neither is assumed to remain. */
private fun Engine.assumeLinkMayBeCleared(mayBeLinked: Boolean) {
    if (mayBeLinked) {
        identity.serverLink = ServerLink.UNKNOWN
        identity.linkedExternalId = null
    }
    device.tags.mirror.clear()
    if (identity.serverTags != ServerTags.NONE) identity.serverTags = ServerTags.UNKNOWN
    identityDirty = true
    stateDirty = true
}

/**
 * The withdrawal unlinked the signed-in customer, or assumed it unlinked: link them again with the identity token in
 * memory, as on iOS, when it is that customer's, unless their login is already queued or the server confirmed their
 * link since. Without the token, the app's next login links them.
 */
private fun Engine.relinkAfterConsent() {
    if (identity.desired != Desired.CUSTOMER) return
    val customer = identity.desiredExternalId ?: return
    if (identityToken?.externalId != customer) return
    if (identity.ops.any { it.kind == IdentityOpKind.LOGIN }) return
    if (identity.serverLink == ServerLink.LINKED && identity.linkedExternalId == customer) return
    enqueueIdentity(
        IdentityOp(allocateOpId(), IdentityOpKind.LOGIN, customer, clearTags = false, sent = false, retry = Retry(nextAt = now() + Engine.DEBOUNCE_SECONDS)),
    )
}
