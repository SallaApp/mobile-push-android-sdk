package com.salla.push.consumercheck.readme;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.salla.push.SallaPush;

// snippet:java-messaging-service
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        // First line: SallaPush shows its own messages and returns true; everything else is false.
        if (SallaPush.getNotifications().handleFirebaseMessage(getApplicationContext(), message)) {
            return;
        }
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    @Override
    public void onNewToken(String token) {
        SallaPush.getNotifications().onNewToken(getApplicationContext(), token);
        // Give the token to your other SDKs here.
    }
}
// end-snippet
