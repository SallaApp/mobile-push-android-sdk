package com.salla.push.internal.engine

import com.salla.push.internal.api.ApiFailure
import com.salla.push.internal.api.ApiResult
import com.salla.push.internal.api.FailureClass
import com.salla.push.internal.api.RegisterResult
import com.salla.push.internal.device.AndroidDeviceInfo
import com.salla.push.internal.device.DeviceFacts
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.internal.log.Redact
import com.salla.push.internal.util.Hashing
import com.salla.push.internal.util.cleanField
import org.json.JSONObject

// Registration, updates, the push token, the permission, and the device-level answers.

/** FCM tokens: base64url characters plus ':', 20-255 long. The server only checks printable ASCII. */
private val FCM_TOKEN = Regex("^[A-Za-z0-9_:\\-]{20,255}$")

internal fun isValidFcmToken(token: String): Boolean = FCM_TOKEN.matches(token)

/** The device fields of a registration or update the server accepted; update.sent names the ones that changed. */
internal data class SentFields(
    val token: String?,
    val optedIn: Boolean,
    val appVersion: String?,
    val sdkVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
    val language: String?,
    val timezone: String?,
) {
    /** The contract names of the fields that differ from [previous]. */
    fun changesSince(previous: SentFields): List<String> {
        val names = ArrayList<String>()
        if (token != previous.token) names.add("push_token")
        if (optedIn != previous.optedIn) names.add("opted_in")
        if (appVersion != previous.appVersion) names.add("app_version")
        if (sdkVersion != previous.sdkVersion) names.add("sdk_version")
        if (osVersion != previous.osVersion) names.add("os_version")
        if (deviceModel != previous.deviceModel) names.add("device_model")
        if (language != previous.language) names.add("language")
        if (timezone != previous.timezone) names.add("timezone")
        return names
    }
}

private fun sentFields(
    token: String?,
    optedIn: Boolean,
    facts: DeviceFacts,
    language: String?,
    timezone: String?,
): SentFields = SentFields(
    token = token,
    optedIn = optedIn,
    appVersion = facts.appVersion,
    sdkVersion = facts.sdkVersion,
    osVersion = facts.osVersion,
    deviceModel = facts.deviceModel,
    language = language,
    timezone = timezone,
)

internal fun Engine.onToken(token: String) {
    val push = device.push
    if (token == push.token) {
        logger.log(LogCode.TOKEN_UNCHANGED) { "Same push token; permission re-checked." }
        refreshPermissionNow()
        return
    }
    logger.log(LogCode.TOKEN_RECEIVED) { "Push token received (${Redact.pushToken(token)})." }
    push.token = token
    stateDirty = true
    tokenMissingTimer?.cancel()
    tokenMissingTimer = null
    ensureRegisterOp()
    ensureUpdateOp()
    refreshPermissionNow()
}

/** Re-reads the notification permission; a change updates opted_in and notifies permission and subscription observers. */
internal fun Engine.refreshPermissionNow() {
    val granted = readPermission()
    if (granted == permissionGranted) return
    val before = permissionGranted
    permissionGranted = granted
    logger.log(LogCode.PERMISSION_CHANGED) {
        "Notification permission ${if (before) "allowed" else "denied"} → ${if (granted) "allowed" else "denied"}; opted_in = ${optedIn()}."
    }
    if (mode == EngineMode.MAIN) host.onPermissionChanged(this, granted)
    ensureUpdateOp()
}

/** optIn() / optOut(): a call that changes nothing logs nothing and writes nothing (as on iOS). */
internal fun Engine.setOptedOut(optedOut: Boolean) {
    if (device.push.userOptedOut == optedOut) return
    device.push.userOptedOut = optedOut
    stateDirty = true
    if (optedOut) {
        logger.log(LogCode.SUBSCRIPTION_OPTED_OUT) { "optOut(): this device stops receiving SallaPush notifications." }
    } else {
        logger.log(LogCode.SUBSCRIPTION_OPTED_IN) { "optIn(): this device receives SallaPush notifications again." }
    }
    ensureUpdateOp()
}

