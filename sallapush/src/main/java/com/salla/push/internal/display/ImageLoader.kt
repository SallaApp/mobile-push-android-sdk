package com.salla.push.internal.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.salla.push.internal.util.Deadlines
import com.salla.push.internal.util.withSocketTag
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The outcome of a notification picture download. */
internal sealed class ImageResult {
    class Loaded(val bitmap: Bitmap) : ImageResult()

    /** [reason] says why there is no picture, for example "HTTP 404" or "larger than 5 MB". */
    class Failed(val reason: String) : ImageResult()
}

/** Downloads a notification picture. */
internal fun interface ImageFetcher {
    /** The decoded picture, or why there is none (the notification is then shown as text). */
    fun fetch(url: String): ImageResult
}

/** Opens a connection for a URL: production opens the URL itself; a test points it at a local server. */
internal fun interface ConnectionOpener {
    fun open(url: URL): HttpURLConnection?
}

/**
 * The notification picture: https only, at most 5 MB, decoded bounds-first with inSampleSize so the
 * longer side is at most 1024 px, all within one budget of 8 s: DNS, connect, headers, at most [MAX_REDIRECTS] https
 * redirects (followed here, never by the platform) and the body. The download runs on a private thread and the caller
 * waits at most the budget; the download's deadline then closes its connection from the deadline thread. Neither a
 * slow or trickling server, a hanging DNS lookup, nor a connection whose close blocks holds the FCM thread longer.
 * Never throws; a failure says why.
 */
internal class ImageLoader(
    private val opener: ConnectionOpener = ConnectionOpener { url -> url.openConnection() as? HttpURLConnection },
    private val budgetMillis: Long = BUDGET_MILLIS,
) : ImageFetcher {

    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, MAX_DOWNLOADS, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "SallaPush-image").apply { isDaemon = true }
        }
    }

    override fun fetch(url: String): ImageResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        val download = Download(url, deadline)
        val future = try {
            executor.submit(Callable { download.run() })
        } catch (t: Throwable) {
            return ImageResult.Failed("the download could not start (at most $MAX_DOWNLOADS pictures download at once)")
        }
        return try {
            future.get(remainingMillis(deadline).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ImageResult.Failed("the download was interrupted")
        } catch (e: ExecutionException) {
            ImageResult.Failed("the download failed (${(e.cause ?: e).javaClass.simpleName})")
        } catch (t: Throwable) {
            // The download's own deadline closes its connection: this thread never waits for a close.
            future.cancel(true)
            budgetSpent()
        }
    }

    private fun budgetSpent(): ImageResult.Failed {
        val seconds = budgetMillis / MILLIS_PER_SECOND.toDouble()
        val text = if (seconds == Math.floor(seconds)) seconds.toLong().toString() else seconds.toString()
        return ImageResult.Failed("not loaded within $text s")
    }

    /** One download, run on an image thread; its deadline closes the connection when the budget is spent. */
    private inner class Download(private val url: String, private val deadline: Long) {

        private val current = AtomicReference<HttpURLConnection?>()

        @Volatile
        private var aborted = false

        fun run(): ImageResult = withSocketTag {
            val watchdog = Deadlines.arm(remainingMillis(deadline), Runnable { abort() })
            try {
                download()
            } catch (e: Exception) {
                if (aborted || remainingMillis(deadline) <= 0) budgetSpent() else ImageResult.Failed("network: ${e.javaClass.simpleName}")
            } catch (e: OutOfMemoryError) {
                ImageResult.Failed("out of memory")
            } finally {
                watchdog.cancel()
            }
        }

        /** Deadline thread: closing the connection makes the blocked connect or read fail. */
        private fun abort() {
            aborted = true
            current.getAndSet(null)?.let { disconnect(it) }
        }

        private fun download(): ImageResult {
            var target = httpsUrl(url) ?: return ImageResult.Failed("not an https URL")
            var requests = 0
            while (requests <= MAX_REDIRECTS) {
                requests += 1
                val remaining = remainingMillis(deadline)
                if (aborted || remaining <= 0) return budgetSpent()
                val connection = opener.open(target) ?: return ImageResult.Failed("could not open a connection")
                current.set(connection)
                try {
                    if (aborted) return budgetSpent()
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = remaining.toInt()
                    connection.readTimeout = remaining.toInt()
                    val status = connection.responseCode
                    if (status in 300..399) {
                        target = connection.getHeaderField("Location")?.let { redirectTarget(target, it) }
                            ?: return ImageResult.Failed("redirected (HTTP $status) to a URL that is not https")
                        continue
                    }
                    if (status !in 200..299) return ImageResult.Failed("HTTP $status")
                    if (connection.contentLength > MAX_BYTES) return tooLarge()
                    return readBody(connection)
                } finally {
                    current.compareAndSet(connection, null)
                    disconnect(connection)
                }
            }
            return ImageResult.Failed("more than $MAX_REDIRECTS redirects")
        }

        private fun readBody(connection: HttpURLConnection): ImageResult {
            connection.inputStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(CHUNK_BYTES)
                while (true) {
                    if (aborted || remainingMillis(deadline) <= 0) return budgetSpent()
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (buffer.size() + read > MAX_BYTES) return tooLarge()
                    buffer.write(chunk, 0, read)
                }
                return decode(buffer.toByteArray())?.let { ImageResult.Loaded(it) } ?: ImageResult.Failed("not a decodable image")
            }
        }
    }

    private fun tooLarge(): ImageResult.Failed = ImageResult.Failed("larger than ${MAX_BYTES / (1024 * 1024)} MB")

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun httpsUrl(text: String): URL? {
        return try {
            URL(text).takeIf { it.protocol == "https" && !it.host.isNullOrEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** A redirect is followed only to another https URL. */
    private fun redirectTarget(from: URL, location: String): URL? {
        return try {
            URL(from, location).takeIf { it.protocol == "https" && !it.host.isNullOrEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun disconnect(connection: HttpURLConnection) {
        try {
            connection.disconnect()
        } catch (ignored: Throwable) {
            // Already closed.
        }
    }

    private fun remainingMillis(deadline: Long): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())

    companion object {
        const val BUDGET_MILLIS = 8_000L
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_DIMENSION = 1024
        const val MAX_REDIRECTS = 3
        private const val MAX_DOWNLOADS = 2
        private const val IDLE_SECONDS = 30L
        private const val CHUNK_BYTES = 16_384
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
