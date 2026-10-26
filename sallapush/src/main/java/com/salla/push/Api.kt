package com.salla.push

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.internal.FacadeSupport
import com.salla.push.internal.util.entry
import com.salla.push.internal.util.entryValue
import org.json.JSONObject

/** The current user of this device: the customer the app logged in, tags, language and the push subscription. */
public interface SallaPushUser {
    /**
     * The external id of the last [SallaPush.login], from the moment the call returns; null for guests, after
     * [SallaPush.logout] and while consent is withdrawn.
     */
    public val externalId: String?

    /**
     * True after [SallaPush.login] until the next [SallaPush.logout], from the moment the call returns. A login ignored
     * for an invalid external id or token leaves it unchanged.
     */
    public val isLoggedIn: Boolean

    /** This device's push subscription. */
    public val pushSubscription: SallaPushSubscription

    /** Adds or replaces one tag. Keys are lowercased; an empty value removes the tag. An empty key is ignored (logged). */
    public fun addTag(key: String, value: String)

    /**
     * Adds or replaces several tags. Keys are lowercased; an empty value removes that tag. Entries with an empty key are
     * ignored (logged); the others are applied.
     */
    public fun addTags(tags: Map<String, String>)

    /** Removes one tag. An empty key is ignored (logged). */
    public fun removeTag(key: String)

    /** Removes several tags. Empty keys are ignored (logged); the others are removed. */
    public fun removeTags(keys: Collection<String>)

    /**
     * Returns this device's tags as known locally, including changes that are not sent yet: a tag call is reflected
     * from the moment it returns, normalised as it will be sent.
     */
    public fun getTags(): Map<String, String>

    /**
     * Sets the app language reported to SallaPush, as given (for example "ar" or "en-US"; trimmed, at most 16
     * characters). Null or blank uses the device language's language part ("ar-SA" → "ar").
     */
    public fun setLanguage(language: String?)

    /**
     * Adds an observer of login state changes, called on the main thread. SallaPush keeps a strong reference to it until
     * [removeObserver]: remove an Activity or Fragment observer when it is destroyed, or it is leaked.
     */
    public fun addObserver(observer: SallaPushUserObserver)

    /** Removes an observer added with [addObserver]. */
    public fun removeObserver(observer: SallaPushUserObserver)
}

/** This device's push subscription: its SallaPush id, its FCM token and whether it receives notifications. */
public interface SallaPushSubscription {
    /** The SallaPush device id; null until the device is registered. */
    public val id: String?

    /** The FCM token SallaPush uses; null until one is known. */
    public val token: String?

    /**
     * True when notifications are allowed, the user did not opt out, and a token exists. [optOut] and [optIn] are
     * reflected from the moment they return.
     */
    public val optedIn: Boolean

    /**
     * Clears an earlier [optOut], and prompts for the notification permission when it can still be requested
     * (Android 13+). Called before [SallaPush.initWithContext] or while the app is not in the foreground, it shows the
     * prompt once at the next foreground, unless the permission was granted by then.
     */
    public fun optIn()

    /** Stops SallaPush notifications to this device until [optIn]. */
    public fun optOut()

    /**
     * Adds an observer of subscription changes, called on the main thread. A new observer gets the current state once
     * the device is registered. SallaPush keeps a strong reference to it until [removeObserver]: remove an Activity or
     * Fragment observer when it is destroyed, or it is leaked.
     */
    public fun addObserver(observer: SallaPushSubscriptionObserver)

    /** Removes an observer added with [addObserver]. */
    public fun removeObserver(observer: SallaPushSubscriptionObserver)
}

/** Notification permission, FCM forwarding, listeners and clearing. */
public interface SallaPushNotifications {
    /** True when this app may show notifications. */
    public val permission: Boolean

    /**
     * True when the system permission prompt can still be shown (Android 13+, not granted, not permanently denied).
     * Android cannot tell a permanent denial from "never asked": SallaPush learns it when a prompt reports it and
     * keeps it until the process ends, so after a relaunch this is true again until a prompt reports the denial anew
     * (the system then answers at once, without showing anything).
     */
    public fun canRequestPermission(): Boolean

