package com.salla.push.sample

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.salla.push.SallaPush
import com.salla.push.SallaPushForegroundLifecycleListener
import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLogListener
import com.salla.push.SallaPushLoginFailure
import com.salla.push.SallaPushPermissionObserver
import com.salla.push.SallaPushSubscriptionObserver
import com.salla.push.SallaPushUserChangedState
import com.salla.push.SallaPushUserObserver

/**
 * One screen to try SallaPush: sign in and out, ask for the notification permission, opt out and in, set a tag, and
 * follow what SallaPush reports in the event feed (its log lines from INFO up and its notif.* DEBUG lines, foreground
 * notifications, state changes, and the taps SampleApp's click listener records).
 */
class MainActivity : Activity() {

    private val status by lazy { findViewById<TextView>(R.id.status) }
    private val events by lazy { findViewById<TextView>(R.id.events) }
    private val customerId by lazy { findViewById<EditText>(R.id.customer_id) }
    private val identityToken by lazy { findViewById<EditText>(R.id.customer_token) }
    private val optInButton by lazy { findViewById<Button>(R.id.opt_in) }

    private val userObserver = object : SallaPushUserObserver {
        override fun onUserStateChange(state: SallaPushUserChangedState) = showStatus()

        override fun onLoginFailure(failure: SallaPushLoginFailure) =
            EventFeed.add(getString(R.string.event_login_failed, failure.name))
    }
    private val subscriptionObserver = SallaPushSubscriptionObserver { showStatus() }
    private val permissionObserver = SallaPushPermissionObserver { showStatus() }
    private val foregroundListener = SallaPushForegroundLifecycleListener { event ->
        EventFeed.add(getString(R.string.event_foreground, EventFeed.describe(this, event.notification)))
    }

    // Log listeners run on a private SallaPush thread. The feed takes INFO and up, and the notif.* DEBUG lines, which
    // say what became of a notification, such as why it did not show.
    private val logListener = SallaPushLogListener { event ->
        if (event.level <= SallaPushLogLevel.INFO || event.code.startsWith("notif.")) {
            runOnUiThread { EventFeed.add(event.code + ": " + event.message) }
        }
    }
    private val feedObserver = { events.text = EventFeed.text }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepClearOfSystemBars(findViewById(R.id.root))
        findViewById<Button>(R.id.sign_in).setOnClickListener { signIn() }
        findViewById<Button>(R.id.sign_out).setOnClickListener { signOut() }
        findViewById<Button>(R.id.request_permission).setOnClickListener { requestPermission() }
        findViewById<Button>(R.id.add_tag).setOnClickListener { addTag() }
        optInButton.setOnClickListener { toggleOptIn() }

        SallaPush.User.addObserver(userObserver)
        SallaPush.User.pushSubscription.addObserver(subscriptionObserver)
        SallaPush.Notifications.addPermissionObserver(permissionObserver)
        SallaPush.Notifications.addForegroundLifecycleListener(foregroundListener)
        SallaPush.Debug.addLogListener(logListener, SallaPushLogLevel.DEBUG)
        EventFeed.observe(feedObserver)
        feedObserver()
        showStatus()
    }

    override fun onDestroy() {
        // snippet:remove-observers
        SallaPush.User.removeObserver(userObserver)
        SallaPush.User.pushSubscription.removeObserver(subscriptionObserver)
        SallaPush.Notifications.removePermissionObserver(permissionObserver)
        SallaPush.Notifications.removeForegroundLifecycleListener(foregroundListener)
        SallaPush.Debug.removeLogListener(logListener)
        // end-snippet
        EventFeed.stopObserving(feedObserver)
        super.onDestroy()
    }

    private fun signIn() {
        val id = customerId.text.toString().trim()
        val token = identityToken.text.toString().trim()
        if (id.isEmpty() || token.isEmpty()) {
            EventFeed.add(getString(R.string.event_token_missing))
            return
        }
        CustomerSession.signIn(this, id, token)
        identityToken.text.clear()
        showStatus()
    }

    private fun signOut() {
        CustomerSession.signOut(this)
        showStatus()
    }

    private fun requestPermission() {
        // snippet:request-permission
        SallaPush.Notifications.requestPermission(fallbackToSettings = true) { granted ->
            showPermissionAnswer(granted)
        }
        // end-snippet
    }

    private fun toggleOptIn() {
        // snippet:opt-in-out
        val subscription = SallaPush.User.pushSubscription
        if (subscription.optedIn) subscription.optOut() else subscription.optIn()
        // end-snippet
        showStatus()
    }

    private fun addTag() {
        SallaPush.User.addTag("last_sample_visit", System.currentTimeMillis().toString())
        showStatus()
    }

    private fun showPermissionAnswer(granted: Boolean) {
        EventFeed.add(getString(if (granted) R.string.event_permission_granted else R.string.event_permission_denied))
        showStatus()
    }

    private fun showStatus() {
        val subscription = SallaPush.User.pushSubscription
        status.text = getString(
            R.string.status,
            SallaPush.sdkVersion,
            SallaPush.appId ?: getString(R.string.status_not_initialized),
            subscription.id ?: getString(R.string.status_not_registered),
            yesNo(SallaPush.Notifications.permission),
            yesNo(subscription.optedIn),
            customer(),
            SallaPush.User.getTags().size,
        )
        optInButton.setText(if (subscription.optedIn) R.string.opt_out else R.string.opt_in)
    }

    private fun customer(): String {
        if (!SallaPush.User.isLoggedIn) return getString(R.string.status_guest)
        return getString(R.string.status_signed_in, SallaPush.User.externalId ?: "?")
    }

    private fun yesNo(value: Boolean): String = getString(if (value) R.string.yes else R.string.no)

    /** Android 15 lays apps that target it out edge to edge: keep the content clear of the bars and the keyboard. */
    private fun keepClearOfSystemBars(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        root.setOnApplyWindowInsetsListener { view, insets ->
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
