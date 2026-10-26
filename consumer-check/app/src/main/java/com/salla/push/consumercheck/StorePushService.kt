package com.salla.push.consumercheck

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.SallaPush

// The app's own FCM service, which AndroidManifest.xml declares at priority 1, as README.md advises when a library
// declares a messaging service too. It puts the SallaPush line first.
class StorePushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (SallaPush.Notifications.handleFirebaseMessage(applicationContext, message)) return
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    override fun onNewToken(token: String) {
        SallaPush.Notifications.onNewToken(applicationContext, token)
        // Give the token to your other SDKs here.
    }
}
