package com.salla.push.internal.storage

import com.salla.push.internal.engine.DeviceOp
import com.salla.push.internal.engine.DeviceOpKind
import com.salla.push.internal.engine.DeviceState
import com.salla.push.internal.engine.DormantReason
import com.salla.push.internal.engine.PendingClick
import com.salla.push.internal.engine.PendingTag
import com.salla.push.internal.engine.RegStatus
import com.salla.push.internal.util.arrayOrNull
import com.salla.push.internal.util.booleanOrNull
import com.salla.push.internal.util.doubleOrNull
import com.salla.push.internal.util.keyList
import com.salla.push.internal.util.longOrNull
import com.salla.push.internal.util.objectOrNull
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * state.json: registration state, push preferences, tags, non-identity ops and pending clicks. A corrupt file is
 * moved aside and the engine continues from an empty registration state; credentials and identity.json are kept.
 */
internal class StateStore(
    private val files: SdkFiles,
    directory: File,
    private val appId: String,
) {

    private val file = File(directory, FILE_NAME)

    fun load(): Loaded<DeviceState> {
        return when (val read = files.read(file)) {
            is FileRead.Missing -> Loaded.Missing
            is FileRead.Unavailable -> Loaded.Unavailable(read.reason)
            is FileRead.Data -> {
                val state = StateCodec.decode(read.bytes)
                if (state == null) {
                    files.moveAside(file, CORRUPT_NAME)
                    Loaded.Corrupt
                } else {
                    Loaded.Ok(state)
                }
            }
        }
    }

    fun save(state: DeviceState): Boolean {
        val bytes = StateCodec.encode(state, appId) ?: return false
        return files.write(file, bytes)
    }

    companion object {
        const val FILE_NAME = "state.json"
        const val CORRUPT_NAME = "state.corrupt.json"
    }
}

internal object StateCodec {

    private const val MAX_TAGS = 200
    private const val MAX_OPS = 10
    private const val MAX_CLICKS = 10
    private const val MAX_PAYLOAD_ENTRIES = 100

    fun encode(state: DeviceState, appId: String): ByteArray? {
        return try {
            val json = JSONObject()
            json.put("v", 1)
            json.put("app_id", appId)
            json.putNullable("last_device_id", state.lastDeviceId)
            val registration = state.registration
            json.put(
                "registration",
                JSONObject()
                    .put("status", registration.status.wire)
                    .putNullable("registered_token", registration.registeredToken)
                    .putNullable("registered_env", registration.registeredEnv)
                    .putNullable("sent_fingerprint", registration.sentFingerprint)
                    .put("last_full_update_at", registration.lastFullUpdateAt)
                    .putNullable("dormant_until", registration.dormantUntil)
                    .putNullable("dormant_reason", registration.dormantReason?.wire),
            )
            json.put(
                "push",
                JSONObject()
                    .putNullable("token", state.push.token)
                    .put("user_opted_out", state.push.userOptedOut)
                    .putNullable("language", state.push.language),
            )
            val mirror = JSONObject()
            for ((key, value) in state.tags.mirror) mirror.put(key, value)
            val pendingAdd = JSONObject()
            for ((key, tag) in state.tags.pendingAdd) pendingAdd.put(key, JSONObject().put("v", tag.value).put("epoch", tag.epoch))
            val pendingRemove = JSONObject()
            for ((key, epoch) in state.tags.pendingRemove) pendingRemove.put(key, epoch)
            json.put("tags", JSONObject().put("mirror", mirror).put("pending_add", pendingAdd).put("pending_remove", pendingRemove))
            val ops = JSONArray()
            for (op in state.ops) {
                val entry = JSONObject().put("id", op.id).put("kind", op.kind.wire)
                RetryCodec.encodeInto(entry, op.retry)
                ops.put(entry)
            }
            json.put("ops", ops)
            val clicks = JSONArray()
            for (click in state.pendingClicks) {
                val payload = JSONObject()
                for ((key, value) in click.payload) payload.put(key, value)
                clicks.put(
                    JSONObject()
                        .put("nonce", click.nonce)
                        .put("at", click.at)
                        .put("android_id", click.androidNotificationId)
                        .put("payload", payload),
                )
            }
            json.put("pending_clicks", clicks)
            json.toString().encodeToByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun decode(bytes: ByteArray): DeviceState? {
        val json = parseJsonObject(bytes.decodeToString()) ?: return null
        if (json.optInt("v", 0) != 1) return null
        val state = DeviceState()
        state.lastDeviceId = json.stringOrNull("last_device_id")?.takeIf { Validation.isUuid(it) }
        json.objectOrNull("registration")?.let { registration ->
            state.registration.status = RegStatus.from(registration.stringOrNull("status"))
            state.registration.registeredToken = registration.stringOrNull("registered_token")
            state.registration.registeredEnv = registration.stringOrNull("registered_env")
            state.registration.sentFingerprint = registration.stringOrNull("sent_fingerprint")
            state.registration.lastFullUpdateAt = registration.doubleOrNull("last_full_update_at") ?: 0.0
            state.registration.dormantUntil = registration.doubleOrNull("dormant_until")
            state.registration.dormantReason = DormantReason.from(registration.stringOrNull("dormant_reason"))
        }
        json.objectOrNull("push")?.let { push ->
            state.push.token = push.stringOrNull("token")
            state.push.userOptedOut = push.booleanOrNull("user_opted_out") ?: false
            state.push.language = push.stringOrNull("language")
        }
        json.objectOrNull("tags")?.let { tags ->
            tags.objectOrNull("mirror")?.let { mirror ->
                for (key in mirror.keyList().take(MAX_TAGS)) {
                    mirror.stringOrNull(key)?.let { state.tags.mirror[key] = it }
                }
            }
            tags.objectOrNull("pending_add")?.let { pending ->
                for (key in pending.keyList().take(MAX_TAGS)) {
                    val entry = pending.objectOrNull(key) ?: continue
                    val value = entry.stringOrNull("v") ?: continue
                    state.tags.pendingAdd[key] = PendingTag(value, entry.longOrNull("epoch") ?: 0L)
                }
            }
            tags.objectOrNull("pending_remove")?.let { pending ->
                for (key in pending.keyList().take(MAX_TAGS)) {
                    state.tags.pendingRemove[key] = pending.longOrNull(key) ?: 0L
                }
            }
        }
        json.arrayOrNull("ops")?.let { array ->
            for (index in 0 until minOf(array.length(), MAX_OPS)) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.longOrNull("id") ?: continue
                val kind = DeviceOpKind.from(entry.stringOrNull("kind")) ?: continue
                if (state.ops.any { it.kind == kind }) continue
                state.ops.add(DeviceOp(id, kind, RetryCodec.decode(entry)))
            }
        }
        json.arrayOrNull("pending_clicks")?.let { array ->
            for (index in 0 until minOf(array.length(), MAX_CLICKS)) {
                val entry = array.optJSONObject(index) ?: continue
                val nonce = entry.stringOrNull("nonce") ?: continue
                val at = entry.doubleOrNull("at") ?: continue
                val androidId = entry.longOrNull("android_id")?.toInt() ?: continue
                val payloadJson = entry.objectOrNull("payload") ?: continue
                val payload = LinkedHashMap<String, String>()
                for (key in payloadJson.keyList().take(MAX_PAYLOAD_ENTRIES)) {
                    payloadJson.stringOrNull(key)?.let { payload[key] = it }
                }
                state.pendingClicks.add(PendingClick(nonce, at, androidId, payload))
            }
        }
        return state
    }
}

/** What consent.json holds (as on iOS). */
internal sealed class ConsentRead {
    /** The last answer written: true when consent was given. */
    class Answer(val given: Boolean) : ConsentRead()

