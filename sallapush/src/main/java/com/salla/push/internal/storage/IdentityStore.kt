package com.salla.push.internal.storage

import com.salla.push.internal.engine.Desired
import com.salla.push.internal.engine.FailureKind
import com.salla.push.internal.engine.IdentityOp
import com.salla.push.internal.engine.IdentityOpKind
import com.salla.push.internal.engine.IdentityRules
import com.salla.push.internal.engine.IdentityState
import com.salla.push.internal.engine.Retry
import com.salla.push.internal.engine.ServerLink
import com.salla.push.internal.engine.ServerTags
import com.salla.push.internal.util.arrayOrNull
import com.salla.push.internal.util.booleanOrNull
import com.salla.push.internal.util.doubleOrNull
import com.salla.push.internal.util.longOrNull
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * identity.json: the identity state and the identity op log. It is written atomically before any identity request
 * and after every identity state change; a corrupt file is moved aside and identity restarts as unknown.
 */
internal class IdentityStore(
    private val files: SdkFiles,
    directory: File,
    private val appId: String,
    private val apiBaseUrl: String,
) {

    private val file = File(directory, FILE_NAME)

    fun load(): Loaded<IdentityState> {
        return when (val read = files.read(file)) {
            is FileRead.Missing -> Loaded.Missing
            is FileRead.Unavailable -> Loaded.Unavailable(read.reason)
            is FileRead.Data -> {
                val state = IdentityCodec.decode(read.bytes)
                if (state == null) {
                    files.moveAside(file, CORRUPT_NAME)
                    Loaded.Corrupt
                } else {
                    Loaded.Ok(state)
                }
            }
        }
    }

    fun save(state: IdentityState): Boolean {
        val bytes = IdentityCodec.encode(state, appId, apiBaseUrl) ?: return false
        return files.write(file, bytes)
    }

    companion object {
        const val FILE_NAME = "identity.json"
        const val CORRUPT_NAME = "identity.corrupt.json"
    }
}

internal object IdentityCodec {

    private const val MAX_OPS = 100

    fun encode(state: IdentityState, appId: String, apiBaseUrl: String): ByteArray? {
        return try {
            val json = JSONObject()
            json.put("v", 1)
            json.put("app_id", appId)
            json.put("api_base_url", apiBaseUrl)
            json.put("desired", state.desired.wire)
            json.putNullable("desired_external_id", state.desiredExternalId)
            json.put("server_link", state.serverLink.wire)
            json.putNullable("linked_external_id", state.linkedExternalId)
            json.put("server_tags", state.serverTags.wire)
            val ops = JSONArray()
            for (op in state.ops) ops.put(encodeOp(op))
            json.put("ops", ops)
            json.toString().encodeToByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Null when the file is not a valid identity state (it is then treated as corrupt). It reads beta.1's file too,
     * whose customer login went through a token fingerprint: a customer without a valid desired_external_id reads as
     * unknown, a login without a valid external_id is skipped, and the fingerprint, refusal, relogin, unlinked-customer
     * and OneSignal hand-off keys are ignored. beta.1's unlinks read as logouts ([IdentityOpKind.from]), and its ops
     * with clear_tags have no tag_keys: they clear the keys SallaPush holds when the clearing is sent.
     */
    fun decode(bytes: ByteArray): IdentityState? {
        val json = parseJsonObject(bytes.decodeToString()) ?: return null
        if (json.optInt("v", 0) != 1) return null
        val state = IdentityState()
        state.desired = Desired.from(json.stringOrNull("desired"))
        state.desiredExternalId = externalId(json.stringOrNull("desired_external_id"))?.takeIf { state.desired == Desired.CUSTOMER }
        if (state.desired == Desired.CUSTOMER && state.desiredExternalId == null) state.desired = Desired.UNKNOWN
        state.serverLink = ServerLink.from(json.stringOrNull("server_link"))
        state.linkedExternalId = externalId(json.stringOrNull("linked_external_id"))?.takeIf { state.serverLink == ServerLink.LINKED }
        state.serverTags = ServerTags.from(json.stringOrNull("server_tags"))
        json.arrayOrNull("ops")?.let { array ->
            for (index in 0 until minOf(array.length(), MAX_OPS)) {
                val op = array.optJSONObject(index)?.let { decodeOp(it) } ?: continue
                state.ops.add(op)
            }
        }
        return state
    }

    private fun encodeOp(op: IdentityOp): JSONObject {
        val json = JSONObject()
        json.put("id", op.id)
        json.put("kind", op.kind.wire)
        json.putNullable("external_id", op.externalId)
        json.put("clear_tags", op.clearTags)
        json.put("tag_keys", JSONArray(op.tagKeys))
        json.put("tags_cleared", op.tagsCleared)
        json.put("sent", op.sent)
        RetryCodec.encodeInto(json, op.retry)
        return json
    }

    private fun decodeOp(json: JSONObject): IdentityOp? {
        val id = json.longOrNull("id") ?: return null
        val kind = IdentityOpKind.from(json.stringOrNull("kind")) ?: return null
        val externalId = if (kind == IdentityOpKind.LOGIN) externalId(json.stringOrNull("external_id")) ?: return null else null
        return IdentityOp(
            id = id,
            kind = kind,
            externalId = externalId,
            clearTags = json.booleanOrNull("clear_tags") ?: false,
            sent = json.booleanOrNull("sent") ?: false,
            retry = RetryCodec.decode(json),
            tagKeys = tagKeys(json.arrayOrNull("tag_keys")),
            tagsCleared = json.booleanOrNull("tags_cleared") ?: false,
        )
    }

    /** The string members of an op's tag_keys; none when it has no such array (a beta.1 op). */
    private fun tagKeys(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val keys = ArrayList<String>()
        for (index in 0 until array.length()) (array.opt(index) as? String)?.let { keys.add(it) }
        return keys
    }

    private fun externalId(value: String?): String? = value?.takeIf { IdentityRules.isExternalId(it) }
}

/** The retry fields shared by identity and device ops. */
internal object RetryCodec {

    fun encodeInto(json: JSONObject, retry: Retry) {
        json.put("attempts", retry.attempts)
        json.put("next_at", retry.nextAt)
        json.put("last_attempt_at", retry.lastAttemptAt)
        json.putNullable("last_failure", retry.lastFailure?.wire)
        json.putNullable("dormant_until", retry.dormantUntil)
    }

    fun decode(json: JSONObject): Retry = Retry(
        attempts = (json.longOrNull("attempts") ?: 0L).coerceIn(0L, 10_000L).toInt(),
        nextAt = json.doubleOrNull("next_at") ?: 0.0,
        lastAttemptAt = json.doubleOrNull("last_attempt_at") ?: 0.0,
        lastFailure = FailureKind.from(json.stringOrNull("last_failure")),
        dormantUntil = json.doubleOrNull("dormant_until"),
    )
}
