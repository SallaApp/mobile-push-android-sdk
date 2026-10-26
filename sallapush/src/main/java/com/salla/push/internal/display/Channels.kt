package com.salla.push.internal.display

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.salla.push.R
import com.salla.push.internal.ManifestConfig

/**
 * Notification channels. A payload may only pick a channel that already exists: SallaPush never
 * creates a channel named by a payload (an app's sound channels are immutable once created). Otherwise the default
 * channel: com.salla.push.default_channel_id (created if needed), else Firebase's default channel if it exists,
 * else "sallapush_default", created lazily with IMPORTANCE_HIGH. A channel SallaPush creates shows badges when
 * [resolve]'s badges is true (autoUpdateBadge); Android fixes that when the channel is created.
 */
internal object Channels {

    const val SDK_DEFAULT_ID = "sallapush_default"

    /**
     * The channel to post on; null when the user blocked it (the notification is then not shown). With [badges] false,
     * [onBadgesOn] receives the id of a channel that already exists and still shows badges.
     */
    fun resolve(
        context: Context,
        manager: NotificationManager,
        requested: String?,
        manifest: ManifestConfig,
        badges: Boolean,
        onBadgesOn: (String) -> Unit,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return SDK_DEFAULT_ID
        if (requested != null) {
            val channel = manager.getNotificationChannel(requested)
            if (channel != null) return usable(requested, channel.importance, channel.canShowBadge(), badges, onBadgesOn)
        }
        manifest.defaultChannelId?.let { id ->
            val channel = manager.getNotificationChannel(id)
            if (channel != null) return usable(id, channel.importance, channel.canShowBadge(), badges, onBadgesOn)
            if (create(context, manager, id, manifest, badges)) return id
        }
        manifest.firebaseChannelId?.let { id ->
            val channel = manager.getNotificationChannel(id)
            if (channel != null) return usable(id, channel.importance, channel.canShowBadge(), badges, onBadgesOn)
        }
        val channel = manager.getNotificationChannel(SDK_DEFAULT_ID)
        if (channel != null) return usable(SDK_DEFAULT_ID, channel.importance, channel.canShowBadge(), badges, onBadgesOn)
        return if (create(context, manager, SDK_DEFAULT_ID, manifest, badges)) SDK_DEFAULT_ID else null
    }

    /** An existing channel's [id], or null when the user blocked it; tells [onBadgesOn] when it shows unwanted badges. */
    private fun usable(id: String, importance: Int, showsBadge: Boolean, badges: Boolean, onBadgesOn: (String) -> Unit): String? {
        if (importance == NotificationManager.IMPORTANCE_NONE) return null
        if (!badges && showsBadge) onBadgesOn(id)
        return id
    }

    private fun create(context: Context, manager: NotificationManager, id: String, manifest: ManifestConfig, badges: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val name = manifest.defaultChannelName ?: context.getString(R.string.sallapush_default_channel_name)
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            channel.setShowBadge(badges)
            manager.createNotificationChannel(channel)
            true
        } catch (e: Exception) {
            false
        }
    }
}
