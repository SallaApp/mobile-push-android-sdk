package com.salla.push.internal

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.SallaPushClickListener
import com.salla.push.SallaPushDebug
import com.salla.push.SallaPushForegroundLifecycleListener
import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLogListener
import com.salla.push.SallaPushMigration
import com.salla.push.SallaPushNotification
import com.salla.push.SallaPushNotifications
import com.salla.push.SallaPushPermissionCallback
import com.salla.push.SallaPushPermissionObserver
import com.salla.push.SallaPushSubscription
import com.salla.push.SallaPushSubscriptionObserver
import com.salla.push.SallaPushUser
import com.salla.push.SallaPushUserObserver
import com.salla.push.SallaPushWillDisplayEvent
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.util.EntryGuard
import com.salla.push.internal.util.Once
import com.salla.push.internal.util.entry
import com.salla.push.internal.util.entryValue
import com.salla.push.internal.util.isNull
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Support for the public notification model, kept out of its public surface. */
internal object FacadeSupport {

    /**
     * Shows a notification a foreground listener prevented: once, within 10 minutes of the will-display event. It
     * exists while the listeners run, so a listener may call display() right after preventDefault() (as on iOS).
     * [decide] fixes whether the prevention took effect; a preventDefault() after that changes nothing.
     */
    class DisplayHandle(
        val createdAtMillis: Long,
        private val event: SallaPushWillDisplayEvent,
        private val show: () -> Unit,
    ) {
        private val lock = Any()

        /** Null while the listeners may still prevent the display; then whether they did in time. */
        private var prevented: Boolean? = null
        private var shown = false

        /** Once the listeners ran or their time ran out: true when the notification is not shown by default. */
        fun decide(): Boolean = synchronized(lock) {
            val decided = event.isPrevented
            prevented = decided
            decided
        }

        /** Shows the notification and returns null, or returns why it is not shown. */
        fun claim(): String? {
            synchronized(lock) {
                val decided = prevented
                if (decided == false) return if (event.isPrevented) PREVENTED_TOO_LATE else NOT_PREVENTED
                // While the listeners run, display() after preventDefault() is final: preventDefault() is never undone.
                if (decided == null && !event.isPrevented) return NOT_PREVENTED
                if (shown) return ALREADY_SHOWN
                shown = true
            }
            show()
            return null
        }
    }

    private const val DISPLAY_WINDOW_MILLIS = 600_000L
    private const val NOT_PREVENTED = "it was not prevented: call event.preventDefault() first"
    private const val PREVENTED_TOO_LATE =
        "it was prevented too late: call event.preventDefault() before the listener returns, within 500 ms"
    private const val ALREADY_SHOWN = "display() was already called for it"

    /**
     * Shows [notification] if a foreground listener prevented it in time; otherwise WARN notif.display_failed says why
     * not (as on iOS).
     */
    fun display(notification: SallaPushNotification) {
        val core = Sdk.core
        val now = core.env.clock.nowMillis()
        val handle = notification.displayHandle?.takeIf { now - it.createdAtMillis <= DISPLAY_WINDOW_MILLIS }
        val reason = if (handle == null) {
            "display() works only within 10 minutes of the foreground listener event, for a notification " +
                "SallaPush presented in the foreground"
        } else {
            handle.claim() ?: return
        }
        core.logger.log(LogCode.NOTIF_DISPLAY_FAILED) { "Could not show the notification ($reason)." }
    }

    fun dataString(notification: SallaPushNotification, key: String): String? {
        if (isNull(key)) {
            EntryGuard.nullArgument("SallaPushNotification.getString", "key")
            return null
        }
        return when (val value = notification.additionalData[key]) {
            is String -> value
            is Boolean -> if (value) "true" else "false"
            is Number -> numberText(value)
            else -> null
        }
    }

