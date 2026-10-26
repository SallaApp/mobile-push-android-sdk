package com.salla.push.internal.click

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.salla.push.internal.Sdk
import com.salla.push.internal.display.NotificationDisplayer
import java.util.UUID

/**
 * The target of a SallaPush notification tap: a non-exported, translucent, no-history activity (no
 * BroadcastReceiver or Service trampoline, which Android 12+ blocks). It records `opened`, dispatches the click to the
 * app's listeners, starts the app's launch intent (or an https launch URL when configured, falling back to the app when
 * nothing can open the URL), and finishes.
 */
internal class SallaPushNotificationOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (savedInstanceState == null) handle(intent)
        } catch (t: Throwable) {
            // A malformed intent cannot be opened; finishing is all that is left.
        } finally {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            handle(intent)
        } catch (t: Throwable) {
            // A malformed intent cannot be opened; finishing is all that is left.
        } finally {
            finish()
        }
    }

    private fun handle(intent: Intent?) {
        val payload = NotificationDisplayer.payloadMap(intent?.getBundleExtra(NotificationDisplayer.EXTRA_PAYLOAD))
        val androidId = intent?.getIntExtra(NotificationDisplayer.EXTRA_ANDROID_ID, 0) ?: 0
        val nonce = intent?.getStringExtra(NotificationDisplayer.EXTRA_NONCE) ?: UUID.randomUUID().toString()
        for (launch in Sdk.core.onNotificationOpened(applicationContext, payload, androidId, nonce)) {
            try {
                startActivity(launch)
                return
            } catch (e: RuntimeException) {
                // Nothing can open it (no browser for the URL: ActivityNotFoundException), or it is not allowed: the
                // next intent, the app itself, opens instead, so a tap never does nothing.
            }
        }
    }
}