internal fun Engine.setLanguage(language: String?) {
    if (language == device.push.language) return
    device.push.language = language
    stateDirty = true
    ensureUpdateOp()
}

// ── Registration ──

internal fun Engine.needsRegistration(): Boolean =
    credentials == null && device.push.token != null && !mode.isDrain && withdrawal == null

internal fun Engine.registerOp(): DeviceOp? = device.ops.firstOrNull { it.kind == DeviceOpKind.REGISTER }

internal fun Engine.ensureRegisterOp(delaySeconds: Double = Engine.DEBOUNCE_SECONDS) {
    if (!needsRegistration()) return
    val existing = registerOp()
    if (existing != null) {
        if (existing.retry.attempts == 0 && existing.retry.nextAt > now() + delaySeconds) existing.retry.nextAt = now() + delaySeconds
        return
    }
    device.ops.add(DeviceOp(allocateOpId(), DeviceOpKind.REGISTER, Retry(nextAt = now() + delaySeconds)))
    stateDirty = true
}

internal fun Engine.clearExpiredDormancy(now: Double) {
    val registration = device.registration
    if (registration.status != RegStatus.DORMANT) return
    if ((registration.dormantUntil ?: 0.0) > now) return
    registration.status = if (credentials != null) RegStatus.REGISTERED else RegStatus.NONE
    registration.dormantUntil = null
    registration.dormantReason = null
    stateDirty = true
    ensureRegisterOp(0.0)
}

internal fun Engine.registrationDormant(now: Double): Boolean {
    val registration = device.registration
    return registration.status == RegStatus.DORMANT && (registration.dormantUntil ?: 0.0) > now
}

internal fun Engine.deviceRoutesDormant(now: Double): Boolean =
    registrationDormant(now) && device.registration.dormantReason?.blocksDeviceRoutes == true

internal fun Engine.registerReady(now: Double): Boolean {
    if (!needsRegistration()) return false
    if (registrationDormant(now)) return false
    if (!sendsDeviceOps() && identity.ops.isEmpty()) return false
    if (registerOp() == null) ensureRegisterOp(0.0)
    val op = registerOp() ?: return false
    return opReady(op.retry, op.id, now)
}

/**
 * Sends the registration with only the device fields push-service stores; every answer is a new device row. False
 * when nothing could be sent (no token).
 */
internal fun Engine.sendRegister(): Boolean {
    val op = registerOp() ?: return false
    val token = device.push.token ?: return false
    val facts = currentFacts()
    val language = effectiveLanguage()
    val timezone = currentTimezone()
    val optedIn = optedIn()
    val body = JSONObject()
    body.put("platform", "android")
    body.put("push_token", token)
    body.put("opted_in", optedIn)
    putMetadata(body, facts, language, timezone)
    val fingerprint = fingerprint(token, optedIn, facts, language, timezone)
    val fields = sentFields(token, optedIn, facts, language, timezone)
    inFlight = "register"
    val generationNow = generation
    api.register(appId, body) { result ->
        if (generationNow == generation) {
            inFlight = null
            task { handleRegisterResult(op, result, token, fingerprint, fields) }
        }
    }
    return true
}

private fun Engine.handleRegisterResult(
    op: DeviceOp,
    result: ApiResult<RegisterResult>,
    token: String,
    fingerprint: String?,
    fields: SentFields,
) {
    when (result) {
        is ApiResult.Ok -> onRegistered(op, result.value, token, fingerprint, fields, result.durationMillis)
        is ApiResult.Err -> onRegisterFailed(op, result.failure)
    }
}

