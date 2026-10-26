package com.salla.push.internal.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A scheduled task that can be cancelled. Cancelling twice, or after the task ran, does nothing. */
internal fun interface Cancellable {
    fun cancel()
}

/** A serial task queue backed by one thread. */
internal interface TaskRunner {
    /** Queues [task]; false when the runner refused it. */
    fun execute(task: Runnable): Boolean

    /** Runs [task] after [delayMillis] (clamped to 0…24 h). */
    fun schedule(delayMillis: Long, task: Runnable): Cancellable
}

/**
 * One named daemon thread. Every task is wrapped, so a failure is reported to [onFailure] and never escapes the
 * thread. A task the executor refuses, or cannot start a thread for (an OutOfMemoryError from pthread_create, which
 * the idle thread timeout makes possible at any later call), is a false result or a no-op Cancellable, reported once.
 * Such a refusal is final: the executor queues a task before it starts its thread, so the refused task is revoked and
 * never runs, even once a later call has started the thread.
 */
internal class ThreadRunner(
    private val threads: ThreadFactory,
    private val onFailure: (Throwable) -> Unit,
) : TaskRunner {

    /** One daemon thread named [threadName]. */
    constructor(threadName: String, onFailure: (Throwable) -> Unit) : this(DaemonThreads(threadName), onFailure)

    private val refusalReported = AtomicBoolean(false)

    private val executor: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1, threads).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    override fun execute(task: Runnable): Boolean {
        val revocable = Revocable(guard(task))
        return try {
            executor.execute(revocable)
            true
        } catch (t: Throwable) {
            revocable.revoke()
            reportRefusal(t)
            false
        }
    }

    override fun schedule(delayMillis: Long, task: Runnable): Cancellable {
        val delay = delayMillis.coerceIn(0L, MAX_DELAY_MILLIS)
        val revocable = Revocable(guard(task))
        return try {
            val future = executor.schedule(revocable, delay, TimeUnit.MILLISECONDS)
            Cancellable { future.cancel(false) }
        } catch (t: Throwable) {
            revocable.revoke()
            reportRefusal(t)
            Cancellable { }
        }
    }

    /** A task that does nothing once revoked: the refused task may already sit in the executor's queue. */
    private class Revocable(private val task: Runnable) : Runnable {
        private val revoked = AtomicBoolean(false)

        fun revoke() {
            revoked.set(true)
        }

        override fun run() {
            if (!revoked.get()) task.run()
        }
    }

    private fun reportRefusal(failure: Throwable) {
        if (refusalReported.compareAndSet(false, true)) reportSafely(failure)
    }

    private fun guard(task: Runnable): Runnable = Runnable {
        try {
            task.run()
        } catch (t: Throwable) {
            reportSafely(t)
        }
    }

    private fun reportSafely(failure: Throwable) {
        try {
            onFailure(failure)
        } catch (ignored: Throwable) {
            // The failure reporter is best effort.
        }
    }

    private class DaemonThreads(private val name: String) : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread {
            val thread = Thread({
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                } catch (ignored: Throwable) {
                    // Priority is a hint only.
                }
                runnable.run()
            }, name)
            thread.isDaemon = true
            return thread
        }
    }

    private companion object {
        const val IDLE_SECONDS = 60L
        const val MAX_DELAY_MILLIS = 86_400_000L
    }
}

/** Posts work to the main thread. */
internal interface MainThread {
    fun post(task: Runnable)

    fun postDelayed(task: Runnable, delayMillis: Long)

    fun isCurrent(): Boolean
}

internal object AndroidMainThread : MainThread {

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    override fun post(task: Runnable) {
        try {
            handler.post(task)
        } catch (ignored: Throwable) {
            // A dead main looper means the process is going away; nothing to deliver to.
        }
    }

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        try {
            handler.postDelayed(task, delayMillis)
        } catch (ignored: Throwable) {
            // A dead main looper means the process is going away; nothing to deliver to.
        }
    }

    override fun isCurrent(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}
