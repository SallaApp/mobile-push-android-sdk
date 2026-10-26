package com.salla.push

import android.Manifest
import android.content.Intent
import com.salla.push.internal.api.Routes
import com.salla.push.internal.click.SallaPushNotificationDismissReceiver
import com.salla.push.internal.click.SallaPushNotificationOpenActivity
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.engine.recordEvent
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

class EventsTest : SdkTestBase() {

    @Before
    fun grantNotifications() {
        shadowOf(h.context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun events(): List<JSONObject> = h.net.calls(Routes.EVENTS).flatMap { call ->
        val array = call.body?.getJSONArray("events") ?: return@flatMap emptyList<JSONObject>()
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun test_receivedAndDisplayed_sentAsOneBatch() {
        h.registered()
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_a1"))))
        h.advance(6.0)
        val sent = events()
        assertEquals(listOf("received", "displayed"), sent.map { it.getString("type") })
        assertTrue(sent.all { it.getString("notification_id") == "msg_a1" })
        assertTrue(sent.all { it.getString("at").matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")) })
        assertEquals(1, h.net.calls(Routes.EVENTS).size)
        assertTrue(h.capture.has(LogCode.EVENTS_FLUSHED))
    }

    @Test
    fun test_aBeta1QueuedEvent_isSentWithMilliseconds() {
        val beta1At = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(h.clock.millis))
        val pending = JSONObject().put("id", "msg_5a11a90001").put("type", "received").put("at", beta1At)
        val beta1File = JSONObject().put("v", 1).put("pending", JSONArray().put(pending)).put("seen", JSONArray().put("msg_5a11a90001|received"))
        h.appDirectory().mkdirs()
        File(h.appDirectory(), "events.json").writeText(beta1File.toString())
        h.registered()
        h.advance(6.0)
        val sent = events().single()
        assertEquals("msg_5a11a90001", sent.getString("notification_id"))
        assertEquals(beta1At.removeSuffix("Z") + ".000Z", sent.getString("at"))
    }

    @Test
    fun test_eventsFlushed_countsTheEventsSent() {
        h.registered()
        h.net.on("POST", Routes.EVENTS) { Http.json(202, "{}") }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_a9")))
        h.advance(6.0)
        assertTrue(SallaPush.Notifications.trackOpened(JSONObject().put("push_ref", "msg_a9")))
        h.advance(6.0)
        val flushed = h.capture.of(LogCode.EVENTS_FLUSHED)
        assertEquals(listOf("2 events sent.", "1 event sent."), flushed.map { it.message })
        assertEquals(listOf("2", "1"), flushed.map { it.metadata[Meta.COUNT] })
    }

    @Test
    fun test_tap_recordsOpenedOnce() {
        h.registered()
        val data = Messages.native(id = "msg_b2")
        Robolectric.buildActivity(SallaPushNotificationOpenActivity::class.java, Messages.openIntent(h, data, nonce = "n1")).create()
        Robolectric.buildActivity(SallaPushNotificationOpenActivity::class.java, Messages.openIntent(h, data, nonce = "n2")).create()
        h.advance(6.0)
        assertEquals(listOf("opened"), events().map { it.getString("type") })
    }

    @Test
    fun test_dismissAction_recordsDismissed_noClick() {
        h.registered()
        var clicks = 0
        SallaPush.Notifications.addClickListener { clicks += 1 }
        SallaPushNotificationDismissReceiver().onReceive(h.context, Intent().putExtra(NotificationDisplayer.EXTRA_NOTIFICATION_ID, "msg_c3"))
        h.advance(6.0)
        assertEquals(listOf("dismissed"), events().map { it.getString("type") })
        assertEquals(0, clicks)
    }

    @Test
    fun test_eventsBeforeRegistration_sentAfter() {
        h.healthyServer()
        h.start()
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_d4")))
        h.advance(30.0)
        assertTrue(h.net.calls(Routes.EVENTS).isEmpty())
        h.deliverToken()
        h.advance(10.0)
        assertEquals(listOf("received", "displayed"), events().map { it.getString("type") })
    }

    @Test
    fun test_eventsBeforeInit_bufferedThenSent() {
        h.healthyServer()
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_e5")))
        h.settle()
        h.start()
        h.deliverToken()
        h.advance(10.0)
        assertEquals(listOf("received", "displayed"), events().map { it.getString("type") })
    }

    @Test
    fun test_batchesHoldAtMost50() {
        h.registered()
        h.net.on("POST", Routes.EVENTS) { null }
        for (index in 0 until 60) {
            SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_%04x".format(index))))
        }
        h.advance(1.0)
        val first = h.net.held().single()
        assertEquals(50, first.body?.getJSONArray("events")?.length())
        first.respond(Http.events(50))
        h.advance(6.0)
        val remaining = h.net.calls(Routes.EVENTS).size
        assertTrue(remaining >= 2)
    }

    @Test
    fun test_oldEventsDropped_atFlush() {
        h.registered()
        h.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_f6")))
        h.advance(7.0 * 86_400)
        h.net.on("POST", Routes.EVENTS) { call -> Http.events(call.body?.getJSONArray("events")?.length() ?: 0) }
        h.background()
        h.foreground()
        h.advance(10.0)
        assertTrue(h.capture.has(LogCode.EVENTS_DROPPED))
        assertTrue(h.engine().events.pending.isEmpty())
    }

    @Test
    fun test_allPendingEventsExpired_theRetryOfAnotherOpStillGoesOut() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "3600") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
        h.net.on("PUT", Routes.DEVICE) { Http.empty() }
        val engine = h.engine()
        engine.task {
            engine.recordEvent("msg_e1", "received", h.clock.seconds - 7.0 * 86_400, null)
            engine.eventsFlushRequested = true
        }
        h.settle()
        assertTrue(h.capture.has(LogCode.EVENTS_DROPPED))
        assertTrue(engine.events.pending.isEmpty())
        // Dropping the expired events must not leave the engine without its wake-up: the 429 retry is still sent.
        h.advance(2.0 * 3600)
        assertEquals(2, h.net.calls(Routes.DEVICE).size)
        assertTrue(h.net.calls(Routes.EVENTS).isEmpty())
    }

    @Test
    fun test_workerWhoseOnlyWorkIsExpiredEvents_endsAtOnce() {
        h.registered()
        h.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_e2")))
        h.advance(6.0)
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 7L * 86_400_000L
        next.install()
        next.healthyServer()
        val started = System.nanoTime()
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
        assertTrue(next.capture.has(LogCode.EVENTS_DROPPED))
        assertTrue(next.net.calls(Routes.EVENTS).isEmpty())
        next.close()
    }

    @Test
    fun test_queueOverflow_dropsOldest() {
        h.healthyServer()
        h.start()
        for (index in 0 until 260) {
            SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_%04x".format(index))))
        }
        h.settle()
        // One line for the full queue, with the count of what that event dropped (as on iOS); not one per later event.
        val full = h.capture.of(LogCode.EVENTS_QUEUE_FULL).single()
        assertEquals("1", full.metadata[Meta.COUNT])
        assertTrue(full.message, full.message.startsWith("Event queue full (500); the oldest event was dropped"))
        assertEquals(500, h.engine().events.pending.size)
    }

    @Test
    fun test_eventsClientError_dropsBatch() {
        h.registered()
        h.net.on("POST", Routes.EVENTS) { Http.error(400, "invalid_request") }
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_a7")))
        h.advance(6.0)
        assertTrue(h.capture.has(LogCode.NET_CLIENT_ERROR))
        assertTrue(h.engine().events.pending.isEmpty())
    }

    @Test
    fun test_invalidMessageId_displayedWithoutEvents() {
        h.registered()
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_test_002")))
        h.advance(6.0)
        assertTrue(h.net.calls(Routes.EVENTS).isEmpty())
        assertEquals(1, shadowOf(h.context.getSystemService(android.app.NotificationManager::class.java)).allNotifications.size)
    }

    @Test
    fun test_trackOpened_recordsOpenedForCompatPushRef() {
        h.registered()
        assertTrue(SallaPush.Notifications.trackOpened(JSONObject().put("push_ref", "msg_ab").put("type", "order")))
        assertFalse(SallaPush.Notifications.trackOpened(JSONObject().put("type", "order")))
        assertFalse(SallaPush.Notifications.trackOpened(JSONObject().put("push_ref", "not-ours")))
        assertFalse(SallaPush.Notifications.trackOpened(null))
        h.advance(6.0)
        assertEquals(listOf("opened"), events().map { it.getString("type") })
    }

    @Test
    fun test_optedOut_receivedOnly_notDisplayed() {
        h.registered()
        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_a8")))
        h.advance(6.0)
        assertEquals(listOf("received"), events().map { it.getString("type") })
        assertTrue(h.capture.has(LogCode.NOTIF_SUPPRESSED))
    }
}