private fun Engine.onRegistered(
    op: DeviceOp,
    value: RegisterResult,
    token: String,
    fingerprint: String?,
    fields: SentFields,
    durationMillis: Long,
) {
    device.ops.remove(op)
    clearAttempts(op.id)
    lastSentFields = fields
    val credentials = Credentials(value.deviceId, value.secret)
    this.credentials = credentials
    // While consent is withdrawn nothing is written: the withdrawal's requests use the credentials from
    // memory, and they are written once consent is given again.
    if (withdrawal == null) saveCredentials(credentials) else credentialsUnsaved = true
    device.lastDeviceId = value.deviceId
    val registration = device.registration
    registration.status = RegStatus.REGISTERED
    registration.registeredToken = token
    registration.registeredEnv = null
    registration.sentFingerprint = fingerprint
    registration.lastFullUpdateAt = now()
    registration.dormantUntil = null
    registration.dormantReason = null
    device.ops.removeAll { it.kind == DeviceOpKind.UPDATE }
    stateDirty = true
    logger.log(
        LogCode.REGISTER_OK,
        mapOf(Meta.DEVICE_ID to value.deviceId, Meta.APP_ID to appId),
    ) {
        "Device registered: ${value.deviceId} in $durationMillis ms."
    }
    afterRegistration()
    ensureUpdateOp()
}

/**
 * The registration refusals push-service answers, matched on status and error code: 404 not_found (no route at
 * this base URL) and 404 unknown_app wait 24 h with their own advice; any other coded 4xx (400 invalid_request)
 * waits 24 h as a rejected request.
 */
private fun Engine.onRegisterFailed(op: DeviceOp, failure: ApiFailure) {
    if (failure.failureClass != FailureClass.CLIENT) {
        applyFailure(op.retry, op.id, failure)
        return
    }
    val metadata = failureMetadata(failure)
    when {
        failure.status == 404 && (failure.code == "unknown_app" || failure.code == "not_found") -> {
            registerDormant(if (failure.code == "unknown_app") DormantReason.UNKNOWN_APP else DormantReason.NOT_FOUND)
            logDormantOnce(failure)
        }
        else -> {
            registerDormant(DormantReason.INVALID_REQUEST)
            logger.log(LogCode.NET_CLIENT_ERROR, metadata) { "${failure.describe()} was rejected; retrying in 24 h." }
        }
    }
}

private fun Engine.logDormantOnce(failure: ApiFailure) {
    if (dormantLogged) return
    dormantLogged = true
    logger.log(LogCode.REGISTER_DORMANT, failureMetadata(failure)) {
        val answer = "HTTP ${failure.status} ${failure.code}${failure.requestId?.let { ", request $it" } ?: ""}"
        if (failure.code == "not_found") {
            "SallaPush is not enabled for app $appId yet ($answer); it registers once it is. Next try in 24 h."
        } else {
            "SallaPush is not available for app $appId ($answer). Check the app id. Next try in 24 h."
        }
    }
}

private fun Engine.registerDormant(reason: DormantReason) {
    val registration = device.registration
    registration.status = RegStatus.DORMANT
    registration.dormantReason = reason
    registration.dormantUntil = now() + Engine.DAY_SECONDS
    stateDirty = true
}

/**
 * Forgets the device credentials (keeping last_device_id) so the next step re-registers.
 * While consent is withdrawn nothing is written or deleted (as on iOS): the stale file is deleted when
 * consent is given again, or replaced by the next registration.
 */
internal fun Engine.dropCredentials() {
    credentials = null
    credentialsUnsaved = false
    if (withdrawal == null) stores?.credentials?.delete()
    val registration = device.registration
    registration.status = RegStatus.NONE
    registration.registeredToken = null
    registration.sentFingerprint = null
    device.ops.removeAll { it.kind == DeviceOpKind.UPDATE }
    stateDirty = true
}

// ── Updates ──

internal fun Engine.effectiveLanguage(): String? =
    cleanField(device.push.language ?: env.device.deviceLanguage(), AndroidDeviceInfo.MAX_LANGUAGE)

