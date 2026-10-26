package com.salla.push.internal.click

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.salla.push.internal.Sdk
import com.salla.push.internal.display.NotificationDisplayer

/** Receives the delete intent of a SallaPush notification: records `dismissed`, never a click. Not exported. */
internal class SallaPushNotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            val appContext = context?.applicationContext ?: return
            Sdk.core.onNotificationDismissed(appContext, intent?.getStringExtra(NotificationDisplayer.EXTRA_NOTIFICATION_ID))
        } catch (t: Throwable) {
            // A dismissal that cannot be recorded is simply not counted.
        }
    }
}
