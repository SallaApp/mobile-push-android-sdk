package com.salla.push.internal.api

import com.salla.push.internal.util.asciiLowercase
import java.net.URI

/** The SDK routes. Templates are what gets logged; real paths carry an app id or device id validated beforehand. */
internal object Routes {

    /** push-service's SDK routes are under /notifications/v1/sallapush. */
    const val DEFAULT_BASE_URL = "https://api.salla.dev/notifications/v1/sallapush"

    const val REGISTER = "/apps/{app_id}/devices"
    const val DEVICE = "/devices/{id}"
    const val LINK = "/devices/{id}/link"
    const val UNLINK = "/devices/{id}/unlink"
    const val TAGS = "/devices/{id}/tags"
    const val EVENTS = "/devices/{id}/events"

    fun register(base: String, appId: String): String = "$base/apps/$appId/devices"

    fun device(base: String, deviceId: String): String = "$base/devices/$deviceId"

    fun deviceAction(base: String, deviceId: String, action: String): String = "$base/devices/$deviceId/$action"
}

/** The API base URL rules: https only; http only for local development hosts. No credentials, query or fragment. */
internal object ApiBaseUrl {

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    /** The normalised base ("scheme://host[:port][/path]" without a trailing slash), or null when not allowed. */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.let { asciiLowercase(it) } ?: return null
            val host = uri.host?.let { asciiLowercase(it) } ?: return null
            if (host.isEmpty()) return null
            val allowed = scheme == "https" || (scheme == "http" && host in LOCAL_HOSTS)
            if (!allowed) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = (uri.rawPath ?: "").trimEnd('/')
            "$scheme://$host$port$path"
        } catch (e: Exception) {
            null
        }
    }
}
