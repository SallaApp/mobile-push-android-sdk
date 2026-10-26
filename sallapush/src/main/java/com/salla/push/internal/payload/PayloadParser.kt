package com.salla.push.internal.payload

import com.salla.push.SallaPushPayloadSource
import com.salla.push.internal.util.MAX_JSON_DEPTH
import com.salla.push.internal.util.objectOrNull
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import java.net.URI

/** A recognised SallaPush message, before it becomes a public notification object. */
internal class ParsedPayload(
    val notificationId: String?,
    val title: String?,
    val subtitle: String?,
    val body: String?,
    val imageUrl: String?,
    val launchUrl: String?,
    val additionalData: Map<String, Any?>,
    val collapseId: String?,
    val channelId: String?,
    val sound: String?,
    val source: SallaPushPayloadSource,
    val rawPayload: Map<String, String>,
    /** True for a native payload whose sp_v is not "1": parsed best effort with the version 1 rules. */
    val unknownVersion: Boolean,
    /** Fields that were present but unusable (for example an sp_id that is not a message id); the rest is used. */
    val unreadableFields: List<String>,
)

/**
 * Parses FCM data maps (all values are strings). Recognition order: native (an sp_v key is present, any value),
 * then OneSignal-compatible (a "custom" JSON string with a string "i" and an "a" object whose push_ref is a valid
 * message id), else foreign. A OneSignal push without a valid push_ref is OneSignal's, not SallaPush's.
 */
internal object PayloadParser {

    private const val MAX_CUSTOM_LENGTH = 4096
    private const val NATIVE_PREFIX = "sp_"
    private const val VERSION_KEY = "sp_v"
    private const val CURRENT_VERSION = "1"

    fun isSallaPush(data: Map<*, *>?): Boolean {
        val strings = stringMap(data) ?: return false
        return strings.containsKey(VERSION_KEY) || compatCustom(strings) != null
    }

    fun parse(data: Map<*, *>?): ParsedPayload? {
        val strings = stringMap(data) ?: return null
        if (strings.containsKey(VERSION_KEY)) return parseNative(strings)
        return parseCompat(strings)
    }

    private fun parseNative(data: Map<String, String>): ParsedPayload {
        val additional = LinkedHashMap<String, Any?>()
        for ((key, value) in data) {
            if (!key.startsWith(NATIVE_PREFIX)) additional[key] = value
        }
        val unreadable = ArrayList<String>(2)
        val id = data["sp_id"]
        val notificationId = if (MessageId.isValid(id)) id else null
        if (id != null && notificationId == null) unreadable.add("sp_id")
        val imageUrl = httpsUrl(data["sp_image"])
        if (imageUrl == null && nonBlank(data["sp_image"]) != null) unreadable.add("sp_image")
        return ParsedPayload(
            notificationId = notificationId,
            title = nonBlank(data["sp_title"]),
            subtitle = nonBlank(data["sp_subtitle"]),
            body = nonBlank(data["sp_body"]),
            imageUrl = imageUrl,
            launchUrl = nonBlank(data["sp_url"]),
            additionalData = additional,
            collapseId = nonBlank(data["sp_collapse"]),
            channelId = nonBlank(data["sp_channel"]),
            sound = null,
            source = SallaPushPayloadSource.NATIVE,
            rawPayload = data,
            unknownVersion = data[VERSION_KEY] != CURRENT_VERSION,
            unreadableFields = unreadable,
        )
    }

    private fun parseCompat(data: Map<String, String>): ParsedPayload? {
        val custom = compatCustom(data) ?: return null
        val attributes = custom.objectOrNull("a") ?: return null
        val additional = LinkedHashMap(JsonConvert.toMap(attributes))
        val pushRef = additional.remove("push_ref") as? String
        val unreadable = ArrayList<String>(2)
        val imageUrl = httpsUrl(data["bicon"])
        if (imageUrl == null && nonBlank(data["bicon"]) != null) unreadable.add("bicon")
        val launchUrl = nonBlank(custom.stringOrNull("u"))
        if (launchUrl == null && custom.has("u") && !custom.isNull("u") && custom.opt("u") !is String) unreadable.add("custom.u")
        return ParsedPayload(
            notificationId = pushRef,
            title = nonBlank(data["title"]),
            subtitle = null,
            body = nonBlank(data["alert"]),
            imageUrl = imageUrl,
            launchUrl = launchUrl,
            additionalData = additional,
            collapseId = null,
            channelId = nonBlank(data["oth_chnl"]),
            sound = nonBlank(data["sound"]),
            source = SallaPushPayloadSource.COMPAT,
            rawPayload = data,
            unknownVersion = false,
            unreadableFields = unreadable,
        )
    }

    /** The parsed "custom" object when the map is a SallaPush compat payload; null otherwise. */
    private fun compatCustom(data: Map<String, String>): org.json.JSONObject? {
        val raw = data["custom"] ?: return null
        if (raw.length > MAX_CUSTOM_LENGTH) return null
        val custom = parseJsonObject(raw, MAX_JSON_DEPTH) ?: return null
        if (custom.stringOrNull("i") == null) return null
        val attributes = custom.objectOrNull("a") ?: return null
        if (!MessageId.isValid(attributes.stringOrNull("push_ref"))) return null
        return custom
    }

    /** Copies the String-to-String entries of a map that may come from Java (null keys or values are skipped). */
    private fun stringMap(data: Map<*, *>?): Map<String, String>? {
        if (data == null) return null
        val result = LinkedHashMap<String, String>()
        for ((key, value) in data) {
            if (key is String && value is String) result[key] = value
        }
        return result
    }

    private fun nonBlank(value: String?): String? = if (value == null || value.isBlank()) null else value

    private fun httpsUrl(value: String?): String? {
        val candidate = nonBlank(value)?.trim() ?: return null
        return try {
            val uri = URI(candidate)
            if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()) candidate else null
        } catch (e: Exception) {
            null
        }
    }
}