internal fun Engine.currentTimezone(): String? = cleanField(env.device.timezone(), AndroidDeviceInfo.MAX_TIMEZONE)

/** Device metadata within the server's limits (UTF-16 units), whatever the source returned. */
internal fun Engine.currentFacts(): DeviceFacts {
    val facts = env.device.facts()
    return DeviceFacts(
        appVersion = cleanField(facts.appVersion, AndroidDeviceInfo.MAX_VERSION),
        sdkVersion = cleanField(facts.sdkVersion, AndroidDeviceInfo.MAX_VERSION),
        osVersion = cleanField(facts.osVersion, AndroidDeviceInfo.MAX_VERSION),
        deviceModel = cleanField(facts.deviceModel, AndroidDeviceInfo.MAX_MODEL),
    )
}

/** SHA-256 of the canonical JSON of every device field (sorted keys); apns_env is always null on Android. */
internal fun fingerprint(token: String?, optedIn: Boolean, facts: DeviceFacts, language: String?, timezone: String?): String? {
    val fields = sortedMapOf<String, Any?>(
        "apns_env" to null,
        "app_version" to facts.appVersion,
        "device_model" to facts.deviceModel,
        "language" to language,
        "opted_in" to optedIn,
        "os_version" to facts.osVersion,
        "push_token" to token,
        "sdk_version" to facts.sdkVersion,
        "timezone" to timezone,
    )
    val canonical = StringBuilder("{")
    var first = true
    for ((key, value) in fields) {
        if (!first) canonical.append(',')
        first = false
        canonical.append(JSONObject.quote(key)).append(':')
        when (value) {
            null -> canonical.append("null")
            is Boolean -> canonical.append(if (value) "true" else "false")
            else -> canonical.append(JSONObject.quote(value.toString()))
        }
    }
    canonical.append('}')
    return Hashing.sha256Hex(canonical.toString())
}

internal fun Engine.currentFingerprint(): String? =
    fingerprint(device.push.token, optedIn(), currentFacts(), effectiveLanguage(), currentTimezone())

/** True when the device fields changed since the last answer, or the last full update is 24 h old. */
internal fun Engine.updateDue(now: Double): Boolean {
    if (credentials == null || device.registration.status != RegStatus.REGISTERED) return false
    val registration = device.registration
    val last = if (registration.lastFullUpdateAt > now) now - Engine.DAY_SECONDS else registration.lastFullUpdateAt
    if (now - last >= Engine.DAY_SECONDS) return true
    return currentFingerprint() != registration.sentFingerprint
}

internal fun Engine.ensureUpdateOp(delaySeconds: Double = Engine.DEBOUNCE_SECONDS) {
    if (mode.isDrain || withdrawal != null) return
    val now = now()
    if (!updateDue(now)) return
    if (device.ops.any { it.kind == DeviceOpKind.UPDATE }) return
    device.ops.add(DeviceOp(allocateOpId(), DeviceOpKind.UPDATE, Retry(nextAt = now + delaySeconds)))
    stateDirty = true
}

internal fun Engine.updateReady(now: Double): Boolean {
    val op = device.ops.firstOrNull { it.kind == DeviceOpKind.UPDATE } ?: return false
    if (!opReady(op.retry, op.id, now)) return false
    if (!updateDue(now)) {
        device.ops.remove(op)
        clearAttempts(op.id)
        stateDirty = true
        return false
    }
    return true
}