    /**
     * Shows the system notification permission prompt (Android 13+). [callback] runs once, on the main thread, with
     * whether notifications are allowed; inside it, [permission] and the subscription's optedIn already reflect the
     * answer. When notifications are already allowed it shows nothing and answers true. While the app is not in the
     * foreground it shows nothing and answers the current state: true when already allowed, else false (with a WARN
     * when a prompt could otherwise be shown). Right after [SallaPush.initWithContext] it first waits until the
     * foreground state is known. Below Android 13 it shows nothing and answers the current state. With
     * [fallbackToSettings], a denial that can no longer be prompted for (a second denial, or one the system gave without
     * showing the prompt) opens the app's notification settings; a first denial, a dismissed prompt or an interrupted
     * request never does.
     */
    public fun requestPermission(fallbackToSettings: Boolean, callback: SallaPushPermissionCallback?)

    /**
     * Suspending form of [requestPermission]; returns whether notifications are allowed. It is not cancellable: it
     * completes when the request ends (the user answered the system dialog, or the app was not in the foreground).
     */
    public suspend fun requestPermissionSuspend(fallbackToSettings: Boolean): Boolean

    /** Re-reads the notification permission now, for apps that prompt in their own way. */
    public fun refreshPermission()

    /**
     * Adds an observer of notification permission changes, called on the main thread. SallaPush keeps a strong
     * reference to it until [removePermissionObserver]: remove an Activity or Fragment observer when it is destroyed.
     */
    public fun addPermissionObserver(observer: SallaPushPermissionObserver)

    /** Removes an observer added with [addPermissionObserver]. */
    public fun removePermissionObserver(observer: SallaPushPermissionObserver)

    /**
     * Adds a listener for taps on SallaPush notifications. Taps made before any listener exists are delivered to the
     * first one added. SallaPush keeps a strong reference to it until [removeClickListener]: register it in
     * Application.onCreate, or remove an Activity listener when the Activity is destroyed.
     */
    public fun addClickListener(listener: SallaPushClickListener)

    /** Removes a listener added with [addClickListener]. */
    public fun removeClickListener(listener: SallaPushClickListener)

    /**
     * Adds a listener that runs on the main thread before a SallaPush notification is shown while the app is in the
     * foreground. FCM delivers messages on a background thread, which waits at most 500 ms for the listeners: a listener
     * that has not returned by then (a busy main thread) cannot stop the notification, which is shown, and a later
     * [SallaPushWillDisplayEvent.preventDefault] or [SallaPushNotification.display] has no effect. SallaPush keeps a
     * strong reference to it until [removeForegroundLifecycleListener]: remove an Activity listener when the Activity is
     * destroyed.
     */
    public fun addForegroundLifecycleListener(listener: SallaPushForegroundLifecycleListener)

    /** Removes a listener added with [addForegroundLifecycleListener]. */
    public fun removeForegroundLifecycleListener(listener: SallaPushForegroundLifecycleListener)

    /** Removes every SallaPush notification from the notification shade; other notifications are kept. */
    public fun clearAllNotifications()

    /** Removes one SallaPush notification, by [SallaPushNotification.androidNotificationId]. */
    public fun removeNotification(androidNotificationId: Int)

    /** True when [message] was sent by SallaPush. */
    public fun isSallaPushMessage(message: RemoteMessage): Boolean

    /** True when an FCM data map was sent by SallaPush. */
    public fun isSallaPushMessage(data: Map<String, String>): Boolean

    /** Call first in your FirebaseMessagingService.onMessageReceived. Returns true when the message was SallaPush's and is handled. */
    public fun handleFirebaseMessage(context: Context, message: RemoteMessage): Boolean

    /** Call from your FirebaseMessagingService.onNewToken. */
    public fun onNewToken(context: Context, token: String)

    /**
     * Records the open of a SallaPush notification that another push SDK in the app showed: pass the custom data that
     * SDK gives for the notification. Returns true when it was SallaPush's.
     */
    public fun trackOpened(additionalData: JSONObject?): Boolean
}

/** Logging controls. */
public interface SallaPushDebug {
    /** The Logcat level, changeable at any time. [SallaPushLogLevel.OFF] silences Logcat only. */
    public var logLevel: SallaPushLogLevel

    /**
     * Adds a listener for log events at [SallaPushLogLevel.WARN] and above. Listeners run on a private thread.
     * SallaPush keeps a strong reference to it until [removeLogListener]. The first listener added in the process also
     * receives, before any later event, the warnings and errors logged before any listener existed (at most 100), so
     * a problem found during [SallaPush.initWithContext] reaches a listener added right after it.
     */
    public fun addLogListener(listener: SallaPushLogListener)

