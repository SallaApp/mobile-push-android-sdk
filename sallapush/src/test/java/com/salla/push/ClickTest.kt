package com.salla.push

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.salla.push.internal.SdkCore
import com.salla.push.internal.click.ClickDispatcher
import com.salla.push.internal.click.QueuedClick
import com.salla.push.internal.click.SallaPushNotificationOpenActivity
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.internal.payload.PayloadParser
import com.salla.push.testing.Harness
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Taps at the minimum SDK (23), at 26 and at 34. */
@Config(sdk = [23, 26, 34])
class ClickTest : SdkTestBase() {

    private val launcher = ComponentName("org.robolectric.default", "com.example.MainActivity")

    @Before
    fun registerLauncherActivity() {
        val packageManager = shadowOf(h.context.packageManager)
        val component = ComponentName(h.context.packageName, "com.example.MainActivity")
        packageManager.addActivityIfNotPresent(component)
        val filter = IntentFilter(Intent.ACTION_MAIN)
        filter.addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.addIntentFilterForActivity(component, filter)
    }

    private fun open(data: Map<String, String>, nonce: String = "n1"): SallaPushNotificationOpenActivity {
        val controller = Robolectric.buildActivity(SallaPushNotificationOpenActivity::class.java, Messages.openIntent(h, data, 42, nonce)).create()
        h.settle()
        return controller.get()
    }

    @Test
    fun test_open_recordsOpened_dispatchesClick_launchesTheApp() {
        h.registered()
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        h.settle()
        val data = Messages.native(id = "msg_d1", extra = mapOf("sp_url" to "salla://orders/987", "orderId" to "987"))
        val activity = open(data)
        assertTrue(activity.isFinishing)
        assertEquals(1, clicks.size)
        val click = clicks.single()
        assertNull(click.result.actionId)
        assertEquals("salla://orders/987", click.result.url)
        assertEquals("msg_d1", click.notification.notificationId)
        assertEquals(42, click.notification.androidNotificationId)
        assertEquals(987L, click.notification.getLong("orderId"))
        val started = shadowOf(h.context as Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(launcher.className, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertEquals("msg_d1", started.getStringExtra(SdkCore.EXTRA_LAUNCH_NOTIFICATION_ID))
        assertEquals("987", started.getBundleExtra(SdkCore.EXTRA_LAUNCH_PAYLOAD)?.getString("orderId"))
        val delivered = h.capture.of(LogCode.NOTIF_CLICK_DELIVERED).single()
        assertEquals("Click delivered to 1 listener.", delivered.message)
        assertEquals("1", delivered.metadata[Meta.COUNT])
        assertTrue(h.engine().events.pending.any { it.type == "opened" && it.notificationId == "msg_d1" } || h.engine().events.seen.contains("msg_d1|opened"))
    }

    @Test
    fun test_clickQueuedUntilAListener_thenDeliveredOnceToTheFirst() {
        h.registered()
        open(Messages.native(id = "msg_d2"))
        assertTrue(h.capture.has(LogCode.NOTIF_CLICK_QUEUED))
        val first = ArrayList<SallaPushClickEvent>()
        val second = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { first.add(it) }
        h.settle()
        SallaPush.Notifications.addClickListener { second.add(it) }
        h.settle()
        assertEquals(1, first.size)
        assertEquals(0, second.size)
    }

    @Test
    fun test_sameNonceTwice_oneClick() {
        h.registered()
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        h.settle()
        val data = Messages.native(id = "msg_d3")
        open(data, nonce = "same")
        open(data, nonce = "same")
        assertEquals(1, clicks.size)
    }

    @Test
    fun test_queuedClickExpiresAfterFiveMinutes() {
        h.registered()
        open(Messages.native(id = "msg_d4"))
        h.advance(301.0)
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        h.settle()
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun test_pendingClicks_persistedAcrossProcessDeath() {
        h.registered()
        open(Messages.native(id = "msg_d5"))
        h.settle()
        assertTrue(h.stateJson().contains("\"nonce\":\"n1\""))
        val next = h.relaunch()
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        next.settle()
        assertEquals(1, clicks.size)
        assertEquals("msg_d5", clicks.single().notification.notificationId)
        next.close()
    }

    @Test
    fun test_pendingClicks_restoredWhenAWorkerCreatedTheEngineBeforeTheAppInitialised() {
        h.registered()
        open(Messages.native(id = "msg_e1"), nonce = "nB")
        assertTrue(h.stateJson().contains("\"nonce\":\"nB\""))
        // A new process: WorkManager runs first, then the app initialises and adds its click listener.
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.install()
        next.healthyServer()
        next.runWorker()
        next.start()
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        next.settle()
        assertEquals(1, clicks.size)
        assertEquals("msg_e1", clicks.single().notification.notificationId)
        // Delivered once: the stored tap is gone, and another launch does not deliver it again.
        assertFalse(next.stateJson().contains("\"nonce\":\"nB\""))
        next.close()
    }

    @Test
    fun test_openLaunchUrls_httpsOpensTheUrl_otherwiseTheApp() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setOpenLaunchUrls(true).build())
        open(Messages.native(id = "msg_d6", extra = mapOf("sp_url" to "https://s.salla.sa/o/987")))
        val started = shadowOf(h.context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://s.salla.sa/o/987", started.dataString)
        open(Messages.native(id = "msg_d7", extra = mapOf("sp_url" to "http://s.salla.sa/o/987")), nonce = "n2")
        assertEquals(launcher.className, shadowOf(h.context as Application).nextStartedActivity.component?.className)
    }

    @Test
    fun test_launchUrlNotOpenedByDefault() {
        h.registered()
        open(Messages.native(id = "msg_d8", extra = mapOf("sp_url" to "https://s.salla.sa/o/987")))
        assertEquals(launcher.className, shadowOf(h.context as Application).nextStartedActivity.component?.className)
    }

    @Test
    fun test_foreignOrEmptyPayload_justLaunchesTheApp() {
        h.registered()
        open(mapOf("title" to "x"))
        assertEquals(launcher.className, shadowOf(h.context as Application).nextStartedActivity.component?.className)
        assertTrue(h.engine().events.pending.isEmpty())
    }

    @Test
    fun test_failingClickListener_doesNotStopOthers_andIsReportedWithWhereItThrew() {
        h.registered()
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { throw IllegalStateException("app bug for customer@example.com") }
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        h.settle()
        open(Messages.native(id = "msg_d9"))
        assertEquals(1, clicks.size)
        val threw = h.capture.of(LogCode.API_CALLBACK_THREW).single()
        assertEquals(SallaPushLogLevel.ERROR, threw.code.level)
        val report = threw.message
        assertTrue(report, report.startsWith("The listener passed to Notifications.addClickListener threw IllegalStateException at com.salla.push.ClickTest"))
        assertTrue(report, Regex(":\\d+").containsMatchIn(report))
        // The exception's message may carry the app's data: it is never logged.
        assertFalse(report.contains("customer@example.com"))
    }

    @Test
    fun test_openLaunchUrls_aUrlNothingCanOpen_opensTheAppInstead() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().setOpenLaunchUrls(true).build())
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        h.settle()
        // No activity on this device handles the URL: starting it throws ActivityNotFoundException.
        shadowOf(h.context as Application).checkActivities(true)
        val activity = open(Messages.native(id = "msg_e2", extra = mapOf("sp_url" to "https://s.salla.sa/o/987")))
        assertTrue(activity.isFinishing)
        val started = shadowOf(h.context as Application).nextStartedActivity
        assertNotNull("the tap opened the app", started)
        assertEquals(launcher.className, started.component?.className)
        assertEquals("msg_e2", started.getStringExtra(SdkCore.EXTRA_LAUNCH_NOTIFICATION_ID))
        // The tap was recorded and dispatched as usual.
        assertEquals("https://s.salla.sa/o/987", clicks.single().result.url)
    }

