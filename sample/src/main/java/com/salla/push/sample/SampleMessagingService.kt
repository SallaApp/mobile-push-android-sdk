package com.salla.push.sample

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.SallaPush

class SampleMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // First line: SallaPush shows its own messages and returns true; everything else is false.
        if (SallaPush.Notifications.handleFirebaseMessage(applicationContext, message)) return
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    override fun onNewToken(token: String) {
        SallaPush.Notifications.onNewToken(applicationContext, token)
        // Give the token to your other SDKs here.
    }
}
