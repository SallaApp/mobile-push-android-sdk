package com.salla.push.internal.lifecycle

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import com.salla.push.internal.util.MainThread
import java.util.Collections
import java.util.WeakHashMap

/** Receives app-level lifecycle changes; called on the main thread. */
internal interface ForegroundListener {
    /**
     * The app came to the foreground. [afterScreenOff]: it went to the background because the screen turned off, so this
     * is usually the phone unlocked with the app on screen, not a new visit.
     */
    fun onForeground(afterScreenOff: Boolean)

    fun onBackground()

    fun onActivityResumed()
}

/**
 * The SDK's own foreground detection, from ActivityLifecycleCallbacks (not ProcessLifecycleOwner: hosts may strip
 * androidx.startup), registered by initWithContext on the caller's thread (registration does no I/O). Until an
 * activity callback arrives, or the process importance read on an SDK thread is applied (a late initialisation, with
 * an activity already showing), the state is [State.UNKNOWN]: decisions that need it wait in [runWhenKnown]. An
 * activity that started before registration counts once it is seen resumed, until it stops. Every callback body is
 * guarded: nothing SallaPush does here can throw into the host's activity lifecycle.
 */
internal class AppLifecycle(
    private val mainThread: MainThread,
    private val listener: ForegroundListener,
) : Application.ActivityLifecycleCallbacks {

    /** Whether the app has a visible activity. */
    enum class State {
        UNKNOWN,
        FOREGROUND,
        BACKGROUND,
    }

    // Main thread only.
    private val started: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /** Activities seen resumed that started before this tracker was registered. */
    private val untracked: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /** The process importance said "foreground" before any activity callback; the first callback supersedes it. */
    private var importanceSeed = false

    /** An activity started, resumed or stopped since registration: an importance read applied later would be older. */
    private var callbackSeen = false
    private var backgroundCheck: Runnable? = null

    /**
     * The last visible activity stopped while the screen was off: the lock screen stops every activity, and the next
     * foreground is usually the unlock, with the app still on screen. After an unlock into another app, it is the app's
     * next opening, which is reported the same way.
     */
    private var backgroundedWithScreenOff = false

    private val whenKnown = ArrayList<Runnable>()

    @Volatile
    var state: State = State.UNKNOWN
        private set

    val isForeground: Boolean
        get() = state == State.FOREGROUND

    @Volatile
    private var registered = false

    /** Registers once per process; false when the platform refused (the importance seed still sets the state). */
    fun register(application: Application): Boolean {
        if (registered) return true
        return try {
            application.registerActivityLifecycleCallbacks(this)
            registered = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Applies the process importance (read off the caller's thread) unless an activity callback came first. */
    fun seed(foregroundNow: Boolean) {
        mainThread.post(Runnable {
            guarded {
                if (state == State.UNKNOWN && !callbackSeen) {
                    if (foregroundNow) {
                        importanceSeed = true
                        becomeForeground()
                    } else {
                        state = State.BACKGROUND
                        runKnownActions()
                    }
                }
            }
        })
    }

    /** Runs [action] on the main thread once the state is known: at once when it already is. */
    fun runWhenKnown(action: Runnable) {
        mainThread.post(Runnable {
            guarded {
                if (state == State.UNKNOWN) whenKnown.add(action) else action.run()
            }
        })
    }

    override fun onActivityStarted(activity: Activity) = guarded {
        callbackSeen = true
        started.add(activity)
        untracked.remove(activity)
        importanceSeed = false
        backgroundCheck = null
        if (state != State.FOREGROUND) becomeForeground()
    }

    override fun onActivityStopped(activity: Activity) = guarded {
        callbackSeen = true
        if (!started.remove(activity)) {
            untracked.remove(activity)
            importanceSeed = false
        }
        if (visible()) return@guarded
        backgroundedWithScreenOff = !screenOn(activity)
        // A configuration change stops and restarts within a frame or two: wait before reporting the background.
        val check = object : Runnable {
            override fun run() = guarded { reportBackground(this) }
        }
        backgroundCheck = check
        mainThread.postDelayed(check, BACKGROUND_DELAY_MILLIS)
    }

    /**
     * A started activity, one seen resumed that started before registration (it stays visible under a translucent
     * activity such as the permission prompt), or the importance seed while no activity callback arrived yet.
     */
    private fun visible(): Boolean = started.isNotEmpty() || untracked.isNotEmpty() || importanceSeed

    /** The delayed [check] of the last stop: the background, unless an activity became visible again since. */
    private fun reportBackground(check: Runnable) {
        if (backgroundCheck !== check || visible()) return
        backgroundCheck = null
        val wasForeground = state == State.FOREGROUND
        state = State.BACKGROUND
        runKnownActions()
        if (wasForeground) guarded { listener.onBackground() }
    }

    override fun onActivityResumed(activity: Activity) = guarded {
        callbackSeen = true
        if (activity !in started) {
            untracked.add(activity)
            importanceSeed = false
            backgroundCheck = null
            if (state != State.FOREGROUND) becomeForeground()
        }
        guarded { listener.onActivityResumed() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun becomeForeground() {
        state = State.FOREGROUND
        val afterScreenOff = backgroundedWithScreenOff
        backgroundedWithScreenOff = false
        guarded { listener.onForeground(afterScreenOff) }
        runKnownActions()
    }

    /** Whether the screen is on (PowerManager.isInteractive, one cheap system call); true when it can't be read. */
    private fun screenOn(activity: Activity): Boolean = try {
        (activity.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    } catch (e: Exception) {
        true
    }

    private fun runKnownActions() {
        if (whenKnown.isEmpty()) return
        val actions = ArrayList(whenKnown)
        whenKnown.clear()
        for (action in actions) guarded { action.run() }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // A failure inside SallaPush must never reach the host's lifecycle callbacks.
        }
    }

    companion object {
        private const val BACKGROUND_DELAY_MILLIS = 700L

        /** Whether this process is in the foreground now (an IPC: never call it on the caller thread of initWithContext). */
        fun processIsForeground(): Boolean {
            return try {
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } catch (e: Exception) {
                false
            }
        }
    }
}
