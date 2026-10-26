package com.salla.push.internal.api

import com.salla.push.internal.log.SecretString
import com.salla.push.internal.util.Deadlines
import com.salla.push.internal.util.withSocketTag
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** One SDK request. [route] is the path template that may be logged; the URL and body never are. */
internal class HttpRequest(
    val method: String,
    val url: String,
    val route: String,
    val body: ByteArray?,
    val authorization: SecretString?,
    val readTimeoutMillis: Int,
)

internal enum class TransportFailure {
    TIMEOUT,
    NETWORK,
    TLS,
    OTHER,
}

internal sealed class HttpResult {

    /**
     * An HTTP answer. [oversized] marks a body longer than the transport reads (it is never parsed: [body] holds only
     * its first bytes).
     */
    class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String?,
        val requestId: String?,
        val retryAfter: String?,
        val durationMillis: Long,
        val oversized: Boolean = false,
    ) : HttpResult()

    /** [detail] is the cause as "ExceptionName: message"; it is sanitised before any log line shows it. */
    class Failure(val kind: TransportFailure, val detail: String) : HttpResult()
}

/** "ExceptionName: message", or the name alone: what a transport failure log line names as its cause. */
internal fun transportCause(failure: Throwable): String {
    val name = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name }
    val message = failure.message?.trim()
    return if (message.isNullOrEmpty()) name else "$name: $message"
}

/** Sends one request synchronously; called on the SallaPush-net thread only. */
internal fun interface Transport {
    fun send(request: HttpRequest): HttpResult
}

/**
 * HttpURLConnection, no OkHttp (hosts pin their own OkHttp). Redirects are never followed, so a Device secret or an
 * identity token is never re-sent to another host; caches are off. Socket timeouts bound each phase (connect 15 s,
 * each read 20 s or 30 s for a login's link), and [totalTimeoutMillis] bounds the whole exchange: when it is spent, a
 * watchdog closes the connection, so a server or middlebox that trickles bytes can never hold the only request slot.
 */
internal class HttpUrlConnectionTransport(
    private val userAgent: String,
    private val totalTimeoutMillis: Long = TOTAL_TIMEOUT_MILLIS,
) : Transport {

    override fun send(request: HttpRequest): HttpResult = withSocketTag { exchange(request) }

    private fun exchange(request: HttpRequest): HttpResult {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis)
        val timedOut = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>()
        val watchdog = Deadlines.arm(totalTimeoutMillis, Runnable {
            timedOut.set(true)
            connection.get()?.let { disconnect(it) }
        })
        return try {
            val opened = URL(request.url).openConnection() as? HttpURLConnection
                ?: return HttpResult.Failure(TransportFailure.OTHER, "not an HTTP URL")
            connection.set(opened)
            if (timedOut.get()) throw SocketTimeoutException("deadline")
            opened.instanceFollowRedirects = false
            opened.useCaches = false
            opened.connectTimeout = phaseTimeout(CONNECT_TIMEOUT_MILLIS, deadline)
            opened.readTimeout = phaseTimeout(request.readTimeoutMillis, deadline)
            opened.requestMethod = request.method
            opened.setRequestProperty("Accept", "application/json")
            opened.setRequestProperty("User-Agent", userAgent)
            request.authorization?.let { opened.setRequestProperty("Authorization", "Device " + it.reveal()) }
            val body = request.body
            if (body != null) {
                opened.doOutput = true
                opened.setRequestProperty("Content-Type", "application/json")
                // Buffered: with a streamed body an HttpURLConnection can lose a 401's answer (the JDK's always does;
                // Android's only when it can answer the 401's challenge, which push-service never sends), and the error
                // code of a 401 decides whether to register again. A connection may send a buffered body
                // again by itself when a connection drops; every push-service route takes that, a link included.
                opened.outputStream.use { it.write(body) }
            }
            val status = opened.responseCode
            val stream = if (status >= HTTP_BAD_REQUEST) opened.errorStream else opened.inputStreamOrNull()
            val bytes = stream?.use { readCapped(it, deadline, timedOut) } ?: ByteArray(0)
            HttpResult.Response(
                status = status,
                body = bytes,
                contentType = opened.getHeaderField("Content-Type"),
                requestId = opened.getHeaderField("X-Request-Id"),
                retryAfter = opened.getHeaderField("Retry-After"),
                durationMillis = (System.nanoTime() - started) / NANOS_PER_MILLI,
                oversized = bytes.size > MAX_BODY_BYTES,
            )
        } catch (e: SocketTimeoutException) {
            // A socket timeout says which phase timed out ("connect timed out", "Read timed out").
            HttpResult.Failure(TransportFailure.TIMEOUT, if (timedOut.get()) deadlineCause() else transportCause(e))
        } catch (e: SSLException) {
            if (timedOut.get()) HttpResult.Failure(TransportFailure.TIMEOUT, deadlineCause()) else HttpResult.Failure(TransportFailure.TLS, transportCause(e))
        } catch (e: IOException) {
            if (timedOut.get()) HttpResult.Failure(TransportFailure.TIMEOUT, deadlineCause()) else HttpResult.Failure(TransportFailure.NETWORK, transportCause(e))
        } catch (e: Exception) {
            if (timedOut.get()) HttpResult.Failure(TransportFailure.TIMEOUT, deadlineCause()) else HttpResult.Failure(TransportFailure.OTHER, transportCause(e))
        } finally {
            watchdog.cancel()
            connection.get()?.let { disconnect(it) }
        }
    }

    private fun deadlineCause(): String {
        val seconds = totalTimeoutMillis / MILLIS_PER_SECOND.toDouble()
        val text = if (seconds == Math.floor(seconds)) seconds.toLong().toString() else seconds.toString()
        return "no complete answer within $text s"
    }

    /** A phase's socket timeout, never beyond what is left of the whole exchange (at least 1 ms: 0 means "none"). */
    private fun phaseTimeout(phaseMillis: Int, deadline: Long): Int {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        return remaining.coerceIn(1L, phaseMillis.toLong()).toInt()
    }

    private fun HttpURLConnection.inputStreamOrNull(): InputStream? {
        return try {
            inputStream
        } catch (e: IOException) {
            errorStream
        }
    }

    /**
     * Reads the body before [deadline], at most [MAX_BODY_BYTES] + 1 bytes: one byte past the cap tells a body that is
     * too long (refused, never parsed) from one that fits; the rest is never downloaded.
     */
    private fun readCapped(stream: InputStream, deadline: Long, timedOut: AtomicBoolean): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (buffer.size() <= MAX_BODY_BYTES) {
            if (System.nanoTime() >= deadline) {
                timedOut.set(true)
                throw SocketTimeoutException("deadline")
            }
            val read = stream.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, minOf(read, MAX_BODY_BYTES + 1 - buffer.size()))
        }
        return buffer.toByteArray()
    }

    private fun disconnect(connection: HttpURLConnection) {
        try {
            connection.disconnect()
        } catch (ignored: Throwable) {
            // Already closed.
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val LOGIN_READ_TIMEOUT_MILLIS = 30_000

        /** The whole exchange, like the iOS SDK's resource timeout. */
        const val TOTAL_TIMEOUT_MILLIS = 60_000L
        private const val HTTP_BAD_REQUEST = 400

        /**
         * The longest answer read: above the largest the contract allows (a tags answer of 100 keys of 64 characters,
         * each value 255 UTF-16 units, JSON-escaped to up to 6 bytes a unit: about 160 KB), as on iOS.
         */
        const val MAX_BODY_BYTES = 262_144
        private const val CHUNK_BYTES = 8_192
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
