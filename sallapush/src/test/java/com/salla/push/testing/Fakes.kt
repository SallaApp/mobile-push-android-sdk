package com.salla.push.testing

import android.graphics.Bitmap
import com.salla.push.internal.FirebaseToken
import com.salla.push.internal.FirebaseTokenSource
import com.salla.push.internal.api.HttpRequest
import com.salla.push.internal.api.HttpResult
import com.salla.push.internal.api.Network
import com.salla.push.internal.api.TransportFailure
import com.salla.push.internal.device.DeviceFacts
import com.salla.push.internal.device.DeviceInfoSource
import com.salla.push.internal.display.ImageFetcher
import com.salla.push.internal.display.ImageResult
import com.salla.push.internal.lifecycle.ConnectivityMonitor
import com.salla.push.internal.lifecycle.DeviceSettingsMonitor
import com.salla.push.internal.storage.SecretBox
import com.salla.push.internal.util.Cancellable
import com.salla.push.internal.util.Clock
import com.salla.push.internal.util.Jitter
import com.salla.push.internal.util.MainThread
import com.salla.push.internal.util.TaskRunner
import com.salla.push.internal.work.WorkScheduler
import org.json.JSONArray
import org.json.JSONObject

/** A settable wall clock. */
internal class FakeClock(var millis: Long = START_MILLIS) : Clock {
    override fun nowMillis(): Long = millis

    val seconds: Double get() = millis / 1000.0

    companion object {
        /** 2026-09-21T22:13:20Z. */
        const val START_MILLIS = 1_790_000_000_000L
    }
}

/** Jitter with a value the test sets: backoff delays and the background events work's spread become deterministic. */
internal class FixedJitter(@Volatile var value: Double) : Jitter {
    override fun next(): Double = value
}

/**
 * A serial runner driven by the test: tasks run only when the test asks, in due-time then insertion order, against a
 * [FakeClock]. No real thread, no real sleep.
 */
internal class ManualRunner(private val clock: FakeClock) : TaskRunner {

    private class Task(val dueMillis: Long, val sequence: Long, val runnable: Runnable) {
        @Volatile
        var cancelled = false
    }

    private val tasks = ArrayList<Task>()
    private var sequence = 0L

    /** When true, every execute/schedule is refused, like a shut-down executor. */
    var refuse = false

    override fun execute(task: Runnable): Boolean {
        if (refuse) return false
        synchronized(tasks) { tasks.add(Task(clock.millis, sequence++, task)) }
        return true
    }

    override fun schedule(delayMillis: Long, task: Runnable): Cancellable {
        if (refuse) return Cancellable { }
        val scheduled = Task(clock.millis + maxOf(0L, delayMillis), sequence++, task)
        synchronized(tasks) { tasks.add(scheduled) }
        return Cancellable { scheduled.cancelled = true }
    }

    /** Runs every task due now, including the ones they post. Returns how many ran. */
    fun runDue(): Int {
        var count = 0
        while (true) {
            if (count > MAX_TASKS_PER_DRAIN) throw AssertionError("a task keeps re-posting itself without the clock moving")
            val next = synchronized(tasks) {
                tasks.removeAll { it.cancelled }
                val candidate = tasks.filter { it.dueMillis <= clock.millis }.minWithOrNull(compareBy<Task>({ it.dueMillis }, { it.sequence }))
                if (candidate != null) tasks.remove(candidate)
                candidate
            } ?: return count
            val outer = running.get()
            running.set(true)
            try {
                next.runnable.run()
            } finally {
                running.set(outer)
            }
            count += 1
        }
    }

    /** The earliest time a pending task is due, or null. */
    fun nextDueMillis(): Long? = synchronized(tasks) { tasks.filter { !it.cancelled }.minOfOrNull { it.dueMillis } }

    fun pendingCount(): Int = synchronized(tasks) { tasks.count { !it.cancelled } }

    companion object {
        private const val MAX_TASKS_PER_DRAIN = 50_000
        private val running = ThreadLocal<Boolean>()

        /** Whether the calling code runs inside a task of some ManualRunner (an SDK thread in production). */
        fun inTask(): Boolean = running.get() == true
    }
}

/** The main thread of a test: posted tasks run when the test drains them. */
internal class FakeMainThread(private val clock: FakeClock) : MainThread {

    private class Task(val dueMillis: Long, val runnable: Runnable)

    private val tasks = ArrayList<Task>()

    /** What [isCurrent] answers: whether the calling code is treated as running on the main thread. */
    @Volatile
    var current: Boolean = false