    /**
     * Adds a listener for log events at [level] and above. Listeners run on a private thread. SallaPush keeps a strong
     * reference to it until [removeLogListener]. The first listener added in the process also receives, before any
     * later event, the warnings and errors logged before any listener existed that [level] admits (at most 100).
     */
    public fun addLogListener(listener: SallaPushLogListener, level: SallaPushLogLevel)

    /** Removes a listener added with [addLogListener]. */
    public fun removeLogListener(listener: SallaPushLogListener)
}

/** Helpers for apps that also run another push SDK, which can show SallaPush's messages in the compatible format. */
public interface SallaPushMigration {
    /**
     * Whether SallaPush shows the messages in the compatible format that reach it (default true). Turn it off when the
     * app's other push SDK shows them.
     */
    public fun setDisplayCompatPayloads(display: Boolean)
}

/** Log levels, from silent to most detailed. */
public enum class SallaPushLogLevel {
    /** Nothing is logged. */
    OFF,

    /** Failures that need action. */
    ERROR,

    /** Problems SallaPush works around. */
    WARN,

    /** Main lifecycle steps. */
    INFO,

    /** Details useful while integrating. */
    DEBUG,

    /** Everything. */
    VERBOSE,
}

/** The payload format a notification arrived in. */
public enum class SallaPushPayloadSource {
    /** SallaPush's own format. */
    NATIVE,

    /** The compatible format, which another push SDK in the app can also show. */
    COMPAT,
}

/** Why a login did not link the customer. */
public enum class SallaPushLoginFailure {
    /**
     * The SallaPush server rejected the identity token: it expired, or its subject is not the external id. Call login
     * again with a fresh token.
     */
    TOKEN_REJECTED,

    /** The SallaPush server refused the login request as invalid; it is not sent again. */
    INVALID_REQUEST,
}

/** A SallaPush notification as parsed from its FCM data. */
public class SallaPushNotification internal constructor(
    /** The SallaPush message id (msg_…); null when the message cannot be attributed. */
    public val notificationId: String?,
    /** The id passed to NotificationManager.notify; use it with [SallaPushNotifications.removeNotification]. */
    public val androidNotificationId: Int,
    /** The title. */
    public val title: String?,
    /** The subtitle, shown as the notification's sub-text. */
    public val subtitle: String?,
    /** The body text. */
    public val body: String?,
    /** The https image URL. */
    public val imageUrl: String?,
    /** The launch URL as sent; SallaPush never opens it unless configured to. */
    public val launchUrl: String?,
    /** Custom data: JSON types kept for messages in the compatible format, strings for native ones. */
    public val additionalData: Map<String, Any?>,
    /** The collapse key; notifications with the same key replace each other. */
    public val collapseId: String?,
    /** The requested notification channel. */
    public val channelId: String?,
    /** The requested sound. */
    public val sound: String?,
    /** The payload format. */
    public val source: SallaPushPayloadSource,
    /** The FCM data map as received. */
    public val rawPayload: Map<String, String>,
) {
    @Volatile
    internal var displayHandle: FacadeSupport.DisplayHandle? = null

    /** Returns the [additionalData] value for [key] as a string (numbers and booleans are converted). */
    public fun getString(key: String): String? = entryValue("SallaPushNotification.getString", null) {
        FacadeSupport.dataString(this, key)
    }

    /** Returns the [additionalData] value for [key] as a number; numeric strings are accepted. */
    public fun getLong(key: String): Long? = entryValue("SallaPushNotification.getLong", null) {
        FacadeSupport.dataLong(this, key)
    }

    /** Returns the [additionalData] value for [key] as a boolean; accepts true/false, 1/0 and their string forms. */
    public fun getBoolean(key: String): Boolean? = entryValue("SallaPushNotification.getBoolean", null) {
        FacadeSupport.dataBoolean(this, key)
    }

    /**
     * Shows a notification that a foreground listener prevented, once, within 10 minutes; also from inside the
     * listener, right after [SallaPushWillDisplayEvent.preventDefault].
     */
    public fun display(): Unit = entry("SallaPushNotification.display") {
        FacadeSupport.display(this)
    }
}

/** What a tap on a notification asked for. */
public class SallaPushClickResult internal constructor(
    /** The tapped action button; null for a tap on the notification itself. */
    public val actionId: String?,
    /** The notification's launch URL. */
    public val url: String?,
)

/** A tap on a SallaPush notification. */
public class SallaPushClickEvent internal constructor(
    /** The tapped notification. */
    public val notification: SallaPushNotification,
    /** What the tap asked for. */
    public val result: SallaPushClickResult,
)

