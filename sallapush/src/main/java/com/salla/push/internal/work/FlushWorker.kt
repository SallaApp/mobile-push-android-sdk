package com.salla.push.internal.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.salla.push.internal.Sdk
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import com.salla.push.internal.util.Cancellable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands pending work to the platform so it survives process death. [consentRequired] travels with the work: a run in
 * a new process, where nothing required consent yet, then sends only once consent.json says "given".
 */
internal interface WorkScheduler {
    /**
     * Unique work "sallapush.flush.<appId>.<hostTag>". With [replace] false (KEEP) a running or enqueued run is kept;
     * with true (REPLACE) an enqueued one is replaced: a run due earlier, or the follow-up of a run that is ending.
     */
    fun scheduleFlush(appId: String, hostTag: String, apiBaseUrl: String, delaySeconds: Long, replace: Boolean, consentRequired: Boolean)

    /**
     * Unique work "sallapush.identity.<appId>.<hostTag>". With [replace] (REPLACE) a logout is never stuck behind a long
     * backoff; without it (KEEP, while a run is attached to the engine) a running run is never cancelled.
     */
    fun scheduleIdentity(appId: String, hostTag: String, apiBaseUrl: String, delaySeconds: Long, replace: Boolean, consentRequired: Boolean)

    /** Cancels both unique works of (appId, hostTag): nothing may run after a consent withdrawal. Best effort. */
    fun cancel(appId: String, hostTag: String)

    /** Cancels the unique flush work of (appId, hostTag) alone, once nothing is left for a run to send. Best effort. */
    fun cancelFlush(appId: String, hostTag: String)
}

/** The WorkManager calls the SDK makes; they throw when the host has no usable WorkManager. */
internal interface UniqueWorkQueue {
    fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)

    fun cancel(name: String)
}

/**
 * WorkManager, obtained lazily and never initialised by the SDK (hosts may replace its initializer). A host without
 * a usable WorkManager falls back to in-process retries. No expedited or periodic work, no foreground service.
 */
internal class WorkManagerScheduler(
    private val logger: Logger,
    private val queue: UniqueWorkQueue,
) : WorkScheduler {

    constructor(context: Context, logger: Logger) : this(
        logger,
        object : UniqueWorkQueue {
            override fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
                WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
            }

            override fun cancel(name: String) {
                WorkManager.getInstance(context).cancelUniqueWork(name)
            }
        },
    )

    private val unavailableLogged = AtomicBoolean(false)

    override fun scheduleFlush(appId: String, hostTag: String, apiBaseUrl: String, delaySeconds: Long, replace: Boolean, consentRequired: Boolean) {
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        enqueue("$FLUSH_PREFIX$appId.$hostTag", policy, appId, apiBaseUrl, delaySeconds, consentRequired)
    }

    override fun scheduleIdentity(appId: String, hostTag: String, apiBaseUrl: String, delaySeconds: Long, replace: Boolean, consentRequired: Boolean) {
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        enqueue("$IDENTITY_PREFIX$appId.$hostTag", policy, appId, apiBaseUrl, delaySeconds, consentRequired)
    }

    override fun cancel(appId: String, hostTag: String) {
        try {
            queue.cancel("$FLUSH_PREFIX$appId.$hostTag")
            queue.cancel("$IDENTITY_PREFIX$appId.$hostTag")
        } catch (e: Exception) {
            // Without a usable WorkManager nothing was enqueued either.
        }
    }

    override fun cancelFlush(appId: String, hostTag: String) {
        try {
            queue.cancel("$FLUSH_PREFIX$appId.$hostTag")
        } catch (e: Exception) {
            // Without a usable WorkManager nothing was enqueued either.
        }
    }

    private fun enqueue(name: String, policy: ExistingWorkPolicy, appId: String, apiBaseUrl: String, delaySeconds: Long, consentRequired: Boolean) {
        try {
            val request = OneTimeWorkRequest.Builder(FlushWorker::class.java)
                .setInitialDelay(delaySeconds.coerceIn(0L, MAX_DELAY_SECONDS), TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(
                    Data.Builder()
                        .putString(FlushWorker.KEY_APP_ID, appId)
                        .putString(FlushWorker.KEY_API_BASE_URL, apiBaseUrl)
                        .putBoolean(FlushWorker.KEY_CONSENT_REQUIRED, consentRequired)
                        .build(),
                )
                .build()
            queue.enqueue(name, policy, request)
        } catch (e: Exception) {
            reportUnavailable(e)
        }
    }

    private fun reportUnavailable(failure: Exception) {
        if (!unavailableLogged.compareAndSet(false, true)) return
        logger.log(LogCode.WORK_UNAVAILABLE) { "WorkManager is not available (${failure.javaClass.simpleName}); retries continue while the app runs." }
    }

    private companion object {
        const val FLUSH_PREFIX = "sallapush.flush."
        const val IDENTITY_PREFIX = "sallapush.identity."
        const val BACKOFF_SECONDS = 30L
        const val MAX_DELAY_SECONDS = 86_400L
    }
}