    @Test
    fun test_theSameListenerAddedFromSeveralThreadsAtOnce_getsEachTapOnce() {
        repeat(ROUNDS) { round ->
            val dispatcher = ClickDispatcher(h.main, h.clock, h.logger) { }
            val delivered = AtomicInteger()
            val listener = SallaPushClickListener { delivered.incrementAndGet() }
            val barrier = CyclicBarrier(THREADS)
            val threads = (1..THREADS).map {
                Thread {
                    barrier.await(5, TimeUnit.SECONDS)
                    dispatcher.addListener(listener)
                }.apply { start() }
            }
            for (thread in threads) thread.join(5_000)
            h.main.runDue()
            val parsed = PayloadParser.parse(Messages.native(id = "msg_e3")) ?: throw AssertionError("not parsed")
            val notification = h.core.createNotification(parsed, 7)
            h.main.post(Runnable {
                dispatcher.dispatch(QueuedClick(SallaPushClickEvent(notification, SallaPushClickResult(null, null)), "nonce-$round", h.clock.nowMillis(), parsed.rawPayload, 7))
            })
            h.main.runDue()
            assertEquals("round $round", 1, delivered.get())
        }
    }

    @Test
    fun test_aTapKeptForTheFirstListener_isDeliveredWhenItIsAdded_evenIfAnotherWasRemovedMeanwhile() {
        h.registered()
        val gone = SallaPushClickListener { }
        SallaPush.Notifications.addClickListener(gone)
        SallaPush.Notifications.removeClickListener(gone)
        h.settle()
        open(Messages.native(id = "msg_e4"))
        val clicks = ArrayList<SallaPushClickEvent>()
        SallaPush.Notifications.addClickListener { clicks.add(it) }
        h.settle()
        assertEquals(listOf("msg_e4"), clicks.map { it.notification.notificationId })
    }

    private companion object {
        const val ROUNDS = 2_000
        const val THREADS = 4
    }
}
