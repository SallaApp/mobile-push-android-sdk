package com.salla.push.consumercheck.readme

import android.app.Application
import android.content.pm.ApplicationInfo
import com.salla.push.SallaPush
import com.salla.push.SallaPushConfig
import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLoginFailure
import com.salla.push.SallaPushNotification
import com.salla.push.SallaPushUserChangedState
import com.salla.push.SallaPushUserObserver

/**
 * The snippets of README.md and docs/REFERENCE.md that belong in an Application (they run in its onCreate); the app
 * supplies the members.
 */
abstract class ApplicationSnippets : Application() {

    abstract val router: Router
    abstract val telemetry: Telemetry

    fun startWithConfiguration() {
        // snippet:configuration
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val config = SallaPushConfig.Builder()
            .setLogLevel(if (debuggable) SallaPushLogLevel.VERBOSE else SallaPushLogLevel.WARN)
            .build()
        SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002", config)
        // end-snippet
    }

    fun startWithBadgeSettings() {
        // snippet:badges
        val config = SallaPushConfig.Builder()
            .setAutoClearBadge(true) // remove SallaPush's notifications from the shade when the app opens
            .build()
        SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002", config)
        // end-snippet
    }

    fun startWithConsent() {
        // snippet:consent-required
        SallaPush.setConsentRequired(true)
        SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002")
        // end-snippet
    }

    fun observeLoginFailures() {
        // snippet:login-failures
        SallaPush.User.addObserver(object : SallaPushUserObserver {
            override fun onUserStateChange(state: SallaPushUserChangedState) {}

            override fun onLoginFailure(failure: SallaPushLoginFailure) {
                when (failure) {
                    // Salla refused this token for this customer id: log in again once the app has a current token.
                    SallaPushLoginFailure.TOKEN_REJECTED ->
                        telemetry.report("sallapush", "login_token_rejected", "Login refused", emptyMap())
                    // The customer id or the token is malformed: report it.
                    SallaPushLoginFailure.INVALID_REQUEST ->
                        telemetry.report("sallapush", "login_invalid_request", "Login refused", emptyMap())
                }
            }
        })
        // end-snippet
    }

    fun listenForTaps() {
        // snippet:click-listener
        SallaPush.Notifications.addClickListener { event ->
            open(event.notification)
        }
        // end-snippet
    }

    // snippet:route
    private fun open(notification: SallaPushNotification) {
        when (notification.getString("type")) {
            "product" -> notification.getLong("id")?.let { router.showProduct(it) }
            "order" -> notification.getString("id")?.let { router.showOrder(it) }
            else -> notification.launchUrl?.let { router.openUrl(it) }
        }
    }
    // end-snippet
}
