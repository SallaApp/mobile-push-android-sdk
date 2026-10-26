package com.salla.push.internal.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.salla.push.internal.util.MainThread
import com.salla.push.internal.util.Once

internal object NotificationPermission {

    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** True when this app may post notifications (the runtime permission on Android 13+ and the app-level switch). */
    fun allowed(context: Context): Boolean {
        val enabled = try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (e: Exception) {
            return false
        }
        return enabled && runtimeGranted(context)
    }

    fun runtimeGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Permission requests: never automatic; only while the app is in the foreground; through the
 * non-exported translucent [SallaPushPermissionActivity]. Requests made while one is showing share its answer.
 * Every callback runs once, on the main thread. Each request has an id: an answer for an older request changes
 * nothing, and a request whose activity never starts is answered with the current state after [START_TIMEOUT_MILLIS].
 */
internal class PermissionRequests(private val mainThread: MainThread) {

    private val lock = Any()
    private val waiting = ArrayList<(Boolean) -> Unit>()
    private var inProgress = false
    private var requestId = 0L
    private var activityCreated = false

    /**
     * Set when the system reported a denial that can no longer be prompted for ("don't ask again"). Android cannot
     * tell that apart from "never asked", and it is kept in memory only, so it is known from that answer until the
     * process ends.
     */
    @Volatile
    var permanentlyDenied = false

    fun canRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationPermission.runtimeGranted(context) && !permanentlyDenied

    /** Starts (or joins) a request; [onResult] runs once on the main thread. */
    fun request(context: Context, fallbackToSettings: Boolean, isForeground: Boolean, onResult: (Boolean) -> Unit) {
        val once = Once()
        val callback: (Boolean) -> Unit = { granted ->
            if (once.claim()) mainThread.post(Runnable { onResult(granted) })
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || NotificationPermission.runtimeGranted(context)) {
            callback(NotificationPermission.allowed(context))
            return
        }
        if (!isForeground) {
            callback(false)
            return
        }
        var startedId = 0L
        val start = synchronized(lock) {
            waiting.add(callback)
            if (inProgress) {
                false
            } else {
                inProgress = true
                activityCreated = false
                requestId += 1
                startedId = requestId
                true
            }
        }
        if (!start) return
        val started = try {
            val intent = Intent(context, SallaPushPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SallaPushPermissionActivity.EXTRA_FALLBACK_TO_SETTINGS, fallbackToSettings)
                .putExtra(SallaPushPermissionActivity.EXTRA_REQUEST_ID, startedId)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
        if (!started) {
            complete(startedId, NotificationPermission.allowed(context))
            return
        }
        // startActivity can return without starting anything (a background activity start blocked on Android 10+,
        // an OEM pop-up restriction): then nothing would ever answer, and every later request would join this one.
        mainThread.postDelayed(Runnable { onStartTimeout(context, startedId) }, START_TIMEOUT_MILLIS)
    }

    /** The activity of request [id] was created: from now on it answers, with its result or its teardown. */
    fun onActivityCreated(id: Long) {
        synchronized(lock) {
            if (id == requestId) activityCreated = true
        }
    }

    private fun onStartTimeout(context: Context, id: Long) {
        val expired = synchronized(lock) { inProgress && requestId == id && !activityCreated }
        if (expired) complete(id, NotificationPermission.allowed(context))
    }

    /** Answers request [id] with [granted]; an answer for any other request changes nothing. */
    fun complete(id: Long, granted: Boolean) {
        val callbacks = synchronized(lock) {
            if (!inProgress || id != requestId) return
            inProgress = false
            val copy = ArrayList(waiting)
            waiting.clear()
            copy
        }
        for (callback in callbacks) callback(granted)
    }

    companion object {
        const val START_TIMEOUT_MILLIS = 10_000L
    }
}
