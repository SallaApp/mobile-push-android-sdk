package com.salla.push.internal

import com.salla.push.SallaPushForegroundLifecycleListener
import com.salla.push.SallaPushLoginFailure
import com.salla.push.SallaPushPermissionObserver
import com.salla.push.SallaPushSubscriptionChangedState
import com.salla.push.SallaPushSubscriptionObserver
import com.salla.push.SallaPushSubscriptionState
import com.salla.push.SallaPushUserChangedState
import com.salla.push.SallaPushUserObserver
import com.salla.push.SallaPushUserState
import com.salla.push.internal.engine.Snapshot
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import com.salla.push.internal.util.AppCallbacks
import com.salla.push.internal.util.MainThread
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App observers and listeners. Callbacks run on the main thread, outside any SDK lock, each one guarded so a failing
 * observer does not stop the others. A subscription observer added once the device id is known gets one replay with
 * previous == current; user and permission observers get no replay.
 */
internal class Observers(
    private val mainThread: MainThread,
    private val logger: Logger,
) {

    val user = CopyOnWriteArrayList<SallaPushUserObserver>()
    val subscription = CopyOnWriteArrayList<SallaPushSubscriptionObserver>()
    val permission = CopyOnWriteArrayList<SallaPushPermissionObserver>()
    val foreground = CopyOnWriteArrayList<SallaPushForegroundLifecycleListener>()

    fun <T> add(list: CopyOnWriteArrayList<T>, item: T) {
        list.addIfAbsent(item)
    }

    fun <T> remove(list: CopyOnWriteArrayList<T>, item: T) {
        list.remove(item)
    }

    private val replayLock = Any()

    /** The state the process started from reached the observers ([restored]); guarded by [replayLock]. */
    private var restoredDelivered = false

    /**
     * Adds a subscription observer and replays the state once, previous == current, on the main thread after this
     * returns. The replay reads [current] when it is delivered, so it is never older than a change delivered before it;
     * it is skipped while the device has no id, and for an observer removed in the meantime. An observer added before
     * the state the process starts from is published gets that one replay instead ([restored]), never two.
     */
    fun addSubscriptionObserver(observer: SallaPushSubscriptionObserver, current: () -> Snapshot) {
        val replayNow = synchronized(replayLock) {
            if (!subscription.addIfAbsent(observer)) return
            restoredDelivered
        }
        if (!replayNow) return
        mainThread.post(Runnable {
            guarded("observer passed to User.pushSubscription.addObserver") {
                val snapshot = current()
                if (snapshot.deviceId != null && subscription.contains(observer)) {
                    val state = subscriptionState(snapshot)
                    observer.onPushSubscriptionChange(SallaPushSubscriptionChangedState(state, state))
                }
            }
        })
    }

    /**
     * The state the process starts from (what an earlier launch left) is no change: user observers hear nothing, and
     * the subscription observers registered now get the replay form once, when the device has an id. Observers added
     * from now on get their replay when they are added.
     */
    fun restored(state: Snapshot) {
        val registered = synchronized(replayLock) {
            restoredDelivered = true
            ArrayList(subscription)
        }
        if (state.deviceId == null || registered.isEmpty()) return
        val replay = subscriptionState(state)
        mainThread.post(Runnable {
            for (observer in registered) {
                if (!subscription.contains(observer)) continue
                guarded("observer passed to User.pushSubscription.addObserver") {
                    observer.onPushSubscriptionChange(SallaPushSubscriptionChangedState(replay, replay))
                }
            }
        })
    }

    fun snapshotChanged(previous: Snapshot, current: Snapshot) {
        val subscriptionChanged = previous.deviceId != current.deviceId || previous.token != current.token || previous.optedIn != current.optedIn
        val userChanged = previous.externalId != current.externalId || previous.isLoggedIn != current.isLoggedIn
        if (!subscriptionChanged && !userChanged) return
        mainThread.post(Runnable {
            if (subscriptionChanged) {
                val change = SallaPushSubscriptionChangedState(subscriptionState(previous), subscriptionState(current))
                for (observer in subscription) guarded("observer passed to User.pushSubscription.addObserver") { observer.onPushSubscriptionChange(change) }
            }
            if (userChanged) {
                val change = SallaPushUserChangedState(
                    SallaPushUserState(previous.externalId, previous.isLoggedIn),
                    SallaPushUserState(current.externalId, current.isLoggedIn),
                )
                for (observer in user) guarded("observer passed to User.addObserver") { observer.onUserStateChange(change) }
            }
        })
    }

    fun permissionChanged(granted: Boolean) {
        mainThread.post(Runnable {
            for (observer in permission) guarded("observer passed to Notifications.addPermissionObserver") { observer.onNotificationPermissionChange(granted) }
        })
    }

    fun loginFailed(failure: SallaPushLoginFailure) {
        mainThread.post(Runnable {
            for (observer in user) guarded("observer passed to User.addObserver") { observer.onLoginFailure(failure) }
        })
    }

    private fun subscriptionState(snapshot: Snapshot) = SallaPushSubscriptionState(snapshot.deviceId, snapshot.token, snapshot.optedIn)

    /**
     * Runs one app callback; a throw is reported as ERROR api.callback_threw with where it came from, and the other
     * observers still run.
     */
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.log(LogCode.API_CALLBACK_THREW) { "The $what threw ${AppCallbacks.failure(t)}; the other observers still ran." }
        }
    }
}