/** Sends the device update with every device field; false when no update op exists. */
internal fun Engine.sendUpdate(credentials: Credentials): Boolean {
    val op = device.ops.firstOrNull { it.kind == DeviceOpKind.UPDATE } ?: return false
    val registration = device.registration
    val token = device.push.token
    val facts = currentFacts()
    val language = effectiveLanguage()
    val timezone = currentTimezone()
    val optedIn = optedIn()
    val body = JSONObject()
    body.put("opted_in", optedIn)
    putMetadata(body, facts, language, timezone)
    val tokenChanged = token != null && token != registration.registeredToken
    if (tokenChanged) body.put("push_token", token)
    val fingerprint = fingerprint(token, optedIn, facts, language, timezone)
    val heartbeat = fingerprint == registration.sentFingerprint
    val fields = sentFields(token, optedIn, facts, language, timezone)
    inFlight = "update"
    val generationNow = generation
    api.update(credentials, body) { result ->
        if (generationNow == generation) {
            inFlight = null
            task { handleUpdateResult(op, result, if (tokenChanged) token else null, fingerprint, heartbeat, fields) }
        }
    }
    return true
}

/**
 * What an update changed, for update.sent: the fields that differ from the last ones the server accepted in this
 * process (as on iOS). Before the first of those, only the persisted fingerprint is known, so it says so.
 */
private fun Engine.updateDescription(fields: SentFields): String {
    val previous = lastSentFields ?: return "first update since launch: a device field changed since the last one"
    val changes = fields.changesSince(previous)
    return if (changes.isEmpty()) "every field sent again" else changes.joinToString(", ")
}

private fun Engine.handleUpdateResult(
    op: DeviceOp,
    result: ApiResult<Unit>,
    sentToken: String?,
    fingerprint: String?,
    heartbeat: Boolean,
    fields: SentFields,
) {
    when (result) {
        is ApiResult.Ok -> {
            device.ops.remove(op)
            clearAttempts(op.id)
            val registration = device.registration
            registration.sentFingerprint = fingerprint
            registration.lastFullUpdateAt = now()
            if (sentToken != null) registration.registeredToken = sentToken
            stateDirty = true
            if (heartbeat) {
                logger.log(LogCode.HEARTBEAT_SENT) { "Daily device refresh sent." }
            } else {
                logger.log(LogCode.UPDATE_SENT) { "Device updated (${updateDescription(fields)})." }
            }
            lastSentFields = fields
            // A change made while this request was on the wire (opt-out, token, language, permission) was absorbed by
            // its op: the latest values go out now, with the usual debounce (latest values win).
            ensureUpdateOp()
        }
        is ApiResult.Err -> {
            val failure = result.failure
            if (handleDeviceAnswer(failure)) return
            if (failure.failureClass == FailureClass.CLIENT) {
                op.retry.dormantUntil = now() + Engine.DAY_SECONDS
                op.retry.lastFailure = FailureKind.CLIENT
                stateDirty = true
                logger.log(LogCode.NET_CLIENT_ERROR, failureMetadata(failure)) { "${failure.describe()} was rejected; retrying in 24 h." }
                return
            }
            applyFailure(op.retry, op.id, failure)
            stateDirty = true
        }
    }
}

private fun putMetadata(body: JSONObject, facts: DeviceFacts, language: String?, timezone: String?) {
    facts.appVersion?.let { body.put("app_version", it) }
    facts.sdkVersion?.let { body.put("sdk_version", it) }
    facts.osVersion?.let { body.put("os_version", it) }
    facts.deviceModel?.let { body.put("device_model", it) }
    language?.let { body.put("language", it) }
    timezone?.let { body.put("timezone", it) }
}

// ── Device-level answers ──

/**
 * Handles the error answers that concern the device itself, matched on status and error code (as on iOS):
 * 401 invalid_device_secret, 404 unknown_device and not_found, the only device-level answers push-service gives. True
 * when [failure] was one of them. Any other coded 4xx, a 401 with another code included, is left to the route's own
 * client-error handling.
 */