    /** No answer was ever written (or a failed withdrawal write removed it). */
    object Absent : ConsentRead()

    /** The file is not valid JSON of the expected shape: it holds no usable answer. */
    object Corrupt : ConsentRead()

    /** The file or the SDK's folder could not be read ([reason]); never taken as an answer. */
    class Unreadable(val reason: String) : ConsentRead()
}

/** Global files: consent.json and last_init.json. */
internal class GlobalStore(private val files: SdkFiles) {

    /** The persisted consent answer, or why there is none. */
    fun loadConsent(): ConsentRead {
        val file = file(CONSENT_FILE) ?: return ConsentRead.Unreadable("storage directory unavailable")
        val bytes = when (val read = files.read(file)) {
            is FileRead.Missing -> return ConsentRead.Absent
            is FileRead.Unavailable -> return ConsentRead.Unreadable(read.reason)
            is FileRead.Data -> read.bytes
        }
        val json = parseJsonObject(bytes.decodeToString()) ?: return ConsentRead.Corrupt
        if (json.optInt("v", 0) != 1) return ConsentRead.Corrupt
        val given = json.booleanOrNull("given") ?: return ConsentRead.Corrupt
        return ConsentRead.Answer(given)
    }

    fun saveConsent(given: Boolean): Boolean {
        val file = file(CONSENT_FILE) ?: return false
        val bytes = try {
            JSONObject().put("v", 1).put("given", given).toString().encodeToByteArray()
        } catch (e: Exception) {
            return false
        }
        return files.write(file, bytes)
    }

    /** Deletes consent.json, so the next launch reads "never given"; false when it is still there. */
    fun removeConsent(): Boolean {
        val file = file(CONSENT_FILE) ?: return false
        files.delete(file)
        return !files.exists(file)
    }

    fun loadLastInit(): LastInit? {
        val file = file(LAST_INIT_FILE) ?: return null
        val read = files.read(file) as? FileRead.Data ?: return null
        val json = parseJsonObject(read.bytes.decodeToString()) ?: return null
        if (json.optInt("v", 0) != 1) return null
        val appId = json.stringOrNull("app_id") ?: return null
        val apiBaseUrl = json.stringOrNull("api_base_url") ?: return null
        return LastInit(appId, apiBaseUrl, json.stringOrNull("log_level"), json.booleanOrNull("auto_update_badge"))
    }

    fun saveLastInit(lastInit: LastInit): Boolean {
        val file = file(LAST_INIT_FILE) ?: return false
        val bytes = try {
            JSONObject()
                .put("v", 1)
                .put("app_id", lastInit.appId)
                .put("api_base_url", lastInit.apiBaseUrl)
                .putNullable("log_level", lastInit.logLevel)
                .putNullable("auto_update_badge", lastInit.autoUpdateBadge)
                .toString()
                .encodeToByteArray()
        } catch (e: Exception) {
            return false
        }
        return files.write(file, bytes)
    }

    private fun file(name: String): File? = files.root()?.let { File(it, name) }

    private companion object {
        const val CONSENT_FILE = "consent.json"
        const val LAST_INIT_FILE = "last_init.json"
    }
}

/**
 * last_init.json: lets a WorkManager run in a fresh process build the engine before the app initialises, and a
 * notification shown before initWithContext create SallaPush's channel as the app last configured it
 * ([autoUpdateBadge]: null in a file written before the setting existed).
 */
internal data class LastInit(val appId: String, val apiBaseUrl: String, val logLevel: String?, val autoUpdateBadge: Boolean?)