    override fun post(task: Runnable) {
        synchronized(tasks) { tasks.add(Task(clock.millis, task)) }
    }

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        synchronized(tasks) { tasks.add(Task(clock.millis + delayMillis, task)) }
    }

    override fun isCurrent(): Boolean = current

    fun runDue(): Int {
        var count = 0
        while (true) {
            val next = synchronized(tasks) {
                val candidate = tasks.firstOrNull { it.dueMillis <= clock.millis }
                if (candidate != null) tasks.remove(candidate)
                candidate
            } ?: return count
            val wasCurrent = current
            current = true
            try {
                next.runnable.run()
            } finally {
                current = wasCurrent
            }
            count += 1
        }
    }

    fun nextDueMillis(): Long? = synchronized(tasks) { tasks.minOfOrNull { it.dueMillis } }
}

/** One request seen by [FakeNetwork]. */
internal class FakeCall(val request: HttpRequest, private val complete: (HttpResult) -> Unit) {

    var completed = false
        private set

    val method: String get() = request.method
    val route: String get() = request.route
    val url: String get() = request.url

    val body: JSONObject?
        get() = request.body?.let { JSONObject(String(it, Charsets.UTF_8)) }

    val authorization: String? get() = request.authorization?.reveal()

    fun respond(result: HttpResult) {
        if (completed) return
        completed = true
        complete(result)
    }
}

/**
 * A scriptable network: every request is recorded and answered by the handler registered for its method and route
 * template. A handler returning null holds the request until the test answers it. Answers reach the engine as a
 * separate task, like the real net thread.
 */
internal class FakeNetwork(private val engineRunner: TaskRunner) : Network {

    val calls = ArrayList<FakeCall>()
    private val handlers = LinkedHashMap<String, (FakeCall) -> HttpResult?>()

    var fallback: (FakeCall) -> HttpResult? = { Http.networkFailure() }

    fun on(method: String, route: String, handler: (FakeCall) -> HttpResult?) {
        handlers["$method $route"] = handler
    }

    override fun submit(request: HttpRequest, onResult: (HttpResult) -> Unit) {
        val call = FakeCall(request) { result -> engineRunner.execute(Runnable { onResult(result) }) }
        synchronized(calls) { calls.add(call) }
        val handler = handlers["${request.method} ${request.route}"] ?: fallback
        handler(call)?.let { call.respond(it) }
    }

    fun calls(route: String): List<FakeCall> = synchronized(calls) { calls.filter { it.route == route } }

    fun held(): List<FakeCall> = synchronized(calls) { calls.filter { !it.completed } }

    fun clear() = synchronized(calls) { calls.clear() }
}

/** Canned answers of the server's routes. */
internal object Http {

    const val DEVICE_ID = "3f2a8c1e-0000-4000-8000-00000000d001"
    const val DEVICE_ID_2 = "3f2a8c1e-0000-4000-8000-00000000d002"
    const val SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQ"

    fun json(
        status: Int,
        body: String,
        requestId: String? = "req-${status}",
        retryAfter: String? = null,
        contentType: String? = "application/json",
    ): HttpResult = HttpResult.Response(status, body.toByteArray(Charsets.UTF_8), contentType, requestId, retryAfter, 7)

    /** The server's registration answer: a new device row. */
    fun registered(deviceId: String = DEVICE_ID, secret: String = SECRET): HttpResult =
        json(201, """{"device_id":"$deviceId","device_secret":"$secret"}""")

    /** The server's answer to an update, a link or an unlink. */
    fun empty(): HttpResult = json(200, "{}")

    fun tags(tags: Map<String, String>): HttpResult = json(200, JSONObject().put("tags", JSONObject(tags)).toString())

    fun events(accepted: Int, ignored: Int = 0): HttpResult = json(202, """{"accepted":$accepted,"ignored":$ignored}""")

    fun error(status: Int, code: String, reason: String? = null, message: String = "Rejected", retryAfter: String? = null): HttpResult {
        val error = JSONObject().put("code", code).put("message", message)
        if (reason != null) error.put("reason", reason)
        return json(status, JSONObject().put("error", error).toString(), retryAfter = retryAfter)
    }

    fun html(status: Int): HttpResult = json(status, "<html><body>Bad gateway</body></html>", contentType = "text/html")

    fun networkFailure(): HttpResult = HttpResult.Failure(TransportFailure.NETWORK, "IOException")

    fun timeout(): HttpResult = HttpResult.Failure(TransportFailure.TIMEOUT, "timeout")
}

/** A reversible stand-in for the Android Keystore (Robolectric has none), with scriptable failures. */
internal class FakeSecretBox : SecretBox {
    var failSeal = false
    var failOpen = false
    var resets = 0

