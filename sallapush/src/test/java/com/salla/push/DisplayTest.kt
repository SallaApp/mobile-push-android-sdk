package com.salla.push

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.TransactionTooLargeException
import androidx.core.app.NotificationCompat
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.SdkCore
import com.salla.push.internal.click.SallaPushNotificationDismissReceiver
import com.salla.push.internal.click.SallaPushNotificationOpenActivity
import com.salla.push.internal.display.Channels
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Display at the minimum SDK (23, before channels), at the first SDK with channels (26), and at 34 (the
 * POST_NOTIFICATIONS runtime permission). Channel tests run where channels exist.
 */
@Config(sdk = [23, 26, 34])
class DisplayTest : SdkTestBase() {

    private lateinit var manager: NotificationManager

    @Before
    fun setUpNotifications() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = h.context.getSystemService(NotificationManager::class.java)
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun show(data: Map<String, String> = Messages.native()): Boolean {
        val handled = SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(data))
        h.settle()
        return handled
    }

    @Test
    fun test_postedUnderSallaPushTag_idMatchesAndroidNotificationId() {
        h.start()
        assertTrue(show(Messages.native(extra = mapOf("sp_collapse" to "order-987"))))
        val id = SdkCore.stableId("order-987")
        val notification = shadowOf(manager).getNotification("sallapush", id)
        assertNotNull(notification)
        assertEquals("تم شحن طلبك", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("طلبك رقم 987 في الطريق", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun test_subtitleIsSubText() {
        h.start()
        show(Messages.native(extra = mapOf("sp_subtitle" to "Order update")))
        assertEquals("Order update", posted().single().extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test
    @Config(sdk = [26, 34])
    fun test_unknownChannel_usesDefault_createsNothingNamed() {
        h.start()
        show(Messages.native(extra = mapOf("sp_channel" to "orders")))
        assertNull(manager.getNotificationChannel("orders"))
        val channel = manager.getNotificationChannel(Channels.SDK_DEFAULT_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Notifications", channel.name.toString())
        assertEquals(Channels.SDK_DEFAULT_ID, posted().single().channelId)
    }

    @Test
    @Config(sdk = [26, 34])
    fun test_existingPayloadChannel_isUsed() {
        manager.createNotificationChannel(NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT))
        h.start()
        show(Messages.native(extra = mapOf("sp_channel" to "orders")))
        assertEquals("orders", posted().single().channelId)
    }

    @Test
    @Config(sdk = [26, 34])
    fun test_compatOthChannel_existingIsUsed() {
        manager.createNotificationChannel(NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT))
        h.start()
        show(Messages.golden("compat-android") { it.getJSONObject("data") })
        assertEquals("orders", posted().single().channelId)
    }

    @Test
    @Config(sdk = [26, 34])
    fun test_metaDataDefaultChannel_isCreated() {
        h.manifest = ManifestConfig(null, 0, 0, 0, "store_default", "Store", null)
        h.start()
        show()
        val channel = manager.getNotificationChannel("store_default")
        assertEquals("Store", channel.name.toString())
        assertEquals("store_default", posted().single().channelId)
    }

    @Test
    @Config(sdk = [26, 34])
    fun test_blockedChannel_suppressed() {
        manager.createNotificationChannel(NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_NONE))
        h.start()
        assertTrue(show(Messages.native(extra = mapOf("sp_channel" to "orders"))))
        assertTrue(posted().isEmpty())
        assertTrue(h.capture.has(LogCode.NOTIF_SUPPRESSED))
    }

    @Test
    @Config(sdk = [34])
    fun test_runtimePermissionDenied_suppressed() {
        shadowOf(h.context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        h.start()
        assertTrue(show())
        assertTrue(posted().isEmpty())
        assertTrue(h.capture.has(LogCode.NOTIF_SUPPRESSED))
    }

    @Test
    fun test_notificationsSwitchedOffForTheApp_suppressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            shadowOf(manager).setNotificationsEnabled(false)
        } else {
            // Before Android 7 the app-level switch is the POST_NOTIFICATION app op (11, a hidden constant).
            val appOps = h.context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            shadowOf(appOps).setMode(11, h.context.applicationInfo.uid, h.context.packageName, AppOpsManager.MODE_IGNORED)
        }
        h.start()
        assertTrue(show())
        assertTrue(posted().isEmpty())
        assertTrue(h.capture.has(LogCode.NOTIF_SUPPRESSED))
    }

    @Test
    fun test_smallIcon_neverLauncherIcon_defaultWarnsOnce() {
        h.start()
        show(Messages.native(id = "msg_a1"))
        show(Messages.native(id = "msg_a2"))
        val icons = posted().map { it.smallIcon.resId }
        assertTrue(icons.all { it == R.drawable.sallapush_ic_stat_default })
        assertTrue(icons.none { it == h.context.applicationInfo.icon && it != 0 })
        assertEquals(1, h.capture.count(LogCode.DISPLAY_DEFAULT_ICON))
        assertEquals(1, h.console.count { it.startsWith("WARN display.default_icon:") })
    }

    @Test
    fun test_metaDataIcon_isUsed() {
        h.manifest = ManifestConfig(null, android.R.drawable.ic_dialog_info, 0, 0, null, null, null)
        h.start()
        show()
        assertEquals(android.R.drawable.ic_dialog_info, posted().single().smallIcon.resId)
        assertFalse(h.capture.has(LogCode.DISPLAY_DEFAULT_ICON))
    }

    @Test
    @Config(sdk = [26])
    fun test_adaptiveIconOnApi26_replacedBySdkIcon_andTheWarningSaysSo() {
        h.manifest = ManifestConfig(null, android.R.drawable.ic_dialog_info, 0, 0, null, null, null)
        h.drawableLoader = { _, _ -> AdaptiveIconDrawable(ColorDrawable(), ColorDrawable()) }
        h.start()
        show()
        assertEquals(R.drawable.sallapush_ic_stat_default, posted().single().smallIcon.resId)
        assertEquals(
            "The com.salla.push.default_notification_icon meta-data is an adaptive icon, which Android 8.0 cannot show as " +
                "a small icon; using SallaPush's default icon. Add a monochrome drawable there.",
            h.capture.of(LogCode.DISPLAY_DEFAULT_ICON).single().message,
        )
    }

    @Test
    fun test_noIconMetaData_theWarningSaysItIsNotSet() {
        h.start()
        show()
        assertEquals(
            "The com.salla.push.default_notification_icon meta-data is not set; using SallaPush's default icon. " +
                "Add a monochrome drawable there.",
            h.capture.of(LogCode.DISPLAY_DEFAULT_ICON).single().message,
        )
    }

    @Test
    fun test_iconMetaDataOfTheWrongType_theWarningNamesTheTypeOfEach() {
        // android:resource pointing at a string (a common copy-paste slip), and Firebase's icon at a color.
        h.manifest = ManifestConfig(null, R.string.sallapush_default_channel_name, android.R.color.black, 0, null, null, null)
        h.start()
        show()
        assertEquals(R.drawable.sallapush_ic_stat_default, posted().single().smallIcon.resId)
        assertEquals(
            "The com.salla.push.default_notification_icon meta-data is a string resource, not a drawable; Firebase's " +
                "default_notification_icon is a color resource, not a drawable; using SallaPush's default icon. " +
                "Add a monochrome drawable there.",
            h.capture.of(LogCode.DISPLAY_DEFAULT_ICON).single().message,
        )
    }

    @Test
    @Suppress("DEPRECATION") // isActivity()/isBroadcast() replace these from API 31 only; this test also runs on 23.
    fun test_pendingIntents_explicitImmutable_uniquePerNotification() {
        h.start()
        show(Messages.native(id = "msg_b1"))
        show(Messages.native(id = "msg_b2"))
        val notifications = posted()
        assertEquals(2, notifications.size)
        for (notification in notifications) {
            val content = shadowOf(notification.contentIntent)
            assertTrue(content.isActivityIntent)
            assertEquals(SallaPushNotificationOpenActivity::class.java.name, content.savedIntent.component?.className)
            assertTrue(content.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            val delete = shadowOf(notification.deleteIntent)
            assertTrue(delete.isBroadcastIntent)
            assertEquals(SallaPushNotificationDismissReceiver::class.java.name, delete.savedIntent.component?.className)
            assertTrue(delete.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
        assertNotEquals(shadowOf(notifications[0].contentIntent).requestCode, shadowOf(notifications[1].contentIntent).requestCode)
    }

    @Test
    fun test_handleOnMainLooper_returnsQuickly_displayFollows() {
        h.start()
        h.main.current = true
        val started = System.nanoTime()
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native())))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMillis ms", elapsedMillis < 50)
        assertTrue(posted().isEmpty())
        h.main.current = false
        h.settle()
        assertEquals(1, posted().size)
    }

    @Test
    fun test_bigPicture_whenTheImageLoads() {
        h.images.bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        h.start()
        show(Messages.native(extra = mapOf("sp_image" to "https://cdn.salla.sa/x.jpg")))
        assertEquals(listOf("https://cdn.salla.sa/x.jpg"), h.images.requested)
        assertTrue(posted().single().extras.containsKey(Notification.EXTRA_PICTURE))
        assertFalse(h.capture.has(LogCode.NOTIF_PARSE_FAILED))
        assertFalse(h.capture.has(LogCode.NOTIF_IMAGE_UNUSABLE))
    }

    @Test
    fun test_bigPicture_isNotAlsoTheExpandedLargeIcon() {
        h.images.bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        h.start()
        show(Messages.native(extra = mapOf("sp_image" to "https://cdn.salla.sa/x.jpg")))
        val extras = posted().single().extras
        assertTrue(extras.containsKey(Notification.EXTRA_PICTURE))
        assertTrue(extras.containsKey(Notification.EXTRA_LARGE_ICON_BIG))
        @Suppress("DEPRECATION")
        assertNull(extras.get(Notification.EXTRA_LARGE_ICON_BIG))
    }

    @Test
    @Config(sdk = [26, 34], qualifiers = "ar")
    fun test_defaultChannelName_isArabicOnAnArabicDevice() {
        h.start()
        show()
        assertEquals("الإشعارات", manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).name.toString())
    }

    @Test
    @Config(sdk = [23])
    @Suppress("DEPRECATION") // Notification.defaults and sound are what Android 7 and older use.
    fun test_beforeAndroid8_theSoundIsSetPerNotification_defaultsWhenThePayloadsSoundIsNotARawResource() {
        h.start()
        show(Messages.golden("compat-android") { it.getJSONObject("data") })
        val posted = posted().single()
        assertEquals(Notification.DEFAULT_ALL, posted.defaults and Notification.DEFAULT_ALL)
        assertNull(posted.sound)
        show(Messages.native(id = "msg_b3"))
        assertEquals(2, posted().size)
    }

    @Test
    fun test_textOnly_whenTheImageFails_andTheReasonIsReportedOnce() {
        h.start()
        h.images.failure = "HTTP 404"
        show(Messages.native(extra = mapOf("sp_image" to "https://cdn.salla.sa/x.jpg")))
        assertFalse(posted().single().extras.containsKey(Notification.EXTRA_PICTURE))
        // An unusable picture is WARN notif.image_unusable, not a parse failure of the payload.
        val report = h.capture.of(LogCode.NOTIF_IMAGE_UNUSABLE).single()
        assertEquals(SallaPushLogLevel.WARN, report.code.level)
        assertEquals("notifications", report.code.category)
        assertEquals("A SallaPush notification's picture could not be used (HTTP 404); it is shown without it.", report.message)
        assertEquals(1, h.console.count { it.startsWith("WARN notif.image_unusable:") })
        assertFalse(h.capture.has(LogCode.NOTIF_PARSE_FAILED))
    }

    @Test
    @Config(shadows = [PictureTooLargeNotificationManager::class])
    fun test_aPictureTooLargeToPost_isReported_andTheNotificationIsPostedAsText() {
        h.images.bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        h.start()
        show(Messages.native(extra = mapOf("sp_image" to "https://cdn.salla.sa/x.jpg")))
        assertFalse(posted().single().extras.containsKey(Notification.EXTRA_PICTURE))
        assertEquals(
            "A SallaPush notification's picture could not be used (too large to post with the notification); it is shown without it.",
            h.capture.of(LogCode.NOTIF_IMAGE_UNUSABLE).single().message,
        )
        assertFalse(h.capture.has(LogCode.NOTIF_DISPLAY_FAILED))
    }

    @Test
    fun test_aForegroundListenerThatThrows_isReported_andTheOthersStillRun() {
        h.start()
        h.main.current = true
        var ran = 0
        SallaPush.Notifications.addForegroundLifecycleListener { throw IllegalStateException("app bug for customer@example.com") }
        SallaPush.Notifications.addForegroundLifecycleListener { ran += 1 }
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native())))
        h.main.current = false
        h.settle()
        assertEquals(1, ran)
        assertEquals(1, posted().size)
        val report = h.capture.of(LogCode.API_CALLBACK_THREW).single()
        assertEquals(SallaPushLogLevel.ERROR, report.code.level)
        assertTrue(report.message, report.message.startsWith("The listener passed to Notifications.addForegroundLifecycleListener threw IllegalStateException at com.salla.push.DisplayTest"))
        assertFalse(report.message.contains("customer@example.com"))
    }

    @Test
    fun test_clearAll_removesOnlySallaPush_andRemoveNotificationById() {
        h.start()
        manager.notify("other_sdk", 7, NotificationCompat.Builder(h.context, "x").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        show(Messages.native(id = "msg_c1", extra = mapOf("sp_collapse" to "one")))
        show(Messages.native(id = "msg_c2", extra = mapOf("sp_collapse" to "two")))
        assertEquals(3, posted().size)
        SallaPush.Notifications.removeNotification(SdkCore.stableId("one"))
        h.settle()
        assertEquals(2, posted().size)
        SallaPush.Notifications.clearAllNotifications()
        h.settle()
        assertEquals(1, posted().size)
        assertNotNull(shadowOf(manager).getNotification("other_sdk", 7))
    }

    @Test
    fun test_compatDisplaySwitchedOff_notShownButHandled() {
        h.start()
        SallaPush.Migration.setDisplayCompatPayloads(false)
        assertTrue(show(Messages.compat()))
        assertTrue(posted().isEmpty())
        assertTrue(h.engine().events.pending.any { it.type == "received" })
    }

    @Test
    fun test_nothingVisible_notShown_isSuppressedNotAParseFailure() {
        h.start()
        assertTrue(show(Messages.native(title = null, body = null)))
        assertTrue(posted().isEmpty())
        assertEquals(SallaPushLogLevel.DEBUG, h.capture.of(LogCode.NOTIF_SUPPRESSED).single().code.level)
        assertFalse(h.capture.has(LogCode.NOTIF_PARSE_FAILED))
    }

    @Test
    fun test_unreadableFields_areReported_andTheRestIsShown() {
        h.start()
        assertTrue(show(Messages.native(id = "msg_not_hex", extra = mapOf("sp_image" to "http://cdn.salla.sa/x.jpg"))))
        assertEquals(1, posted().size)
        val failure = h.capture.of(LogCode.NOTIF_PARSE_FAILED).single()
        assertTrue(failure.message, failure.message.contains("sp_id") && failure.message.contains("sp_image"))
        assertTrue(h.images.requested.isEmpty())
    }

    @Test
    fun test_foregroundListener_preventDefault_thenDisplayOnce() {
        h.start()
        h.main.current = true
        var captured: SallaPushNotification? = null
        SallaPush.Notifications.addForegroundLifecycleListener { event ->
            captured = event.notification
            event.preventDefault()
        }
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native())))
        h.main.current = false
        h.settle()
        assertTrue(posted().isEmpty())
        val notification = captured ?: throw AssertionError("listener not called")
        notification.display()
        h.settle()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.NOTIF_DISPLAY_FAILED))
        notification.display()
        h.settle()
        assertEquals(1, posted().size)
        assertEquals(
            "Could not show the notification (display() was already called for it).",
            h.capture.of(LogCode.NOTIF_DISPLAY_FAILED).single().message,
        )
    }

    @Test
    fun test_display_ofANotificationTheListenerDidNotPrevent_saysSo() {
        h.start()
        h.main.current = true
        var captured: SallaPushNotification? = null
        SallaPush.Notifications.addForegroundLifecycleListener { event -> captured = event.notification }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native()))
        h.main.current = false
        h.settle()
        assertEquals(1, posted().size)
        captured?.display()
        h.settle()
        assertEquals(1, posted().size)
        assertEquals(
            "Could not show the notification (it was not prevented: call event.preventDefault() first).",
            h.capture.of(LogCode.NOTIF_DISPLAY_FAILED).single().message,
        )
        assertTrue(h.console.contains("WARN notif.display_failed: Could not show the notification (it was not prevented: call event.preventDefault() first)."))
    }

    @Test
    fun test_display_ofANotificationNotPresentedInTheForeground_saysSo() {
        h.start()
        val parsed = com.salla.push.internal.payload.PayloadParser.parse(Messages.native()) ?: throw AssertionError("not parsed")
        h.core.createNotification(parsed, 42).display()
        h.settle()
        assertTrue(posted().isEmpty())
        assertEquals(
            "Could not show the notification (display() works only within 10 minutes of the foreground listener event, " +
                "for a notification SallaPush presented in the foreground).",
            h.capture.of(LogCode.NOTIF_DISPLAY_FAILED).single().message,
        )
    }

    @Test
    fun test_display_fromInsideTheListener_rightAfterPreventDefault_showsItOnce() {
        h.start()
        h.main.current = true
        SallaPush.Notifications.addForegroundLifecycleListener { event ->
            event.preventDefault()
            event.notification.display()
        }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native()))
        h.main.current = false
        h.settle()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.NOTIF_DISPLAY_FAILED))
    }

    @Test
    fun test_aListenerThatPreventsAfterItsTime_leavesTheNotificationShownOnce_andDisplaySaysWhy() {
        h.start()
        SallaPush.Notifications.addForegroundLifecycleListener { event ->
            event.preventDefault()
            event.notification.display()
        }
        // Off the main thread SallaPush waits 500 ms for the listener, which runs only later: the notification is shown.
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native()))
        h.settle()
        assertEquals(1, posted().size)
        assertEquals(
            "Could not show the notification (it was prevented too late: call event.preventDefault() before the listener " +
                "returns, within 500 ms).",
            h.capture.of(LogCode.NOTIF_DISPLAY_FAILED).single().message,
        )
    }

    @Test
    fun test_display_afterTenMinutes_isNoOp() {
        h.start()
        h.main.current = true
        var captured: SallaPushNotification? = null
        SallaPush.Notifications.addForegroundLifecycleListener { event ->
            captured = event.notification
            event.preventDefault()
        }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native()))
        h.main.current = false
        h.advance(601.0)
        captured?.display()
        h.settle()
        assertTrue(posted().isEmpty())
        assertTrue(h.capture.of(LogCode.NOTIF_DISPLAY_FAILED).single().message.contains("only within 10 minutes"))
    }

    @Test
    fun test_foregroundListenerOffMain_waitsBoundedThenDisplays() {
        h.start()
        SallaPush.Notifications.addForegroundLifecycleListener { }
        val started = System.nanoTime()
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native()))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $elapsedMillis ms", elapsedMillis in 400..3000)
        h.settle()
        assertEquals(1, posted().size)
    }

    @Test
    fun test_listenerNotRunInBackground() {
        h.init()
        var called = false
        SallaPush.Notifications.addForegroundLifecycleListener { called = true }
        show()
        assertFalse(called)
        assertEquals(1, posted().size)
    }

    @Test
    fun test_platformFailure_isReportedAsDisplayFailed() {
        val parsed = com.salla.push.internal.payload.PayloadParser.parse(Messages.native()) ?: throw AssertionError("not parsed")
        val notification = h.core.createNotification(parsed, 42)
        val broken = object : android.content.ContextWrapper(h.context) {
            override fun getPackageName(): String = throw IllegalStateException("package manager died")
        }
        val result = h.displayer.show(broken, notification, "nonce-1", ManifestConfig.EMPTY, true)
        assertEquals(com.salla.push.internal.display.DisplayResult.FAILED, result)
        assertTrue(h.capture.has(LogCode.NOTIF_DISPLAY_FAILED))
        assertTrue(posted().isEmpty())
    }
}

/** Refuses a notification that carries a big picture, as Android's binder does above its transaction limit. */
@Implements(NotificationManager::class)
class PictureTooLargeNotificationManager : ShadowNotificationManager() {
    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification) {
        if (notification.extras.containsKey(Notification.EXTRA_PICTURE)) throw RuntimeException(TransactionTooLargeException("data parcel size 1258291 bytes"))
        super.notify(tag, id, notification)
    }
}
