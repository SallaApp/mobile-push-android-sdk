package com.salla.push.internal.api

import com.salla.push.internal.log.LogSanitizer
import com.salla.push.internal.util.asciiLowercase
import com.salla.push.internal.util.objectOrNull
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.printableAscii
import com.salla.push.internal.util.stringOrNull
import com.salla.push.internal.util.truncatedUtf16

/** How a failed request is handled. */
internal enum class FailureClass {
    /** Transport failure: network, timeout, TLS. Retried with backoff; resumes on connectivity. */
    NETWORK,

    /** 5xx, or a 4xx without a JSON error envelope. Retried with backoff (Retry-After when present). */
    SERVER,

    /** 429. Retried after Retry-After (default 60 s); triggers never shorten it. */
    RATE_LIMITED,

    /** 3xx: never followed, retried. */
    REDIRECT,

    /** 2xx with a body that fails validation. Retried. */
    INVALID_RESPONSE,

    /** 4xx with a JSON error code. Handled per code. */
    CLIENT,
}

/**
 * A failed SDK request, with only loggable details: no URL, body or header other than X-Request-Id. A transport
 * failure also carries its [cause] ("UnknownServiceException: CLEARTEXT communication … not permitted …"), sanitised
 * like server text.
 */
internal class ApiFailure(
    val failureClass: FailureClass,
    val method: String,
    val route: String,
    val status: Int,
    val code: String?,
    val reason: String?,
    val serverMessage: String?,
    val requestId: String?,
    val retryAfterSeconds: Double?,
    val transport: TransportFailure?,
    val cause: String? = null,
    /** Why a 2xx body was refused (INVALID_RESPONSE), for the log line. */
    val detail: String? = null,
) {
    /**
     * Whether the request may have changed server state: it got no answer (a transport failure), or a 2xx whose body
     * could not be read. Every other failure is an answer that applied nothing (the SDK routes are transactional).
     */
    val mayHaveApplied: Boolean
        get() = failureClass == FailureClass.NETWORK || failureClass == FailureClass.INVALID_RESPONSE

    /** "POST /devices/{id}/login (503 server_error, request 8c1f…)" — what a log line names. */
    fun describe(): String = "$method $route (${summary()})"

    /** "503 server_error, request 8c1f…": what the failure was, without the request's method and route. */
    fun summary(): String {
        val what = when {
            transport != null -> asciiLowercase(transport.name) + (cause?.let { ": $it" } ?: "")
            code != null -> "$status $code"
            else -> status.toString()
        }
        // Server text is shown only for client errors, where it says what to fix; retryable failures never echo it.
        val message = if (failureClass == FailureClass.CLIENT) serverMessage?.let { ": \"$it\"" } ?: "" else ""
        val request = requestId?.let { ", request $it" } ?: ""
        return "$what$message$request"
    }

    companion object {

        private const val MAX_REQUEST_ID = 100
        private const val MAX_REASON = 64

        /**
         * A transport failure. Its detail is "ExceptionName: message" or a plain description: the exception's simple
         * name is kept as is (a class name, and a low-cardinality reason for telemetry) and only the message is
         * sanitised like server text, since it may echo what the peer sent.
         */
        fun transport(method: String, route: String, failure: HttpResult.Failure): ApiFailure {
            val name = EXCEPTION_NAME.find(failure.detail)?.groupValues?.get(1)
            val text = if (name == null) failure.detail else failure.detail.removePrefix(name).removePrefix(":")
            val message = LogSanitizer.serverText(text)
            val cause = when {
                name == null -> message
                message == null -> name
                else -> "$name: $message"
            }
            val reason = name?.let { printableAscii(truncatedUtf16(it, MAX_REASON)) }
            return ApiFailure(FailureClass.NETWORK, method, route, 0, null, reason, null, null, null, failure.kind, cause)
        }

        /** A leading Java class name, alone or followed by ":". */
        private val EXCEPTION_NAME = Regex("^([A-Za-z_$][A-Za-z0-9_$]*)(?=:|$)")

        fun invalidResponse(method: String, route: String, response: HttpResult.Response, detail: String): ApiFailure =
            ApiFailure(FailureClass.INVALID_RESPONSE, method, route, response.status, null, null, null, requestId(response), null, null, detail = detail)

        /** An answer that could not be interpreted at all: retried like an invalid body, built without reading it again. */
        fun unreadable(method: String, route: String, result: HttpResult): ApiFailure {
            val response = result as? HttpResult.Response
            val requestId = try {
                response?.let { requestId(it) }
            } catch (t: Throwable) {
                null
            }
            return ApiFailure(FailureClass.INVALID_RESPONSE, method, route, response?.status ?: 0, null, null, null, requestId, null, null, detail = "SallaPush could not interpret it")
        }

        /** Classifies a non-2xx answer. A body is read as an error code only when it is a JSON error envelope. */
        fun fromResponse(method: String, route: String, response: HttpResult.Response, nowMillis: Long): ApiFailure {
            val status = response.status
            val requestId = requestId(response)
            return when {
                status in 300..399 ->
                    ApiFailure(FailureClass.REDIRECT, method, route, status, null, null, null, requestId, null, null)
                status == 429 -> ApiFailure(
                    FailureClass.RATE_LIMITED, method, route, status, "rate_limited", null, null, requestId,
                    RetryAfter.seconds(response.retryAfter, nowMillis) ?: RetryAfter.DEFAULT_RATE_LIMIT_SECONDS, null,
                )
                status in 400..499 -> {
                    val envelope = errorEnvelope(response)
                    if (envelope == null) {
                        ApiFailure(FailureClass.SERVER, method, route, status, null, null, null, requestId, null, null)
                    } else {
                        ApiFailure(
                            FailureClass.CLIENT, method, route, status, envelope.code, envelope.reason, envelope.message,
                            requestId, RetryAfter.seconds(response.retryAfter, nowMillis), null,
                        )
                    }
                }
                else -> {
                    val envelope = errorEnvelope(response)
                    ApiFailure(
                        FailureClass.SERVER, method, route, status, envelope?.code, null, envelope?.message, requestId,
                        RetryAfter.seconds(response.retryAfter, nowMillis), null,
                    )
                }
            }
        }

        private class Envelope(val code: String, val reason: String?, val message: String?)

        private fun errorEnvelope(response: HttpResult.Response): Envelope? {
            if (response.oversized) return null
            val contentType = response.contentType?.let { asciiLowercase(it) }
            if (contentType != null && !contentType.contains("json")) return null
            val error = parseJsonObject(response.body.decodeToString())?.objectOrNull("error") ?: return null
            val code = error.stringOrNull("code")?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
            return Envelope(
                code = printableAscii(code),
                reason = error.stringOrNull("reason")?.let { printableAscii(truncatedUtf16(it, 64)) },
                message = LogSanitizer.serverText(error.stringOrNull("message")),
            )
        }

        fun requestId(response: HttpResult.Response): String? {
            val raw = response.requestId?.trim() ?: return null
            if (raw.isEmpty()) return null
            return printableAscii(truncatedUtf16(raw, MAX_REQUEST_ID))
        }
    }
}
