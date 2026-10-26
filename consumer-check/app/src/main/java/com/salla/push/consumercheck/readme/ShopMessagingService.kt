package com.salla.push.consumercheck.readme

import com.google.firebase.messaging.RemoteMessage
import com.salla.push.SallaPush
import com.salla.push.SallaPushFirebaseMessagingService

// snippet:subclass-service
class ShopMessagingService : SallaPushFirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (SallaPush.Notifications.isSallaPushMessage(message)) return
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Give the token to your other SDKs here.
    }
}
// end-snippet
