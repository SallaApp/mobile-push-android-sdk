package com.salla.push.internal.display

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.TransactionTooLargeException
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.salla.push.SallaPushNotification
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.click.SallaPushNotificationDismissReceiver
import com.salla.push.internal.click.SallaPushNotificationOpenActivity
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import com.salla.push.internal.permission.NotificationPermission
import com.salla.push.internal.util.printableAscii
import com.salla.push.internal.util.truncatedUtf16
import java.util.Collections

internal enum class DisplayResult {
    SHOWN,

    /** Not shown: permission off, channel blocked or the user opted out. */
    SUPPRESSED,

    /** Nothing user-visible (no title and no body). */
    NOTHING_TO_SHOW,
    FAILED,
}

/**
 * Builds and posts SallaPush notifications under the tag "sallapush", so clearing never touches other
 * SDKs' notifications. Every platform call is guarded; a too-large picture is retried as text. Called off the main
 * thread (the picture download may take up to 8 s).
 */
internal class NotificationDisplayer(
    private val logger: Logger,
    private val images: ImageFetcher,
    private val smallIcon: SmallIcon,
) {

    /** The channels badge.channel_badges_on named in this process: it warns once per channel. */
    private val badgedChannelsWarned: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Shows [notification]. [badges] is autoUpdateBadge: whether a channel SallaPush creates for it shows badges, and,
     * when false, whether to warn about a channel that already exists with badges.
     */
    fun show(context: Context, notification: SallaPushNotification, nonce: String, manifest: ManifestConfig, badges: Boolean): DisplayResult {
        if (notification.title.isNullOrBlank() && notification.body.isNullOrBlank()) {
            logger.log(LogCode.NOTIF_SUPPRESSED) { "Notification not shown (no title and no body)." }
            return DisplayResult.NOTHING_TO_SHOW
        }
        if (!NotificationPermission.allowed(context)) {
            logger.log(LogCode.NOTIF_SUPPRESSED) { "Notification not shown (permission off)." }
            return DisplayResult.SUPPRESSED
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return failed("no notification service")
        val channelId = try {
            Channels.resolve(context, manager, notification.channelId, manifest, badges) { id -> channelShowsBadges(id) }
        } catch (e: Exception) {
            Channels.SDK_DEFAULT_ID
        }
        if (channelId == null) {
            logger.log(LogCode.NOTIF_SUPPRESSED) { "Notification not shown (channel blocked)." }
            return DisplayResult.SUPPRESSED
        }
        val picture = notification.imageUrl?.let { picture(it) }
        val first = build(context, notification, channelId, manifest, picture, nonce)
            ?: return failed("could not build the notification")
        return when (val posted = post(manager, notification.androidNotificationId, first)) {
            null -> DisplayResult.SHOWN
            is TransactionTooLargeException -> {
                if (picture == null) return failed(posted.javaClass.simpleName)
                imageUnusable("too large to post with the notification")
                val textOnly = build(context, notification, channelId, manifest, null, nonce) ?: return failed("could not build the notification")
                val retried = post(manager, notification.androidNotificationId, textOnly)
                if (retried == null) DisplayResult.SHOWN else failed(retried.javaClass.simpleName)
            }
            else -> failed(posted.javaClass.simpleName)
        }
    }

    /** Removes every notification posted under the SallaPush tag. */
    fun clearAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            for (active in manager.activeNotifications) {
                if (active.tag == TAG) manager.cancel(TAG, active.id)
            }
        } catch (e: Exception) {
            // A system service failure leaves the notifications in place.
        }
    }

    /**
     * Removes the SallaPush notifications that show on the app icon's badge, and returns how many it removed: on
     * Android 8.0 and later, every one posted under the SallaPush tag except those on a channel that can't show badges.
     * Below Android 8.0 launchers show no badge, and nothing is removed.
     */
    fun clearBadged(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return 0
        var removed = 0
        try {
            val showsBadges = HashMap<String, Boolean>()
            for (active in manager.activeNotifications) {
                if (active.tag != TAG) continue
                val channelId = active.notification.channelId
                if (channelId != null) {
                    val badged = showsBadges.getOrPut(channelId) { manager.getNotificationChannel(channelId)?.canShowBadge() != false }
                    if (!badged) continue
                }
                manager.cancel(TAG, active.id)
                removed += 1
            }
        } catch (e: Exception) {
            // A system service failure leaves the rest in place.
        }
        return removed
    }

    fun remove(context: Context, androidNotificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            manager.cancel(TAG, androidNotificationId)
        } catch (e: Exception) {
            // A system service failure leaves the notification in place.
        }
    }

    /**
     * The notification's https picture, or null with WARN notif.image_unusable saying why (once per notification): the
     * notification is then shown as text.
     */
    private fun picture(url: String): Bitmap? {
        return when (val result = images.fetch(url)) {
            is ImageResult.Loaded -> result.bitmap
            is ImageResult.Failed -> {
                imageUnusable(result.reason)
                null
            }
        }
    }

    /** WARN badge.channel_badges_on, once per channel in this process. */
    private fun channelShowsBadges(id: String) {
        if (!badgedChannelsWarned.add(id)) return
        logger.log(LogCode.BADGE_CHANNEL_BADGES_ON) {
            "autoUpdateBadge is off, but channel ${printableAscii(truncatedUtf16(id, MAX_SHOWN_CHANNEL_ID))} shows badges, " +
                "so SallaPush notifications on it count on the app icon. Android lets only the user change a channel that " +
                "exists: create a channel with setShowBadge(false) and name it in com.salla.push.default_channel_id."
        }
    }

    private fun imageUnusable(reason: String) {
        logger.log(LogCode.NOTIF_IMAGE_UNUSABLE) { "A SallaPush notification's picture could not be used ($reason); it is shown without it." }
    }

    private fun failed(reason: String): DisplayResult {
        logger.log(LogCode.NOTIF_DISPLAY_FAILED) { "Could not show the notification ($reason)." }
        return DisplayResult.FAILED
    }

    /** Null when posted; else the failure. */
    private fun post(manager: NotificationManager, id: Int, notification: Notification): Exception? {
        return try {
            manager.notify(TAG, id, notification)
            null
        } catch (e: SecurityException) {
            e
        } catch (e: RuntimeException) {
            (e.cause as? TransactionTooLargeException) ?: e
        }
    }

    private fun build(
        context: Context,
        notification: SallaPushNotification,
        channelId: String,
        manifest: ManifestConfig,
        picture: Bitmap?,
        nonce: String,
    ): Notification? {
        return try {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(smallIcon.resolve(context, manifest))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openIntent(context, notification, nonce))
                .setDeleteIntent(dismissIntent(context, notification))
            notification.title?.let { builder.setContentTitle(it) }
            notification.body?.let { builder.setContentText(it) }
            notification.subtitle?.let { builder.setSubText(it) }
            if (manifest.colorResource != 0) {
                try {
                    builder.setColor(ContextCompat.getColor(context, manifest.colorResource))
                } catch (e: Exception) {
                    // A colour resource that does not resolve is ignored.
                }
            }
            if (picture != null) {
                // The picture is the large icon while collapsed only; expanded, it shows once, as the big picture.
                builder.setLargeIcon(picture)
                builder.setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(notification.body),
                )
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            }
            applySound(context, builder, notification.sound)
            builder.build()
        } catch (e: Exception) {
            null
        }
    }

    /** Before Android 8 the sound is set per notification; from 8 on the channel decides. */
    private fun applySound(context: Context, builder: NotificationCompat.Builder, sound: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        val resource = sound?.let { rawResource(context, it) } ?: 0
        if (resource == 0) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            return
        }
        builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_VIBRATE)
        builder.setSound(Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$resource"))
    }

    @Suppress("DiscouragedApi")
    private fun rawResource(context: Context, name: String): Int {
        val base = name.substringBeforeLast('.')
        return try {
            context.resources.getIdentifier(base, "raw", context.packageName)
        } catch (e: Exception) {
            0
        }
    }

    private fun openIntent(context: Context, notification: SallaPushNotification, nonce: String): PendingIntent {
        val intent = Intent(context, SallaPushNotificationOpenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_PAYLOAD, payloadBundle(notification.rawPayload))
            .putExtra(EXTRA_ANDROID_ID, notification.androidNotificationId)
            .putExtra(EXTRA_NONCE, nonce)
        return PendingIntent.getActivity(context, notification.androidNotificationId, intent, PENDING_FLAGS)
    }

    private fun dismissIntent(context: Context, notification: SallaPushNotification): PendingIntent {
        val intent = Intent(context, SallaPushNotificationDismissReceiver::class.java)
            .putExtra(EXTRA_NOTIFICATION_ID, notification.notificationId)
        return PendingIntent.getBroadcast(context, notification.androidNotificationId, intent, PENDING_FLAGS)
    }

    companion object {
        const val TAG = "sallapush"
        const val EXTRA_PAYLOAD = "com.salla.push.extra.PAYLOAD"
        const val EXTRA_ANDROID_ID = "com.salla.push.extra.ANDROID_ID"
        const val EXTRA_NONCE = "com.salla.push.extra.NONCE"
        const val EXTRA_NOTIFICATION_ID = "com.salla.push.extra.NOTIFICATION_ID"
        private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val MAX_SHOWN_CHANNEL_ID = 64

        fun payloadBundle(payload: Map<String, String>): Bundle {
            val bundle = Bundle()
            for ((key, value) in payload) bundle.putString(key, value)
            return bundle
        }

        /** The raw data map back from a Bundle; null-safe and type-checked. */
        fun payloadMap(bundle: Bundle?): Map<String, String> {
            if (bundle == null) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (key in bundle.keySet()) {
                val value = bundle.getString(key) ?: continue
                result[key] = value
            }
            return result
        }
    }
}
