package com.salla.push.internal.permission

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.salla.push.internal.Sdk
import com.salla.push.internal.util.Once

/**
 * A translucent, non-exported activity that shows the Android 13+ notification permission prompt and reports the
 * answer. With fallbackToSettings, a denial that can no longer be prompted for opens the app's notification settings.
 * Recreated for a configuration change while the system dialog shows, it keeps waiting: the new instance receives the
 * result.
 */
internal class SallaPushPermissionActivity : Activity() {

    private val reported = Once()
    private var requestId = NO_REQUEST

    /** shouldShowRequestPermissionRationale just before the prompt was requested. */
    private var rationaleBefore = false

    /** When the prompt was requested (the SDK clock), and whether this activity was covered since (paused). */
    private var requestedAtMillis = 0L
    private var pausedSinceRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestId = intent?.getLongExtra(EXTRA_REQUEST_ID, NO_REQUEST) ?: NO_REQUEST
            Sdk.core.permissionRequests.onActivityCreated(requestId)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || NotificationPermission.runtimeGranted(this)) {
                report(NotificationPermission.allowed(this))
                finish()
                return
            }
            if (savedInstanceState == null) {
                rationaleBefore = shouldShowRequestPermissionRationale(NotificationPermission.POST_NOTIFICATIONS)
                requestedAtMillis = Sdk.core.env.clock.nowMillis()
                requestPermissions(arrayOf(NotificationPermission.POST_NOTIFICATIONS), REQUEST_CODE)
            } else {
                rationaleBefore = savedInstanceState.getBoolean(STATE_RATIONALE_BEFORE, false)
                requestedAtMillis = savedInstanceState.getLong(STATE_REQUESTED_AT, 0L)
                pausedSinceRequest = savedInstanceState.getBoolean(STATE_PAUSED, false)
            }
        } catch (t: Throwable) {
            report(false)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // The system permission dialog is an activity over this one: it pauses this one while it shows.
        pausedSinceRequest = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RATIONALE_BEFORE, rationaleBefore)
        outState.putLong(STATE_REQUESTED_AT, requestedAtMillis)
        outState.putBoolean(STATE_PAUSED, pausedSinceRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        try {
            if (requestCode != REQUEST_CODE) return
            if (permissions.isEmpty() || grantResults.isEmpty()) {
                // An interrupted request: Android passes empty arrays, to be treated as a cancellation, not an answer.
                report(NotificationPermission.allowed(this))
                return
            }
            val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cannotPromptAgain()) {
                Sdk.core.permissionRequests.permanentlyDenied = true
                if (intent?.getBooleanExtra(EXTRA_FALLBACK_TO_SETTINGS, false) == true) openSettings()
            }
            report(granted && NotificationPermission.allowed(this))
        } catch (t: Throwable) {
            report(false)
        } finally {
            finish()
        }
    }

    /**
     * Android never says "don't ask again" directly. A denial means it when the rationale flag went from true to false
     * (the second explicit denial), or when no dialog showed: this activity was never covered, or the answer came
     * faster than a person can answer one. A first denial, a dismissed dialog and an interrupted request can still be
     * prompted for, so they never open the settings.
     */
    private fun cannotPromptAgain(): Boolean {
        if (shouldShowRequestPermissionRationale(NotificationPermission.POST_NOTIFICATIONS)) return false
        if (rationaleBefore) return true
        val answeredAfterMillis = Sdk.core.env.clock.nowMillis() - requestedAtMillis
        return !pausedSinceRequest || answeredAfterMillis < MIN_HUMAN_ANSWER_MILLIS
    }

    override fun onDestroy() {
        try {
            // A configuration change recreates this activity while the dialog shows: the new instance answers.
            if (!isChangingConfigurations) report(NotificationPermission.allowed(this))
        } catch (t: Throwable) {
            report(false)
        }
        super.onDestroy()
    }

    private fun report(granted: Boolean) {
        if (!reported.claim()) return
        try {
            Sdk.core.onPermissionRequestFinished(requestId, granted)
        } catch (ignored: Throwable) {
            // The request's callbacks are best effort once the activity is going away.
        }
    }

    private fun openSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // No settings screen on this device: the denial stands.
        }
    }

    companion object {
        const val EXTRA_FALLBACK_TO_SETTINGS = "com.salla.push.extra.FALLBACK_TO_SETTINGS"
        const val EXTRA_REQUEST_ID = "com.salla.push.extra.PERMISSION_REQUEST_ID"
        const val REQUEST_CODE = 7301

        /** The dialog takes a few hundred milliseconds to appear: no person answers it sooner than this. */
        const val MIN_HUMAN_ANSWER_MILLIS = 500L
        private const val NO_REQUEST = -1L
        private const val STATE_RATIONALE_BEFORE = "com.salla.push.state.RATIONALE_BEFORE"
        private const val STATE_REQUESTED_AT = "com.salla.push.state.REQUESTED_AT"
        private const val STATE_PAUSED = "com.salla.push.state.PAUSED"
    }
}
