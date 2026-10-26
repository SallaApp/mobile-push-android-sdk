package com.salla.push

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.display.Channels
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Harness
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The app icon's badge on Android: launchers count an app's notifications in the shade, so SallaPush adds no counter.
 * autoUpdateBadge decides whether the channels SallaPush creates show badges; autoClearBadge, off by default, removes
 * SallaPush's notifications that show on the badge when the app comes to the foreground, never after an unlock with the
 * app on screen.
 */
@Config(sdk = [26, 34])
class BadgeTest : SdkTestBase() {

    private lateinit var manager: NotificationManager

    @Before
    fun setUpNotifications() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = h.context.getSystemService(NotificationManager::class.java)
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun show(data: Map<String, String> = Messages.native()) {
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(data)))
        h.settle()
    }

    private fun noBadges(): SallaPushConfig = SallaPushConfig.Builder().setAutoUpdateBadge(false).build()

    @Test
    fun test_byDefault_sallaPushsChannelShowsBadges() {
        h.start()
        show()
        assertTrue(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
        assertEquals(Channels.SDK_DEFAULT_ID, posted().single().channelId)
        assertFalse(h.capture.has(LogCode.BADGE_CHANNEL_BADGES_ON))
    }

    @Test
    fun test_autoUpdateBadgeOff_sallaPushsChannelIsCreatedWithoutBadges() {
        h.start(config = noBadges())
        show()
        assertFalse(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CHANNEL_BADGES_ON))
    }

    @Test
    fun test_theManifestsDefaultChannel_isCreatedTheSameWay() {
        h.manifest = ManifestConfig(null, 0, 0, 0, "store_default", "Store", null)
        h.start(config = noBadges())
        show()
        assertFalse(manager.getNotificationChannel("store_default").canShowBadge())
        assertEquals("store_default", posted().single().channelId)
    }

    @Test
    fun test_theManifestsDefaultChannel_showsBadgesByDefault() {
        h.manifest = ManifestConfig(null, 0, 0, 0, "store_default", "Store", null)
        h.start()
        show()
        assertTrue(manager.getNotificationChannel("store_default").canShowBadge())
    }

    @Test
    fun test_autoUpdateBadgeOff_aChannelThatAlreadyShowsBadges_isUsed_andWarnedAboutOncePerChannel() {
        // 1.0.x created sallapush_default with badges, and Android lets only the user change it.
        manager.createNotificationChannel(NotificationChannel(Channels.SDK_DEFAULT_ID, "Notifications", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT))
        h.start(config = noBadges())
        show(Messages.native(id = "msg_a1"))
        show(Messages.native(id = "msg_a2"))
        show(Messages.native(id = "msg_a3", extra = mapOf("sp_channel" to "orders")))
        show(Messages.native(id = "msg_a4", extra = mapOf("sp_channel" to "orders")))
        assertEquals(4, posted().size)
        assertTrue(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
        val warnings = h.capture.of(LogCode.BADGE_CHANNEL_BADGES_ON)
        assertEquals(2, warnings.size)
        assertEquals(SallaPushLogLevel.WARN, warnings[0].code.level)
        assertEquals("notifications", warnings[0].code.category)
        assertEquals(
            "autoUpdateBadge is off, but channel sallapush_default shows badges, so SallaPush notifications on it count on " +
                "the app icon. Android lets only the user change a channel that exists: create a channel with " +
                "setShowBadge(false) and name it in com.salla.push.default_channel_id.",
            warnings[0].message,
        )
        assertTrue(warnings[1].message, warnings[1].message.startsWith("autoUpdateBadge is off, but channel orders shows badges"))
    }

    @Test
    fun test_autoUpdateBadgeOff_aChannelWithoutBadges_isNoWarning() {
        val quiet = NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT)
        quiet.setShowBadge(false)
        manager.createNotificationChannel(quiet)
        h.start(config = noBadges())
        show(Messages.native(extra = mapOf("sp_channel" to "orders")))
        assertEquals("orders", posted().single().channelId)
        assertFalse(h.capture.has(LogCode.BADGE_CHANNEL_BADGES_ON))
    }

    @Test
    fun test_autoUpdateBadgeOn_anExistingChannelWithBadges_isNoWarning() {
        manager.createNotificationChannel(NotificationChannel(Channels.SDK_DEFAULT_ID, "Notifications", NotificationManager.IMPORTANCE_HIGH))
        h.start()
        show()
        assertFalse(h.capture.has(LogCode.BADGE_CHANNEL_BADGES_ON))
    }

    @Test
    fun test_aBlockedChannel_isSuppressed_withoutABadgeWarning() {
        manager.createNotificationChannel(NotificationChannel(Channels.SDK_DEFAULT_ID, "Notifications", NotificationManager.IMPORTANCE_NONE))
        h.start(config = noBadges())
        show()
        assertTrue(posted().isEmpty())
        assertTrue(h.capture.has(LogCode.NOTIF_SUPPRESSED))
        assertFalse(h.capture.has(LogCode.BADGE_CHANNEL_BADGES_ON))
    }

    @Test
    fun test_theSettingIsSavedInLastInit_andANotificationShownBeforeInitWithContextUsesIt() {
        h.healthyServer()
        h.start(config = noBadges())
        assertTrue(File(h.root, "sallapush/last_init.json").readText().contains("\"auto_update_badge\":false"))
        // A new process that FCM started for a push: the app hasn't called initWithContext in it.
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.install()
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(next.context, Messages.remote(Messages.native())))
        next.settle()
        assertFalse(SallaPush.isInitialized)
        assertEquals(1, posted().size)
        assertFalse(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
        next.close()
    }

    @Test
    fun test_beforeInitWithContext_withoutASavedSetting_theChannelShowsBadges() {
        // A last_init.json of a version without the setting, as 1.0.x wrote it.
        val lastInit = File(h.root, "sallapush/last_init.json")
        lastInit.parentFile?.mkdirs()
        lastInit.writeText("""{"v":1,"app_id":"st_42_android","api_base_url":"https://api.salla.dev/notifications/v1/sallapush","log_level":"warn"}""")
        show()
        assertFalse(SallaPush.isInitialized)
        assertTrue(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
    }

    @Test
    fun test_beforeInitWithContext_withoutLastInit_theChannelShowsBadges() {
        show()
        assertTrue(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
    }

    @Test
    fun test_consentWithheld_theConfigurationDecidesTheChannel_notASavedSetting() {
        // Nothing but the consent answer is written while consent is withheld, so no saved setting exists.
        SallaPush.setConsentRequired(true)
        h.start(config = noBadges())
        show()
        assertFalse(File(h.root, "sallapush/last_init.json").exists())
        assertFalse(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
    }

    @Test
    fun test_aSettingChangedAtALaterLaunch_isSavedAgain() {
        h.healthyServer()
        h.start(config = noBadges())
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.install()
        next.healthyServer()
        next.start()
        assertTrue(File(next.root, "sallapush/last_init.json").readText().contains("\"auto_update_badge\":true"))
        next.close()
    }

    @Test
    @Config(sdk = [23])
    fun test_beforeAndroid8_thereAreNoChannels_andTheSettingChangesNothing() {
        h.start(config = noBadges())
        show()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CHANNEL_BADGES_ON))
    }

    @Test
    fun test_noCounter_eachNotificationIsOne_andAReplacementKeepsOne() {
        h.start()
        show(Messages.native(id = "msg_b1", extra = mapOf("sp_collapse" to "order-1")))
        show(Messages.native(id = "msg_b2", extra = mapOf("sp_collapse" to "order-1")))
        show(Messages.native(id = "msg_b3", extra = mapOf("sp_collapse" to "order-2")))
        assertEquals(2, posted().size)
        assertTrue(posted().all { it.number == 0 })
    }

    // ── autoClearBadge ──

    private fun clearOnOpen(): SallaPushConfig = SallaPushConfig.Builder().setAutoClearBadge(true).build()

    private fun keepBadges(): SallaPushConfig = SallaPushConfig.Builder().setAutoClearBadge(false).build()

    private fun otherSdksNotification() {
        manager.notify("other_sdk", 7, NotificationCompat.Builder(h.context, "other").setSmallIcon(android.R.drawable.ic_dialog_info).build())
    }

    private fun reopen() {
        h.background()
        h.foreground()
    }

    @Test
    fun test_byDefault_theForegroundRemovesNothing() {
        h.start()
        show()
        reopen()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_theAppComingToTheForeground_removesSallaPushsNotifications_andNoOtherSdks() {
        h.start(config = clearOnOpen())
        otherSdksNotification()
        show(Messages.native(id = "msg_c1", extra = mapOf("sp_collapse" to "one")))
        show(Messages.native(id = "msg_c2", extra = mapOf("sp_collapse" to "two")))
        h.advance(30.0)
        assertEquals("they stay while the app is open", 3, posted().size)
        h.background()
        assertEquals("the background removes nothing", 3, posted().size)
        h.foreground()
        assertEquals(1, posted().size)
        assertNotNull(shadowOf(manager).getNotification("other_sdk", 7))
        val cleared = h.capture.of(LogCode.BADGE_CLEARED).single()
        assertEquals(SallaPushLogLevel.DEBUG, cleared.code.level)
        assertEquals("notifications", cleared.code.category)
        assertEquals("2", cleared.metadata[Meta.COUNT])
        assertEquals(
            "Cleared the badge: removed 2 SallaPush notifications from the shade as the app came to the foreground (autoClearBadge).",
            cleared.message,
        )
        assertTrue("no dismissed event for them", h.engine().events.pending.none { it.type == "dismissed" })
    }

    @Test
    fun test_aForegroundWithNothingToRemove_logsNothing() {
        h.start(config = clearOnOpen())
        reopen()
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_autoClearBadgeOff_theNotificationsStay() {
        h.start(config = keepBadges())
        show()
        reopen()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_anUnlockWithTheAppOnScreen_removesNothing_butTheNextVisitDoes() {
        h.init(config = clearOnOpen())
        val app = Robolectric.buildActivity(Activity::class.java).setup()
        h.advance(0.6)
        val power = shadowOf(h.context.getSystemService(PowerManager::class.java))
        // The screen turns off with the app on screen: the lock screen stops the activity.
        power.setIsInteractive(false)
        app.pause().stop()
        h.advance(1.0)
        show(Messages.native(id = "msg_d1", extra = mapOf("sp_collapse" to "one")))
        show(Messages.native(id = "msg_d2", extra = mapOf("sp_collapse" to "two")))
        // The user unlocks the phone to read them: the activity starts again.
        power.setIsInteractive(true)
        app.restart().resume()
        h.advance(0.6)
        assertEquals(2, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
        // Home, then the app again from the launcher.
        app.pause().stop()
        h.advance(1.0)
        app.restart().resume()
        h.advance(0.6)
        assertTrue(posted().isEmpty())
        assertEquals("2", h.capture.of(LogCode.BADGE_CLEARED).single().metadata[Meta.COUNT])
    }

    @Test
    fun test_autoUpdateBadgeOff_theForegroundLeavesSallaPushsNotifications_butRemovesOneOnAChannelWithBadges() {
        manager.createNotificationChannel(NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT))
        h.start(config = SallaPushConfig.Builder().setAutoClearBadge(true).setAutoUpdateBadge(false).build())
        show(Messages.native(id = "msg_e1", extra = mapOf("sp_collapse" to "one")))
        show(Messages.native(id = "msg_e2", extra = mapOf("sp_collapse" to "two", "sp_channel" to "orders")))
        reopen()
        assertEquals(Channels.SDK_DEFAULT_ID, posted().single().channelId)
        assertEquals("1", h.capture.of(LogCode.BADGE_CLEARED).single().metadata[Meta.COUNT])
    }

    @Test
    fun test_bothOff_sallaPushNeverTouchesTheBadge() {
        h.start(config = SallaPushConfig.Builder().setAutoClearBadge(false).setAutoUpdateBadge(false).build())
        show()
        reopen()
        assertEquals(1, posted().size)
        assertFalse(manager.getNotificationChannel(Channels.SDK_DEFAULT_ID).canShowBadge())
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    @Config(sdk = [23])
    fun test_beforeAndroid8_launchersShowNoBadge_andNothingIsRemoved() {
        h.start(config = clearOnOpen())
        show()
        reopen()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_consentRequiredAndNotGiven_nothingIsRemoved_untilItIsGiven() {
        SallaPush.setConsentRequired(true)
        h.start(config = clearOnOpen())
        show()
        reopen()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
        SallaPush.setConsentGiven(true)
        h.settle()
        reopen()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun test_beforeInitWithContext_aForegroundRemovesNothing() {
        show()
        Robolectric.buildActivity(Activity::class.java).setup()
        h.advance(1.0)
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_beforeInitWithContext_aForegroundTheCoreHears_removesNothing() {
        // A push gave SallaPush the app's Context, but initWithContext hasn't run: the clear on open waits for it.
        show()
        h.core.onForeground(false)
        h.settle()
        assertEquals(1, posted().size)
        assertFalse(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_aLateInitWithContext_withTheAppOnScreen_isTheAppOpening() {
        // FCM showed a notification first; the app then started SallaPush late, with its activity on screen.
        show()
        h.processForeground = true
        h.init(config = clearOnOpen())
        h.advance(0.1)
        assertTrue(posted().isEmpty())
        assertTrue(h.capture.has(LogCode.BADGE_CLEARED))
    }

    @Test
    fun test_clearAllNotifications_stillRemovesEverySallaPushNotification_whatEverItsChannel() {
        val quiet = NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT)
        quiet.setShowBadge(false)
        manager.createNotificationChannel(quiet)
        h.start(config = keepBadges())
        show(Messages.native(id = "msg_f1", extra = mapOf("sp_collapse" to "one", "sp_channel" to "orders")))
        show(Messages.native(id = "msg_f2", extra = mapOf("sp_collapse" to "two")))
        SallaPush.Notifications.clearAllNotifications()
        h.settle()
        assertTrue(posted().isEmpty())
    }
}