internal fun Engine.handleDeviceAnswer(failure: ApiFailure): Boolean {
    if (failure.failureClass != FailureClass.CLIENT) return false
    when {
        failure.status == 401 && failure.code == "invalid_device_secret" -> handleInvalidSecret(failure)
        failure.status == 404 && failure.code == "unknown_device" -> handleUnknownDevice(failure)
        failure.status == 404 && failure.code == "not_found" -> handleRoutesClosed(failure)
        else -> return false
    }
    return true
}

private fun Engine.handleInvalidSecret(failure: ApiFailure) {
    dropCredentials()
    reregister("invalid_device_secret", failure)
}

/**
 * The re-registration valve, for every device-level re-registration: 401 invalid_device_secret and 404
 * unknown_device. The first re-registration runs at once. One that comes within an hour of the previous
 * re-registration is held until an hour after it, with an ERROR, so a misbehaving server can never make the SDK loop
 * on registration. The hold is a dormant wait on the register op: no trigger, debounce or worker flush shortens it, and
 * it is persisted with the op, so it also holds after a relaunch ([restoreReregistrationHold]).
 */
private fun Engine.reregister(reason: String, failure: ApiFailure) {
    val now = now()
    // A previous re-registration later than now means the clock moved back: this one is not held.
    val heldUntil = lastReregisterAt?.takeIf { it <= now && now - it < HOUR_SECONDS }?.plus(HOUR_SECONDS)
    val runAt = heldUntil ?: now
    lastReregisterAt = runAt
    ensureRegisterOp(0.0)
    registerOp()?.retry?.let { retry ->
        // A 429 wait is never shortened.
        retry.nextAt = if (retry.lastFailure == FailureKind.RATE_LIMITED) maxOf(retry.nextAt, runAt) else runAt
        retry.dormantUntil = heldUntil
        stateDirty = true
    }
    if (heldUntil == null) {
        logger.log(LogCode.REGISTER_REREGISTERING, mapOf(Meta.REASON to reason)) { "Registering again (reason: $reason)." }
        return
    }
    val waitSeconds = heldUntil - now
    val metadata = LinkedHashMap(failureMetadata(failure))
    metadata[Meta.REASON] = reason
    metadata[Meta.DELAY_S] = Math.ceil(waitSeconds).toLong().toString()
    logger.log(LogCode.NET_CLIENT_ERROR, metadata) {
        val minutes = Math.ceil(waitSeconds / 60.0).toLong()
        "${failure.describe()} asked for a new registration within an hour of the previous registration; registering again in $minutes min (reason: $reason)."
    }
}

/**
 * At engine start: a re-registration held before a relaunch keeps holding, at most an hour from now even if the clock
 * moved back, and counts as the previous re-registration of this process.
 */
internal fun Engine.restoreReregistrationHold() {
    val retry = registerOp()?.retry ?: return
    val until = retry.dormantUntil ?: return
    val latest = now() + HOUR_SECONDS
    if (until > latest) {
        retry.dormantUntil = latest
        retry.nextAt = minOf(retry.nextAt, latest)
        stateDirty = true
    }
    lastReregisterAt = minOf(until, latest)
}

/**
 * 404 unknown_device: the row is gone, and the device registers again. The registration answer is a new row, which
 * links the asserted customer again and gets the tags again ([afterRegistration]).
 */
private fun Engine.handleUnknownDevice(failure: ApiFailure) {
    dropCredentials()
    reregister("unknown_device", failure)
}

private fun Engine.handleRoutesClosed(failure: ApiFailure) {
    val registration = device.registration
    registration.status = RegStatus.DORMANT
    registration.dormantReason = DormantReason.NOT_FOUND
    registration.dormantUntil = now() + Engine.DAY_SECONDS
    stateDirty = true
    if (!routesClosedLogged) {
        routesClosedLogged = true
        logger.log(LogCode.NET_ROUTES_CLOSED, failureMetadata(failure)) {
            "SallaPush device routes are closed (404 not_found); pending work is kept and retried in 24 h."
        }
    }
}

private const val HOUR_SECONDS = 3600.0