/**
 * Runs one engine flush, in a new process too: the engine is obtained from the process-wide registry (created from
 * the work's input or last_init.json when the app has not initialised). It sends everything due, update and tags
 * included, and returns success when nothing is left or when the engine enqueued a follow-up run for the time the
 * remaining work is due; retry only after a short transient backoff. A ListenableWorker: no WorkManager thread waits
 * while requests are on the wire (the host's WorkManager executor is shared by every worker of the app); the engine
 * completes the future. Kept by the consumer rules: WorkManager creates it by reflection, also through a host's
 * HiltWorkerFactory fallback.
 */
internal class FlushWorker(context: Context, parameters: WorkerParameters) : ListenableWorker(context, parameters) {

    @Volatile
    private var run: Cancellable? = null

    /** Called on the main thread: starts the flush on the engine thread and returns at once. */
    override fun startWork(): ListenableFuture<Result> {
        val future = WorkResultFuture<Result>()
        try {
            val started = FlushWork.start(
                applicationContext,
                inputData.getString(KEY_APP_ID),
                inputData.getString(KEY_API_BASE_URL),
                inputData.getBoolean(KEY_CONSENT_REQUIRED, false),
            ) { outcome -> future.complete(if (outcome == FlushOutcome.RETRY) Result.retry() else Result.success()) }
            run = started
            // Stopped while it was starting: onStopped found no run to release.
            if (isStopped) started.cancel()
        } catch (t: Throwable) {
            future.complete(Result.success())
        }
        return future
    }

    /** WorkManager stopped the run (cancelled, replaced, constraints lost or its time limit): the engine lets it go. */
    override fun onStopped() {
        try {
            run?.cancel()
        } catch (ignored: Throwable) {
            // The run's own bounded wait releases it too.
        }
    }

    companion object {
        const val KEY_APP_ID = "app_id"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_CONSENT_REQUIRED = "consent_required"
    }
}

/**
 * The future [FlushWorker.startWork] returns, completed by the engine: done once, by a value or a cancellation; each
 * listener runs once, on its executor, outside any lock. (WorkManager's own future types are library-internal, and the
 * SDK adds no dependency for one.)
 */
internal class WorkResultFuture<T : Any> : ListenableFuture<T> {

    private val lock = Any()
    private val done = CountDownLatch(1)
    private val state = AtomicInteger(PENDING)

    @Volatile
    private var value: T? = null
    private val listeners = ArrayList<Pair<Runnable, Executor>>()

    /** Completes the future with [result]; false when it was already done. */
    fun complete(result: T): Boolean {
        if (!state.compareAndSet(PENDING, COMPLETING)) return false
        value = result
        state.set(SUCCEEDED)
        finish()
        return true
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        if (!state.compareAndSet(PENDING, CANCELLED)) return false
        finish()
        return true
    }

    override fun isCancelled(): Boolean = state.get() == CANCELLED

    override fun isDone(): Boolean = done.count == 0L

    override fun get(): T {
        done.await()
        return outcome()
    }

    override fun get(timeout: Long, unit: TimeUnit): T {
        if (!done.await(timeout, unit)) throw TimeoutException("not done")
        return outcome()
    }

    override fun addListener(listener: Runnable, executor: Executor) {
        val runNow = synchronized(lock) {
            if (done.count == 0L) {
                true
            } else {
                listeners.add(listener to executor)
                false
            }
        }
        if (runNow) execute(listener, executor)
    }

    private fun finish() {
        val ready = synchronized(lock) {
            done.countDown()
            val copy = ArrayList(listeners)
            listeners.clear()
            copy
        }
        for ((listener, executor) in ready) execute(listener, executor)
    }

    private fun outcome(): T = value ?: throw CancellationException("cancelled")

    private fun execute(listener: Runnable, executor: Executor) {
        try {
            executor.execute(listener)
        } catch (ignored: Throwable) {
            // A listener's executor that refuses it is WorkManager's concern; the future stays done.
        }
    }

    private companion object {
        const val PENDING = 0
        const val COMPLETING = 1
        const val SUCCEEDED = 2
        const val CANCELLED = 3
    }
}

/** The worker's body, separate from WorkManager so it can be exercised directly. */
internal object FlushWork {

    /** The longest a run waits for the engine to settle; then it ends with RETRY and leaves the engine. */
    const val MAX_WAIT_MILLIS = 300_000L

    /**
     * Starts one flush and returns at once; [done] runs once, with the outcome (on the engine thread, or at once when
     * that thread cannot run). The returned handle releases the run (WorkManager stopped it).
     */
    fun start(context: Context, appId: String?, apiBaseUrl: String?, consentRequired: Boolean, done: (FlushOutcome) -> Unit): Cancellable =
        Sdk.core.startWorkerFlush(context, appId, apiBaseUrl, consentRequired, MAX_WAIT_MILLIS, done)
}
