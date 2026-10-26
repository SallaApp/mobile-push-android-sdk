package com.salla.push.internal.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Reports that the device's time zone or locale changed: both are device fields sent to the server. */
internal interface DeviceSettingsMonitor {
    /** Starts reporting to [onChange]; a second start keeps the first registration. */
    fun start(onChange: () -> Unit)

    fun stop()
}

/**
 * A context-registered receiver for ACTION_TIMEZONE_CHANGED and ACTION_LOCALE_CHANGED, registered only while the main
 * engine runs, on an SDK thread. Both are protected broadcasts only the system sends; the receiver is not exported.
 * Every call is guarded: without a receiver (a host that removed androidx.core's receiver permission, an OEM refusal),
 * a change is still seen at the next foreground or activity resume.
 */
internal class AndroidDeviceSettingsMonitor(
    private val context: Context,
) : DeviceSettingsMonitor {

    private val lock = Any()
    private var receiver: BroadcastReceiver? = null

    override fun start(onChange: () -> Unit) {
        synchronized(lock) {
            if (receiver != null) return
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        onChange()
                    } catch (t: Throwable) {
                        // Runs on the app's main thread: a SallaPush failure must never surface there.
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
            try {
                ContextCompat.registerReceiver(context, created, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiver = created
            } catch (t: Throwable) {
                // Not registered: the next foreground or activity resume sends the new values.
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val registered = receiver ?: return
            receiver = null
            try {
                context.unregisterReceiver(registered)
            } catch (t: Throwable) {
                // Already unregistered.
            }
        }
    }
}
