package com.salla.push.internal.api

import com.salla.push.internal.engine.Credentials
import com.salla.push.internal.engine.PendingEvent
import com.salla.push.internal.log.SecretString
import com.salla.push.internal.storage.Validation
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.keyList
import com.salla.push.internal.util.longOrNull
import com.salla.push.internal.util.objectOrNull
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

internal sealed class ApiResult<out T> {
    class Ok<T>(val value: T, val durationMillis: Long) : ApiResult<T>()

    class Err(val failure: ApiFailure) : ApiResult<Nothing>()
}

internal class RegisterResult(val deviceId: String, val secret: SecretString)

internal class TagsResult(val tags: Map<String, String>)

internal class EventsResult(val accepted: Long, val ignored: Long)

/**
 * The SDK routes as typed calls. Every 2xx body is validated; an invalid one is a retryable
 * INVALID_RESPONSE failure. Results are delivered on the engine thread.
 */
internal class ApiClient(
    private val network: Network,
    private val clock: Clock,
    val baseUrl: String,
) {

    fun register(appId: String, body: JSONObject, done: (ApiResult<RegisterResult>) -> Unit) {
        send("POST", Routes.REGISTER, Routes.register(baseUrl, appId), body, null, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS, done) {
            parseRegister(it)
        }
    }

    /** The device update: push-service answers `200 {}`; any JSON object is a success. */
    fun update(credentials: Credentials, body: JSONObject, done: (ApiResult<Unit>) -> Unit) {
        val url = Routes.device(baseUrl, credentials.deviceId)
        send("PUT", Routes.DEVICE, url, body, credentials.secret, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS, done) { }
    }

    /**
     * The link, with exactly `{external_id, identity_token}`: push-service checks that the token's subject is
     * [externalId] and answers `200 {}`, or 403 invalid_identity_token. Any JSON object is a success.
     */
    fun link(credentials: Credentials, externalId: String, identityToken: SecretString, done: (ApiResult<Unit>) -> Unit) {
        val body = JSONObject()
        body.put("external_id", externalId)
        body.put("identity_token", identityToken.reveal())
        val url = Routes.deviceAction(baseUrl, credentials.deviceId, "link")
        send("POST", Routes.LINK, url, body, credentials.secret, HttpUrlConnectionTransport.LOGIN_READ_TIMEOUT_MILLIS, done) { }
    }

    /** The unlink, with `{}`: push-service removes the link and keeps the device's tags. */
    fun unlink(credentials: Credentials, done: (ApiResult<Unit>) -> Unit) {
        val url = Routes.deviceAction(baseUrl, credentials.deviceId, "unlink")
        send("POST", Routes.UNLINK, url, JSONObject(), credentials.secret, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS, done) { }
    }

    /**
     * The tags route takes `PATCH` only: another method gets 404 not_found, which closes every device
     * route for 24 h. Android's HttpURLConnection sends PATCH (AOSP `HttpURLConnectionImpl.METHODS`); the JDK's
     * refuses it, which only JVM tests meet.
     */
    fun tags(credentials: Credentials, add: Map<String, String>, remove: Collection<String>, done: (ApiResult<TagsResult>) -> Unit) {
        val body = JSONObject()
        if (add.isNotEmpty()) {
            val addJson = JSONObject()
            for ((key, value) in add) addJson.put(key, value)
            body.put("add", addJson)
        }
        if (remove.isNotEmpty()) {
            val removeJson = JSONArray()
            for (key in remove) removeJson.put(key)
            body.put("remove", removeJson)
        }
        val url = Routes.deviceAction(baseUrl, credentials.deviceId, "tags")
        send("PATCH", Routes.TAGS, url, body, credentials.secret, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS, done) { json ->
            val tagsJson = json.objectOrNull("tags") ?: return@send null
            val tags = LinkedHashMap<String, String>()
            for (key in tagsJson.keyList()) {
                val value = tagsJson.stringOrNull(key) ?: return@send null
                tags[key] = value
            }
            TagsResult(tags)
        }
    }

    fun events(credentials: Credentials, events: List<PendingEvent>, done: (ApiResult<EventsResult>) -> Unit) {
        val array = JSONArray()
        for (event in events) {
            array.put(JSONObject().put("type", event.type).put("notification_id", event.notificationId).put("at", event.at))
        }
        val body = JSONObject().put("events", array)
        val url = Routes.deviceAction(baseUrl, credentials.deviceId, "events")
        send("POST", Routes.EVENTS, url, body, credentials.secret, HttpUrlConnectionTransport.READ_TIMEOUT_MILLIS, done) { json ->
            EventsResult(json.longOrNull("accepted") ?: 0L, json.longOrNull("ignored") ?: 0L)
        }
    }

    /**
     * push-service answers `201 {device_id, device_secret}`: a new device row every time, without a link
     * or tags. Any other field is not read: a `status` is none of push-service's, and its device routes never refuse a
     * disabled device.
     */
    private fun parseRegister(json: JSONObject): RegisterResult? {
        val deviceId = json.stringOrNull("device_id")?.takeIf { Validation.isUuid(it) } ?: return null
        val secret = json.stringOrNull("device_secret")?.takeIf { Validation.isDeviceSecret(it) } ?: return null
        return RegisterResult(com.salla.push.internal.util.asciiLowercase(deviceId), SecretString(secret))
    }

    private inline fun <T> send(
        method: String,
        route: String,
        url: String,
        body: JSONObject?,
        authorization: SecretString?,
        readTimeoutMillis: Int,
        noinline done: (ApiResult<T>) -> Unit,
        crossinline parse: (JSONObject) -> T?,
    ) {
        val bytes = body?.toString()?.encodeToByteArray()
        network.submit(HttpRequest(method, url, route, bytes, authorization, readTimeoutMillis)) { result ->
            // The caller clears its in-flight request only in [done]: it must run, whatever the answer looks like.
            val interpreted = try {
                interpret(method, route, result) { parse(it) }
            } catch (t: Throwable) {
                ApiResult.Err(ApiFailure.unreadable(method, route, result))
            }
            done(interpreted)
        }
    }

    private inline fun <T> interpret(method: String, route: String, result: HttpResult, parse: (JSONObject) -> T?): ApiResult<T> {
        return when (result) {
            is HttpResult.Failure -> ApiResult.Err(ApiFailure.transport(method, route, result))
            is HttpResult.Response -> {
                if (result.status in 200..299) {
                    val json = if (result.oversized) null else parseJsonObject(result.body.decodeToString())
                    val value = json?.let { parse(it) }
                    when {
                        value != null -> ApiResult.Ok(value, result.durationMillis)
                        result.oversized -> ApiResult.Err(ApiFailure.invalidResponse(method, route, result, OVERSIZED))
                        json == null -> ApiResult.Err(ApiFailure.invalidResponse(method, route, result, NOT_JSON))
                        else -> ApiResult.Err(ApiFailure.invalidResponse(method, route, result, MISSING_FIELDS))
                    }
                } else {
                    ApiResult.Err(ApiFailure.fromResponse(method, route, result, clock.nowMillis()))
                }
            }
        }
    }

    private companion object {
        val OVERSIZED = "larger than ${HttpUrlConnectionTransport.MAX_BODY_BYTES / 1024} KB"
        const val NOT_JSON = "not a JSON object of at most 32 levels"
        const val MISSING_FIELDS = "a required field is missing or invalid"
    }
}