    fun dataLong(notification: SallaPushNotification, key: String): Long? {
        if (isNull(key)) {
            EntryGuard.nullArgument("SallaPushNotification.getLong", "key")
            return null
        }
        return when (val value = notification.additionalData[key]) {
            is Int -> value.toLong()
            is Long -> value
            is Number -> wholeNumber(value.toDouble())
            is String -> {
                val text = value.trim()
                text.toLongOrNull() ?: text.toDoubleOrNull()?.let { wholeNumber(it) }
            }
            else -> null
        }
    }

    fun dataBoolean(notification: SallaPushNotification, key: String): Boolean? {
        if (isNull(key)) {
            EntryGuard.nullArgument("SallaPushNotification.getBoolean", "key")
            return null
        }
        return when (val value = notification.additionalData[key]) {
            is Boolean -> value
            is Number -> when (value.toDouble()) {
                1.0 -> true
                0.0 -> false
                else -> null
            }
            is String -> when (value.trim()) {
                "true", "TRUE", "True", "1" -> true
                "false", "FALSE", "False", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun wholeNumber(value: Double): Long? {
        if (value.isNaN() || value.isInfinite() || value != Math.floor(value)) return null
        if (value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()) return null
        return value.toLong()
    }

    private fun numberText(value: Number): String = when (value) {
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is BigInteger, is Int, is Long, is Short, is Byte -> value.toString()
        else -> {
            val double = value.toDouble()
            wholeNumber(double)?.toString() ?: double.toString()
        }
    }
}

internal object UserFacade : SallaPushUser {

    override val externalId: String?
        get() = entryValue("User.externalId", null) { Sdk.core.view().externalId }

    override val isLoggedIn: Boolean
        get() = entryValue("User.isLoggedIn", false) { Sdk.core.view().isLoggedIn }

    override val pushSubscription: SallaPushSubscription
        get() = SubscriptionFacade

    override fun addTag(key: String, value: String): Unit = entry("User.addTag") {
        if (isNull(key)) return@entry EntryGuard.nullArgument("User.addTag", "key")
        if (isNull(value)) return@entry EntryGuard.nullArgument("User.addTag", "value")
        Sdk.core.addTags(mapOf(key to value), "addTag")
    }

    override fun addTags(tags: Map<String, String>): Unit = entry("User.addTags") {
        if (isNull(tags)) return@entry EntryGuard.nullArgument("User.addTags", "tags")
        Sdk.core.addTags(tags, "addTags")
    }

    override fun removeTag(key: String): Unit = entry("User.removeTag") {
        if (isNull(key)) return@entry EntryGuard.nullArgument("User.removeTag", "key")
        Sdk.core.removeTags(listOf(key), "removeTag")
    }

    override fun removeTags(keys: Collection<String>): Unit = entry("User.removeTags") {
        if (isNull(keys)) return@entry EntryGuard.nullArgument("User.removeTags", "keys")
        Sdk.core.removeTags(keys, "removeTags")
    }

    override fun getTags(): Map<String, String> = entryValue("User.getTags", emptyMap()) {
        java.util.Collections.unmodifiableMap(LinkedHashMap(Sdk.core.view().tags))
    }

    override fun setLanguage(language: String?): Unit = entry("User.setLanguage") {
        Sdk.core.setLanguage(language)
    }

    override fun addObserver(observer: SallaPushUserObserver): Unit = entry("User.addObserver") {
        if (isNull(observer)) return@entry EntryGuard.nullArgument("User.addObserver", "observer")
        Sdk.core.observers.add(Sdk.core.observers.user, observer)
    }

    override fun removeObserver(observer: SallaPushUserObserver): Unit = entry("User.removeObserver") {
        if (isNull(observer)) return@entry EntryGuard.nullArgument("User.removeObserver", "observer")
        Sdk.core.observers.remove(Sdk.core.observers.user, observer)
    }
}

internal object SubscriptionFacade : SallaPushSubscription {

    override val id: String?
        get() = entryValue("User.pushSubscription.id", null) { Sdk.core.view().deviceId }

    override val token: String?
        get() = entryValue("User.pushSubscription.token", null) { Sdk.core.view().token }

    override val optedIn: Boolean
        get() = entryValue("User.pushSubscription.optedIn", false) { Sdk.core.view().optedIn }

    override fun optIn(): Unit = entry("User.pushSubscription.optIn") {
        Sdk.core.optIn()
    }

    override fun optOut(): Unit = entry("User.pushSubscription.optOut") {
        Sdk.core.setOptedOut(true)
    }

    override fun addObserver(observer: SallaPushSubscriptionObserver): Unit = entry("User.pushSubscription.addObserver") {
        if (isNull(observer)) return@entry EntryGuard.nullArgument("User.pushSubscription.addObserver", "observer")
        val core = Sdk.core
        core.observers.addSubscriptionObserver(observer) { core.snapshot() }
    }

    override fun removeObserver(observer: SallaPushSubscriptionObserver): Unit = entry("User.pushSubscription.removeObserver") {
        if (isNull(observer)) return@entry EntryGuard.nullArgument("User.pushSubscription.removeObserver", "observer")
        Sdk.core.observers.remove(Sdk.core.observers.subscription, observer)
    }
}

internal object NotificationsFacade : SallaPushNotifications {

    override val permission: Boolean
        get() = entryValue("Notifications.permission", false) { Sdk.core.permissionNow() }

    override fun canRequestPermission(): Boolean = entryValue("Notifications.canRequestPermission", false) {
        Sdk.core.canRequestPermission()
    }

    override fun requestPermission(fallbackToSettings: Boolean, callback: SallaPushPermissionCallback?): Unit =
        entry("Notifications.requestPermission") {
            Sdk.core.requestPermission(fallbackToSettings) { granted -> callback?.onResult(granted) }
        }

    override suspend fun requestPermissionSuspend(fallbackToSettings: Boolean): Boolean {
        return try {
            suspendCoroutine { continuation ->
                val once = Once()
                val started = try {
                    Sdk.core.requestPermission(fallbackToSettings) { granted -> if (once.claim()) continuation.resume(granted) }
                    true
                } catch (t: Throwable) {
                    false
                }
                if (!started && once.claim()) continuation.resume(false)
            }
        } catch (t: Throwable) {
            false
        }
    }

    override fun refreshPermission(): Unit = entry("Notifications.refreshPermission") {
        Sdk.core.refreshPermission()
    }

    override fun addPermissionObserver(observer: SallaPushPermissionObserver): Unit = entry("Notifications.addPermissionObserver") {
        if (isNull(observer)) return@entry EntryGuard.nullArgument("Notifications.addPermissionObserver", "observer")
        Sdk.core.observers.add(Sdk.core.observers.permission, observer)
    }

    override fun removePermissionObserver(observer: SallaPushPermissionObserver): Unit = entry("Notifications.removePermissionObserver") {
        if (isNull(observer)) return@entry EntryGuard.nullArgument("Notifications.removePermissionObserver", "observer")
        Sdk.core.observers.remove(Sdk.core.observers.permission, observer)
    }

    override fun addClickListener(listener: SallaPushClickListener): Unit = entry("Notifications.addClickListener") {
        if (isNull(listener)) return@entry EntryGuard.nullArgument("Notifications.addClickListener", "listener")
        Sdk.core.clicks.addListener(listener)
    }

    override fun removeClickListener(listener: SallaPushClickListener): Unit = entry("Notifications.removeClickListener") {
        if (isNull(listener)) return@entry EntryGuard.nullArgument("Notifications.removeClickListener", "listener")
        Sdk.core.clicks.removeListener(listener)
    }

    override fun addForegroundLifecycleListener(listener: SallaPushForegroundLifecycleListener): Unit =
        entry("Notifications.addForegroundLifecycleListener") {
            if (isNull(listener)) return@entry EntryGuard.nullArgument("Notifications.addForegroundLifecycleListener", "listener")
            Sdk.core.observers.add(Sdk.core.observers.foreground, listener)
        }

    override fun removeForegroundLifecycleListener(listener: SallaPushForegroundLifecycleListener): Unit =
        entry("Notifications.removeForegroundLifecycleListener") {
            if (isNull(listener)) return@entry EntryGuard.nullArgument("Notifications.removeForegroundLifecycleListener", "listener")
            Sdk.core.observers.remove(Sdk.core.observers.foreground, listener)
        }

    override fun clearAllNotifications(): Unit = entry("Notifications.clearAllNotifications") {
        Sdk.core.clearAllNotifications()
    }

    override fun removeNotification(androidNotificationId: Int): Unit = entry("Notifications.removeNotification") {
        Sdk.core.removeNotification(androidNotificationId)
    }

    override fun isSallaPushMessage(message: RemoteMessage): Boolean = entryValue("Notifications.isSallaPushMessage", false) {
        if (isNull(message)) {
            EntryGuard.nullArgument("Notifications.isSallaPushMessage", "message")
            false
        } else {
            Sdk.core.isSallaPushMessage(message.data)
        }
    }

    override fun isSallaPushMessage(data: Map<String, String>): Boolean = entryValue("Notifications.isSallaPushMessage", false) {
        if (isNull(data)) {
            EntryGuard.nullArgument("Notifications.isSallaPushMessage", "data")
            false
        } else {
            Sdk.core.isSallaPushMessage(data)
        }
    }

    override fun handleFirebaseMessage(context: Context, message: RemoteMessage): Boolean = entryValue("Notifications.handleFirebaseMessage", false) {
        when {
            isNull(context) -> {
                EntryGuard.nullArgument("Notifications.handleFirebaseMessage", "context")
                false
            }
            isNull(message) -> {
                EntryGuard.nullArgument("Notifications.handleFirebaseMessage", "message")
                false
            }
            else -> Sdk.core.handleFirebaseMessage(context, message.data)
        }
    }

    override fun onNewToken(context: Context, token: String): Unit = entry("Notifications.onNewToken") {
        if (isNull(context)) return@entry EntryGuard.nullArgument("Notifications.onNewToken", "context")
        if (isNull(token)) return@entry EntryGuard.nullArgument("Notifications.onNewToken", "token")
        Sdk.core.onNewToken(context, token)
    }

    override fun trackOpened(additionalData: JSONObject?): Boolean = entryValue("Notifications.trackOpened", false) {
        Sdk.core.trackOpened(additionalData)
    }
}

internal object DebugFacade : SallaPushDebug {

    override var logLevel: SallaPushLogLevel
        get() = entryValue("Debug.logLevel", SallaPushLogLevel.WARN) { Sdk.core.logger.consoleLevel }
        set(value) = entry("Debug.logLevel") {
            if (isNull(value)) return@entry EntryGuard.nullArgument("Debug.setLogLevel", "level")
            Sdk.core.logger.setRuntimeLevel(value)
        }

    override fun addLogListener(listener: SallaPushLogListener): Unit = entry("Debug.addLogListener") {
        if (isNull(listener)) return@entry EntryGuard.nullArgument("Debug.addLogListener", "listener")
        Sdk.core.logger.addListener(listener, SallaPushLogLevel.WARN)
    }

    override fun addLogListener(listener: SallaPushLogListener, level: SallaPushLogLevel): Unit = entry("Debug.addLogListener") {
        if (isNull(listener)) return@entry EntryGuard.nullArgument("Debug.addLogListener", "listener")
        if (isNull(level)) return@entry EntryGuard.nullArgument("Debug.addLogListener", "level")
        Sdk.core.logger.addListener(listener, level)
    }

    override fun removeLogListener(listener: SallaPushLogListener): Unit = entry("Debug.removeLogListener") {
        if (isNull(listener)) return@entry EntryGuard.nullArgument("Debug.removeLogListener", "listener")
        Sdk.core.logger.removeListener(listener)
    }
}

internal object MigrationFacade : SallaPushMigration {

    override fun setDisplayCompatPayloads(display: Boolean): Unit = entry("Migration.setDisplayCompatPayloads") {
        Sdk.core.displayCompatPayloads = display
    }
}
