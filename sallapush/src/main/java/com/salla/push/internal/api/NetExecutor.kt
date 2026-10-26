package com.salla.push.internal.api

import com.salla.push.internal.util.Cancellable
import com.salla.push.internal.util.Once
import com.salla.push.internal.util.TaskRunner

/** Submits requests; [onResult] always runs once, on the engine thread. */
internal fun interface Network {
    fun submit(request: HttpRequest, onResult: (HttpResult) -> Unit)
}

/**
 * Runs one HttpURLConnection at a time on the SallaPush-net thread and posts each result back to the engine thread.
 * The engine thread therefore never blocks on network I/O, and a logout reaches disk while a request hangs.
 *
 * Each request is answered exactly once, and always: a request the net thread refuses is answered as failed (its task
 * can never run later); an answer the engine thread refuses (a thread it cannot start) is posted again a few times;
 * and a watchdog on the engine thread answers a request that got no answer within [watchdogMillis] as a timeout, so a
 * lost answer never leaves the engine waiting for the rest of the process. The pending watchdog also keeps the engine
 * thread alive while a request is on the wire, so delivering the answer needs no new thread.
 */
internal class NetExecutor(
    private val transport: Transport,
    private val netRunner: TaskRunner,
    private val engineRunner: TaskRunner,
    private val watchdogMillis: Long = WATCHDOG_MILLIS,
) : Network {

    override fun submit(request: HttpRequest, onResult: (HttpResult) -> Unit) {
        val once = Once()
        val answer = { result: HttpResult -> Runnable { if (once.claim()) onResult(result) } }
        val noAnswer = HttpResult.Failure(TransportFailure.TIMEOUT, "no answer reached SallaPush within ${watchdogMillis / MILLIS_PER_SECOND} s")
        val watchdog = engineRunner.schedule(watchdogMillis, answer(noAnswer))
        val accepted = netRunner.execute(
            Runnable {
                val result = try {
                    transport.send(request)
                } catch (t: Throwable) {
                    HttpResult.Failure(TransportFailure.OTHER, transportCause(t))
                }
                post(answer(result), watchdog, 0)
            },
        )
        if (!accepted) post(answer(HttpResult.Failure(TransportFailure.OTHER, "the SallaPush network thread could not start")), watchdog, 0)
    }

    /** Posts [answer] to the engine thread; a refusal is retried from the net thread, after that the watchdog answers. */
    private fun post(answer: Runnable, watchdog: Cancellable, attempt: Int) {
        if (engineRunner.execute(answer)) {
            watchdog.cancel()
            return
        }
        if (attempt >= POST_RETRY_DELAYS_MILLIS.size) return
        netRunner.schedule(POST_RETRY_DELAYS_MILLIS[attempt], Runnable { post(answer, watchdog, attempt + 1) })
    }

    companion object {
        /** The transport's own total deadline (60 s) plus a margin: only an answer that was lost waits this long. */
        const val WATCHDOG_MILLIS = 90_000L

        private const val MILLIS_PER_SECOND = 1_000L
        private val POST_RETRY_DELAYS_MILLIS = longArrayOf(50L, 500L, 5_000L)
    }
}
