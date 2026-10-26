package com.salla.push.internal.storage

import com.salla.push.internal.util.Hashing
import com.salla.push.internal.util.asciiLowercase
import java.net.URI

/**
 * Per-app state is keyed by (app id, host tag) so credentials of one API host are never sent to another. The host
 * tag is the first 8 hex characters of SHA-256 of the lowercased "scheme://host[:port]" of the API base.
 */
internal object HostTag {

    /** "scheme://host[:port]", lowercased; the port only when the URL states one. */
    fun origin(apiBaseUrl: String): String? {
        return try {
            val uri = URI(apiBaseUrl)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            asciiLowercase("$scheme://$host$port")
        } catch (e: Exception) {
            null
        }
    }

    fun of(apiBaseUrl: String): String? {
        val origin = origin(apiBaseUrl) ?: return null
        return Hashing.sha256Hex(origin)?.take(8)
    }
}