/** A SallaPush notification about to be shown while the app is in the foreground. */
public class SallaPushWillDisplayEvent internal constructor(
    /** The notification about to be shown. */
    public val notification: SallaPushNotification,
) {
    @Volatile
    private var prevented: Boolean = false

    /** True once [preventDefault] was called. */
    public val isPrevented: Boolean
        get() = prevented

    /**
     * Keeps the notification from being shown; it can be shown later with [SallaPushNotification.display]. It takes
     * effect only while the listener runs within the 500 ms SallaPush waits for it (see [SallaPushForegroundLifecycleListener]).
     */
    public fun preventDefault() {
        prevented = true
    }
}

/** A snapshot of the push subscription. */
public class SallaPushSubscriptionState internal constructor(
    /** The SallaPush device id. */
    public val id: String?,
    /** The FCM token. */
    public val token: String?,
    /** Whether the device receives SallaPush notifications. */
    public val optedIn: Boolean,
)

/** A change of the push subscription. */
public class SallaPushSubscriptionChangedState internal constructor(
    /** The state before the change. */
    public val previous: SallaPushSubscriptionState,
    /** The state after the change. */
    public val current: SallaPushSubscriptionState,
)

/** A snapshot of the user's login state. */
public class SallaPushUserState internal constructor(
    /**
     * The external id of the last [SallaPush.login], from the moment the call returns; null for guests, after
     * [SallaPush.logout] and while consent is withdrawn.
     */
    public val externalId: String?,
    /**
     * True after [SallaPush.login] until the next [SallaPush.logout]. A login ignored for an invalid external id or
     * token leaves it unchanged.
     */
    public val isLoggedIn: Boolean,
)

/** A change of the user's login state. */
public class SallaPushUserChangedState internal constructor(
    /** The state before the change. */
    public val previous: SallaPushUserState,
    /** The state after the change. */
    public val current: SallaPushUserState,
)

/** One SallaPush log event. Values are redacted: no secret, token or customer identifier appears. */
public class SallaPushLogEvent internal constructor(
    /** The level. */
    public val level: SallaPushLogLevel,
    /** The area: lifecycle, registration, identity, notifications, events, tags, network, storage or consent. */
    public val category: String,
    /** A stable code, for example "register.ok". */
    public val code: String,
    /** A one-line description. */
    public val message: String,
    /** Redacted details such as http_status, error_code and request_id. */
    public val metadata: Map<String, String>,
    /** When the event happened, in milliseconds since the epoch. */
    public val timestampMillis: Long,
)

/** Observes push subscription changes. */
public fun interface SallaPushSubscriptionObserver {
    /** Called on the main thread when the subscription id, token or opt-in state changes. */
    public fun onPushSubscriptionChange(state: SallaPushSubscriptionChangedState)
}

/** Observes notification permission changes. */
public fun interface SallaPushPermissionObserver {
    /** Called on the main thread when the notification permission changes. */
    public fun onNotificationPermissionChange(permission: Boolean)
}

/** Receives taps on SallaPush notifications. */
public fun interface SallaPushClickListener {
    /** Called on the main thread for each tap. */
    public fun onClick(event: SallaPushClickEvent)
}

/** Runs before a SallaPush notification is shown in the foreground. */
public fun interface SallaPushForegroundLifecycleListener {
    /**
     * Called on the main thread; call [SallaPushWillDisplayEvent.preventDefault] to keep the notification from being
     * shown. When the push arrived off the main thread (FCM's own thread), this must return within 500 ms: otherwise
     * the notification is shown, and a later preventDefault() or [SallaPushNotification.display] has no effect.
     */
    public fun onWillDisplay(event: SallaPushWillDisplayEvent)
}

/** Receives the result of a permission request. */
public fun interface SallaPushPermissionCallback {
    /** Called once on the main thread with whether notifications are allowed. */
    public fun onResult(granted: Boolean)
}

/** Receives SallaPush log events. */
public fun interface SallaPushLogListener {
    /** Called on a private SallaPush thread; must return quickly. */
    public fun onLogEvent(event: SallaPushLogEvent)
}

/** Observes the user's login state. */
public interface SallaPushUserObserver {
    /** Called on the main thread when the login state or the external id changes. */
    public fun onUserStateChange(state: SallaPushUserChangedState)

    /** Called on the main thread when a login did not link the customer. Optional to implement. */
    public fun onLoginFailure(failure: SallaPushLoginFailure) {}
}
