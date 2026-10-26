package com.salla.push.sample

import android.app.Application
import com.salla.push.SallaPush

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // snippet:initialize
        SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002")
        // Added here, the click listener also receives the tap that started the app.
        SallaPush.Notifications.addClickListener { event -> SampleRouter.open(this, event) }
        // end-snippet
        // At each launch while the customer is signed in.
        val customerId = CustomerSession.customerId(this)
        val token = CustomerSession.identityToken(this)
        if (customerId != null && token != null) SallaPush.login(customerId, token)
    }
}