    override fun seal(plain: ByteArray): ByteArray? {
        if (failSeal) return null
        return MARKER + plain.reversedArray()
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (failOpen || sealed.size < MARKER.size) return null
        for (index in MARKER.indices) if (sealed[index] != MARKER[index]) return null
        return sealed.copyOfRange(MARKER.size, sealed.size).reversedArray()
    }

    override fun reset() {
        resets += 1
    }

    private companion object {
        val MARKER = byteArrayOf(0x53, 0x50, 0x01)
    }
}

internal class FakeDeviceInfo : DeviceInfoSource {
    var appVersion: String? = "5.0.37"
    var osVersion: String? = "14"
    var model: String? = "Pixel 8"
    var language: String? = "ar"
    var zone: String? = "Asia/Riyadh"

    override fun facts(): DeviceFacts = DeviceFacts(appVersion, "1.1.0", osVersion, model)

    override fun deviceLanguage(): String? = language

    override fun timezone(): String? = zone
}

/**
 * Records the WorkManager requests. Kinds: "flush" and "identity" (the unique works; [Scheduled.replace] tells KEEP
 * from REPLACE), "cancel" (both works) and "cancelFlush" (the flush work alone).
 */
internal class FakeWorkScheduler : WorkScheduler {
    data class Scheduled(
        val kind: String,
        val appId: String,
        val hostTag: String,
        val apiBaseUrl: String,
        val delaySeconds: Long,
        val replace: Boolean = false,
        val consentRequired: Boolean = false,
    )

    val scheduled = ArrayList<Scheduled>()

    /** Told of each request as it is made, so a test can look at the SDK's files at that moment. */
    var onRequest: ((Scheduled) -> Unit)? = null

    override fun scheduleFlush(appId: String, hostTag: String, apiBaseUrl: String, delaySeconds: Long, replace: Boolean, consentRequired: Boolean) {
        record(Scheduled("flush", appId, hostTag, apiBaseUrl, delaySeconds, replace, consentRequired))
    }

    override fun scheduleIdentity(appId: String, hostTag: String, apiBaseUrl: String, delaySeconds: Long, replace: Boolean, consentRequired: Boolean) {
        record(Scheduled("identity", appId, hostTag, apiBaseUrl, delaySeconds, replace, consentRequired))
    }

    override fun cancel(appId: String, hostTag: String) {
        record(Scheduled("cancel", appId, hostTag, "", 0L))
    }

    override fun cancelFlush(appId: String, hostTag: String) {
        record(Scheduled("cancelFlush", appId, hostTag, "", 0L))
    }

    private fun record(request: Scheduled) {
        scheduled.add(request)
        onRequest?.invoke(request)
    }

    fun of(kind: String): List<Scheduled> = scheduled.filter { it.kind == kind }

    /** The flush works enqueued with REPLACE: a run's follow-up, or a run due earlier than the enqueued one. */
    fun replacingFlushes(): List<Scheduled> = scheduled.filter { it.kind == "flush" && it.replace }
}

internal class FakeConnectivity : ConnectivityMonitor {
    var started = false
    var starts = 0
    var stops = 0
    private var callback: (() -> Unit)? = null

    override fun start(onAvailable: () -> Unit) {
        if (started) return
        started = true
        starts += 1
        callback = onAvailable
    }

    override fun stop() {
        if (!started) return
        started = false
        stops += 1
        callback = null
    }

    /** Simulates a network becoming available. */
    fun networkAvailable() {
        callback?.invoke()
    }
}

/** A time zone / locale monitor driven by the test. */
internal class FakeDeviceSettings : DeviceSettingsMonitor {
    var starts = 0
    var stops = 0
    private var onChange: (() -> Unit)? = null

    override fun start(onChange: () -> Unit) {
        if (this.onChange != null) return
        starts += 1
        this.onChange = onChange
    }

    override fun stop() {
        if (onChange == null) return
        stops += 1
        onChange = null
    }

    val started: Boolean get() = onChange != null
}

/** Answers [result] at once; with [answers] false the token read never completes (Firebase still working on it). */
internal class FakeFirebase : FirebaseTokenSource {
    var result: FirebaseToken = FirebaseToken.FirebaseMissing
    var answers = true
    var fetches = 0

    override fun fetch(onResult: (FirebaseToken) -> Unit) {
        fetches += 1
        if (answers) onResult(result)
    }
}

/** Answers [bitmap] when set, else a failure with [failure]. */
internal class FakeImages : ImageFetcher {
    var bitmap: Bitmap? = null
    var failure = "HTTP 404"
    val requested = ArrayList<String>()

    override fun fetch(url: String): ImageResult {
        requested.add(url)
        return bitmap?.let { ImageResult.Loaded(it) } ?: ImageResult.Failed(failure)
    }
}

internal fun JSONObject.keysList(): List<String> = keys().asSequence().toList()

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
