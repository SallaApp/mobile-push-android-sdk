package com.salla.push.consumercheck.readme

import com.salla.push.SallaPush
import com.salla.push.SallaPushNotification

/**
 * The snippets of README.md and docs/REFERENCE.md about showing and clearing notifications; the app supplies its
 * checkout flow.
 */
abstract class NotificationSnippets {

    abstract val checkout: Checkout

    fun holdDuringCheckout() {
        // snippet:foreground-listener
        SallaPush.Notifications.addForegroundLifecycleListener { event ->
            if (checkout.isInProgress) {
                event.preventDefault()
                checkout.whenFinished { event.notification.display() }
            }
        }
        // end-snippet
    }

    // snippet:permission-suspend
    suspend fun askForNotifications(): Boolean =
        SallaPush.Notifications.requestPermissionSuspend(fallbackToSettings = true)
    // end-snippet

    fun clear(notification: SallaPushNotification) {
        // snippet:clear-notifications
        SallaPush.Notifications.removeNotification(notification.androidNotificationId)
        SallaPush.Notifications.clearAllNotifications()
        // end-snippet
    }
}
