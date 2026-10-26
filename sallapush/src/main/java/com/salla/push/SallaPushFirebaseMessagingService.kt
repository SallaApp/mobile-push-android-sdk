package com.salla.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.internal.util.entry
import com.salla.push.internal.util.isNull

/**
 * The fallback FirebaseMessagingService, declared at priority -450. Android gives FCM messages and tokens to one
 * service, the one of highest priority in the merged manifest, so this one receives them only when no service above it
 * exists: neither the app's own (default priority 0) nor another library's. Open for subclassing: a subclass calls
 * super first, and one that also serves other SDKs skips SallaPush's own messages with
 * [SallaPushNotifications.isSallaPushMessage] (super returns nothing to tell). An app whose FCM goes to another service
 * forwards from it to [SallaPushNotifications.handleFirebaseMessage] and [SallaPushNotifications.onNewToken].
 */
public open class SallaPushFirebaseMessagingService : FirebaseMessagingService() {

    /** Hands the message to SallaPush; messages of other SDKs are ignored. */
    override fun onMessageReceived(message: RemoteMessage): Unit = entry("SallaPushFirebaseMessagingService.onMessageReceived") {
        if (isNull(message)) return@entry
        SallaPush.Notifications.handleFirebaseMessage(applicationContext, message)
    }

    /** Hands a new FCM token to SallaPush. */
    override fun onNewToken(token: String): Unit = entry("SallaPushFirebaseMessagingService.onNewToken") {
        if (isNull(token)) return@entry
        SallaPush.Notifications.onNewToken(applicationContext, token)
    }
}
