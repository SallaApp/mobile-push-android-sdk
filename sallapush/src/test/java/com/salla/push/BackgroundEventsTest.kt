package com.salla.push

import android.Manifest
import android.app.Application
import android.content.Intent
import com.salla.push.internal.api.Routes
import com.salla.push.internal.click.SallaPushNotificationDismissReceiver
import com.salla.push.internal.click.SallaPushNotificationOpenActivity
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.engine.Engine
import com.salla.push.internal.engine.EngineMode
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.engine.recordEvent
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.storage.EventTime
import com.salla.push.internal.work.FlushWork
import com.salla.push.testing.FakeCall
import com.salla.push.testing.FixedJitter
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Events recorded while the app is not in the foreground also go to WorkManager, and only those. */
class BackgroundEventsTest : SdkTestBase() {

    private val consentRequired = SallaPushConfig.Builder().setRequiresUserConsent(true).build()

    @Before
    fun grantNotifications() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ── A push in a process FCM started ──

    @Test
    fun test_aPushInAProcessFcmStarted_queuesTheFlushWork_atARandomSecondOfThe25AfterIt() {
        h.registered()
        val next = processStartedByFcm()
        next.jitter.value = 0.5
        var savedWhenEnqueued: Boolean? = null
        next.work.onRequest = { savedWhenEnqueued = File(next.appDirectory(), "events.json").let { it.isFile && it.readText().contains("msg_b1") } }
        push(next, "msg_b1")
        assertEquals("the work exists only once its events are on disk", true, savedWhenEnqueued)
        // One work for the burst: `received` and `displayed` share its draw.
        val flush = next.work.of("flush").single()
        assertEquals(13L, flush.delaySeconds)
        assertTrue("no work was known: REPLACE", flush.replace)
        assertFalse(flush.consentRequired)
        assertEquals(Harness.APP_ID to next.key.hostTag, flush.appId to flush.hostTag)
        assertEquals(Routes.DEFAULT_BASE_URL, flush.apiBaseUrl)
        assertEquals(listOf("received", "displayed"), next.engine().events.pending.map { it.type })
        assertTrue(File(next.appDirectory(), "events.json").readText().contains("msg_b1"))
        assertTrue(next.net.calls(Routes.EVENTS).isEmpty())
        next.close()
    }

    @Test
    fun test_theProcessDiesBeforeTheWake_aRunInANewProcessSendsReceivedAndDisplayedInOneBatch() {
        h.registered()
        val next = processStartedByFcm()
        val arrival = next.clock.seconds
        push(next, "msg_b2")
        assertEquals(25L, next.work.of("flush").single().delaySeconds)
        // The process dies before its 5 s wake; WorkManager starts a new one when the work is due.
        val third = processStartedByWorkManager(next, 25.0)
        assertEquals(FlushOutcome.SUCCESS, third.runWorker())
        val sent = sentEvents(third.net.calls(Routes.EVENTS).single())
        assertEquals(listOf("received", "displayed"), sent.map { it.getString("type") })
        assertTrue(sent.all { it.getString("notification_id") == "msg_b2" })
        assertTrue("each event keeps the time it happened", sent.all { it.getString("at") == EventTime.format(arrival) })
        assertTrue(third.engine().events.pending.isEmpty())
        assertEquals(EngineMode.WORKER, third.engine().mode)
        assertTrue(next.net.calls(Routes.EVENTS).isEmpty())
        third.close()
        next.close()
    }

    @Test
    fun test_theWakeSendsFirst_thenTheWorkIsCancelled() {
        h.registered()
        val next = processStartedByFcm()
        push(next, "msg_b3")
        assertEquals(25L, next.work.of("flush").single().delaySeconds)
        next.advance(5.0)
        assertEquals(1, next.net.calls(Routes.EVENTS).size)
        val cancelled = next.work.of("cancelFlush").single()
        assertEquals(Harness.APP_ID to next.key.hostTag, cancelled.appId to cancelled.hostTag)
        assertTrue("the identity work is never cancelled with it", next.work.of("cancel").isEmpty())
        assertNull(next.engine().flushWorkDueAt)
        assertNull(next.engine().eventsWorkDueAt)
        // A run WorkManager started before the cancel reached it finds nothing to send.
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertEquals(1, next.net.calls(Routes.EVENTS).size)
        next.close()
    }

    @Test
    fun test_theWakeCannotReachTheNetwork_theWorkStays_andItsRunEndsWithTheBatchSent() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        push(next, "msg_b4")
        next.advance(5.0)
        assertEquals(1, next.net.calls(Routes.EVENTS).size)
        assertTrue(next.work.of("cancelFlush").isEmpty())
        healthyEvents(next)
        // A frozen process: no timer ran until JobScheduler thawed it for the work, after the wake's backoff. The
        // overdue retry and the run flush the same queue: one request, and the run ends once it is answered.
        next.clock.millis += 20_000
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        val calls = next.net.calls(Routes.EVENTS)
        assertEquals(2, calls.size)
        assertEquals(listOf("received", "displayed"), types(calls.last()))
        assertTrue(next.engine().events.pending.isEmpty())
        assertTrue(next.work.of("cancelFlush").isEmpty())
        next.close()
    }

    // ── The enqueued work: kept within the spread, replaced otherwise ──

    @Test
    fun test_eventsWithinTheSpread_neverMoveTheEnqueuedWork() {
        h.registered()
        val next = processStartedByFcm()
        next.jitter.value = 0.5
        push(next, "msg_b5")
        val first = next.work.of("flush").single()
        next.advance(3.0)
        next.jitter.value = 0.0
        push(next, "msg_b6")
        next.jitter.value = 1.0
        dismiss(next, "msg_b5")
        assertEquals(listOf("received", "displayed", "received", "displayed", "dismissed"), next.engine().events.pending.map { it.type })
        assertEquals(listOf(first), next.work.of("flush"))
        next.close()
    }

    @Test
    fun test_aKnownWorkThatWasDueAlready_isReplaced_soWorkManagersBackoffNeverHoldsTheEvent() {
        h.registered()
        val next = processStartedByFcm()
        // The wake's request never gets an answer (the process froze with it on the wire).
        next.net.on("POST", Routes.EVENTS) { null }
        next.jitter.value = 0.0
        push(next, "msg_b7")
        assertEquals(0L, next.work.of("flush").single().delaySeconds)
        next.advance(40.0)
        assertEquals(1, next.net.held().size)
        next.jitter.value = 1.0
        push(next, "msg_b8")
        val flushes = next.work.of("flush")
        assertEquals(2, flushes.size)
        assertTrue("a work that was due already may sit in WorkManager's backoff: REPLACE", flushes.last().replace)
        assertEquals("the oldest unsent event is 40 s old: due at once", 0L, flushes.last().delaySeconds)
        next.close()
    }

    @Test
    fun test_aFarFollowUpIsReplacedByTheEventsWork_whoseRunEnqueuesItAgain() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "3600") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(listOf(3600L), h.work.of("flush").map { it.delaySeconds })
        h.background()
        assertEquals(1, h.work.of("flush").size)
        h.work.scheduled.clear()
        push(h, "msg_b9")
        val flush = h.work.of("flush").single()
        assertTrue("this run comes before the far follow-up: REPLACE", flush.replace)
        assertEquals(25L, flush.delaySeconds)
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.EVENTS).size)
        assertTrue("the update still waits, so the work stays", h.work.of("cancelFlush").isEmpty())
        h.advance(20.0)
        assertEquals(FlushOutcome.SUCCESS, h.runWorker())
        val followUp = h.work.replacingFlushes().last().delaySeconds
        assertTrue("follow-up $followUp", followUp in 3560L..3600L)
    }

    @Test
    fun test_aClockThatMovedBack_neverPostponesTheWorkPastTheSpread() {
        h.registered()
        // FCM starts a process for a push before the app initialises SallaPush: its events wait in memory.
        val next = processStartedByWorkManager(h, 60.0)
        push(next, "msg_b26")
        // The phone's clock is set an hour back before the app initialises SallaPush, still in the background.
        next.clock.millis -= 3_600_000
        next.init()
        assertEquals(listOf("received", "displayed"), next.engine().events.pending.map { it.type })
        assertEquals(25L, next.work.of("flush").single().delaySeconds)
        next.close()
    }

    @Test
    fun test_whileTheDeviceRoutesAreClosed_theWorkWaitsUntilTheyReopen() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(404, "not_found", message = "Not found") }
        SallaPush.logout()
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.NET_ROUTES_CLOSED))
        val next = processStartedByFcm()
        push(next, "msg_b25")
        // No run could send anything before the routes reopen, a day later: none is started for nothing.
        val delay = next.work.of("flush").single().delaySeconds
        assertTrue("delay $delay", delay in 86_000L..86_400L)
        next.close()
    }

    // ── Which events queue the work ──

    @Test
    fun test_inTheForeground_noWorkIsQueued() {
        h.registered()
        h.work.scheduled.clear()
        push(h, "msg_ba")
        h.advance(6.0)
        assertEquals(listOf("received", "displayed"), types(h.net.calls(Routes.EVENTS).single()))
        assertTrue(h.work.scheduled.isEmpty())
    }

    @Test
    fun test_aDismissalWhileTheAppIsInTheBackground_queuesTheWorkToo() {
        h.registered()
        h.background()
        h.work.scheduled.clear()
        dismiss(h, "msg_bb")
        assertEquals(listOf("dismissed"), h.engine().events.pending.map { it.type })
        val flush = h.work.of("flush").single()
        assertEquals(25L, flush.delaySeconds)
        assertTrue(flush.replace)
    }

    @Test
    fun test_whileConsentIsWithheld_aPushQueuesNoWork() {
        h.registered()
        val next = processStartedByFcm(config = consentRequired)
        // The state is never loaded while consent is withheld: on the main thread the push never waits for it.
        next.main.current = true
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(next.context, Messages.remote(Messages.native(id = "msg_bc"))))
        next.main.current = false
        next.settle()
        assertTrue(next.work.scheduled.isEmpty())
        assertTrue("no engine runs: the events wait in memory", next.core.registry.all().none { it.started })
        // Consent given later in this background process: the events kept in memory are handed over then.
        SallaPush.setConsentGiven(true)
        next.settle()
        assertEquals(listOf("received", "displayed"), next.engine().events.pending.map { it.type })
        val flush = next.work.of("flush").single()
        assertTrue(flush.consentRequired)
        assertEquals(25L, flush.delaySeconds)
        next.close()
    }

    @Test
    fun test_withConsentGiven_theWorkCarriesTheConsentRequirement() {
        h.healthyServer()
        h.start(config = consentRequired)
        SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        val next = processStartedByFcm(config = consentRequired)
        push(next, "msg_bd")
        assertTrue(next.work.of("flush").single().consentRequired)
        next.close()
    }

    @Test
    fun test_whileARunIsAttached_anEventQueuesNothing_andTheRunsFollowUpCoversIt() {
        h.registered()
        h.background()
        h.net.on("PUT", Routes.DEVICE) { null }
        SallaPush.User.setLanguage("en")
        h.advance(1.5)
        assertEquals(1, h.net.held().size)
        var outcome: FlushOutcome? = null
        FlushWork.start(h.context, Harness.APP_ID, Routes.DEFAULT_BASE_URL, false) { outcome = it }
        h.settle()
        assertTrue(h.engine().workerAttached)
        h.work.scheduled.clear()
        push(h, "msg_be")
        assertTrue("the attached run's follow-up covers the event", h.work.of("flush").isEmpty())
        h.net.held().single().respond(Http.empty())
        h.settle()
        assertEquals(FlushOutcome.SUCCESS, outcome)
        assertEquals(5L, h.work.replacingFlushes().single().delaySeconds)
    }

    @Test
    fun test_anEventADeadProcessLeft_makesTheNextWorkDueAtOnce() {
        h.registered()
        val first = processStartedByFcm()
        push(first, "msg_c5")
        // That process dies before anything sent its events; FCM starts another one a minute later.
        val second = processStartedByFcm(from = first, laterSeconds = 60.0)
        assertEquals(2, second.engine().events.pending.size)
        push(second, "msg_c6")
        val flush = second.work.of("flush").single()
        assertEquals(0L, flush.delaySeconds)
        assertTrue(flush.replace)
        first.close()
        second.close()
    }

    @Test
    fun test_aBurstOfEvents_postsOneTask_andEnqueuesOneWork() {
        h.registered()
        h.background()
        h.work.scheduled.clear()
        val engine = h.engine()
        val before = h.engineRunner.pendingCount()
        // Fewer than a batch (50): nothing goes before the 5 s debounce.
        engine.task { repeat(40) { engine.recordEvent("msg_%04x".format(it), "received", h.clock.seconds, null) } }
        // One task for WorkManager, and the 5 s wake.
        assertTrue("pending ${h.engineRunner.pendingCount()}", h.engineRunner.pendingCount() <= before + 2)
        h.settle()
        assertEquals(40, engine.events.pending.size)
        assertTrue(h.net.calls(Routes.EVENTS).isEmpty())
        assertEquals(1, h.work.of("flush").size)
    }

    @Test
    fun test_beforeTheDeviceCanRegister_aBackgroundPushQueuesNoWork() {
        h.healthyServer()
        h.init()
        push(h, "msg_b24")
        assertEquals(listOf("received", "displayed"), h.engine().events.pending.map { it.type })
        assertTrue("no push token yet: nothing a run could send", h.work.scheduled.isEmpty())
    }

    // ── The cancel: only this path's work, once nothing at all is left ──

    @Test
    fun test_onlyTheBackgroundEventsWork_isEverCancelled() {
        h.registered()
        h.background()
        push(h, "msg_bf")
        assertEquals(1, h.work.of("flush").size)
        // The foreground sends the events at once and takes the work back.
        h.foreground()
        assertEquals(1, h.net.calls(Routes.EVENTS).size)
        assertEquals(1, h.work.of("cancelFlush").size)
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        // The background hand-off enqueues its own work for the tag.
        h.background()
        val handOff = h.work.of("flush").last()
        assertEquals(2, h.work.of("flush").size)
        assertTrue(handOff.replace)
        h.net.on("PATCH", Routes.TAGS) { call -> Http.tags(h.tagsAfter(call)) }
        h.advance(30.0)
        assertEquals(mapOf("plan" to "gold"), h.serverTags)
        assertNull("nothing is left for a run to send", h.engine().nextDue(h.clock.seconds, durable = true))
        assertEquals("the hand-off's work is never cancelled", 1, h.work.of("cancelFlush").size)
    }

    @Test
    fun test_aWorkALongRetryTookOver_isNeverCancelled() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.error(429, "rate_limited", retryAfter = "60") }
        push(next, "msg_b21")
        assertEquals(25L, next.work.of("flush").single().delaySeconds)
        // The wake meets a 429 of 60 s: that long retry hands the events to its own work, due at once.
        next.advance(5.0)
        val flushes = next.work.of("flush")
        assertEquals(2, flushes.size)
        assertEquals(0L, flushes.last().delaySeconds)
        healthyEvents(next)
        next.advance(60.0)
        assertEquals(2, next.net.calls(Routes.EVENTS).size)
        assertNull("nothing is left for a run to send", next.engine().nextDue(next.clock.seconds, durable = true))
        assertTrue("that work is the long retry's, not this path's to cancel", next.work.of("cancelFlush").isEmpty())
        next.close()
    }

    @Test
    fun test_aTagTheAppLeftPending_keepsTheWork_andItsRunSendsTheTag() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        // FCM starts a new process: its wake sends the events, and leaves the tag to a run or the foreground.
        val next = processStartedByFcm()
        push(next, "msg_b23")
        next.advance(5.0)
        assertEquals(1, next.net.calls(Routes.EVENTS).size)
        assertTrue(next.net.calls(Routes.TAGS).isEmpty())
        assertTrue("a run would still send the tag: the work stays", next.work.of("cancelFlush").isEmpty())
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertEquals("gold", next.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("plan"))
        next.close()
    }

    @Test
    fun test_theSpreadIsAWholeSecondFrom0To25_eachAsLikely() {
        // Each second takes one 26th of [0, 1): the start, the middle and the end of every 26th map to it.
        for (offset in listOf(0.001, 0.5, 0.999)) {
            assertEquals("offset $offset", (0L..25L).toList(), (0..25).map { Engine.eventsWorkSpreadSeconds(FixedJitter((it + offset) / 26.0)) })
        }
        assertEquals(25L, Engine.eventsWorkSpreadSeconds(FixedJitter(1.0)))
        assertEquals(0L, Engine.eventsWorkSpreadSeconds(FixedJitter(-3.0)))
        assertEquals(25L, Engine.eventsWorkSpreadSeconds(FixedJitter(7.0)))
        assertEquals(0L, Engine.eventsWorkSpreadSeconds(FixedJitter(Double.NaN)))
        assertEquals(25L, Engine.EVENTS_WORK_SPREAD_SECONDS)
    }

    // ── A run where the 5 s wake failed (Android 15+ cut the network, then the freezer stopped the process) ──

    @Test
    fun test_aRunInsideTheFailedWakesBackoff_sendsTheEventsAtOnce() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        push(next, "msg_b16")
        // The wake at 5 s and its retry at 7 s fail; the next retry is at 11 s.
        next.advance(7.0)
        assertEquals(2, next.net.calls(Routes.EVENTS).size)
        healthyEvents(next)
        // JobScheduler thaws the frozen process for the work at 8 s, inside that backoff: no timer ran.
        next.clock.millis += 1_000
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        val calls = next.net.calls(Routes.EVENTS)
        assertEquals(3, calls.size)
        assertEquals(listOf("received", "displayed"), types(calls.last()))
        assertTrue(next.engine().events.pending.isEmpty())
        next.close()
    }

    @Test
    fun test_aRunWhoseRequestFailsAgain_makesOneAttempt_andKeepsTheBackoff() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        push(next, "msg_b22")
        next.advance(7.0)
        assertEquals(2, next.net.calls(Routes.EVENTS).size)
        // The run's request fails too: one attempt, then WorkManager's own backoff, and the events' backoff keeps growing.
        next.clock.millis += 1_000
        assertEquals(FlushOutcome.RETRY, next.runWorker())
        assertEquals(3, next.net.calls(Routes.EVENTS).size)
        assertEquals(3, next.engine().eventsRetry.attempts)
        assertEquals(2, next.engine().events.pending.size)
        next.close()
    }

    @Test
    fun test_aRunAfterSixNetworkFailures_stillSendsTheEvents() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        push(next, "msg_b17")
        // The wake at 5 s and its retries at 7, 11, 19, 35 and 67 s fail: then the events wait for a trigger or a run.
        next.advance(67.0)
        assertEquals(6, next.net.calls(Routes.EVENTS).size)
        assertEquals(
            "POST /devices/{id}/events (network: IOException) failed 6 times; waiting for the next launch, foreground, network change or WorkManager run.",
            next.capture.of(LogCode.NET_RETRY_EXHAUSTED).single().message,
        )
        healthyEvents(next)
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        val calls = next.net.calls(Routes.EVENTS)
        assertEquals(7, calls.size)
        assertEquals(listOf("received", "displayed"), types(calls.last()))
        assertTrue(next.engine().events.pending.isEmpty())
        next.close()
    }

    @Test
    fun test_aRunAfterSixTimeouts_makesOneAttempt_andLeavesNoFollowUpOrWake() {
        h.registered()
        val next = processStartedByFcm()
        // The network works, but the server never answers in time: every request times out.
        next.net.on("POST", Routes.EVENTS) { Http.timeout() }
        push(next, "msg_b27")
        next.advance(67.0)
        assertEquals(6, next.net.calls(Routes.EVENTS).size)
        assertEquals(1, next.capture.count(LogCode.NET_RETRY_EXHAUSTED))
        // The sixth failure handed the events to a work due at once. Its run makes one attempt, which times out too.
        next.work.scheduled.clear()
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertEquals(7, next.net.calls(Routes.EVENTS).size)
        assertEquals("the failures of this process still count", 2, next.capture.count(LogCode.NET_RETRY_EXHAUSTED))
        // No follow-up run (a KEEP while the run is running enqueues nothing) and no wake: the retries end as after
        // six failures without a run, and the events stay queued.
        assertTrue(next.work.replacingFlushes().isEmpty())
        assertNull(next.engine().nextDue(next.clock.seconds))
        next.advance(4 * 3600.0)
        assertEquals(7, next.net.calls(Routes.EVENTS).size)
        assertEquals(2, next.engine().events.pending.size)
        next.close()
    }

    @Test
    fun test_aRunAfterSix5xxAnswers_leavesTheEventsToATrigger() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.error(503, "server_error") }
        push(next, "msg_b32")
        next.advance(67.0)
        assertEquals(6, next.net.calls(Routes.EVENTS).size)
        // The server answered: only events that got no answer go with a run.
        assertEquals(
            "POST /devices/{id}/events (503 server_error, request req-503) failed 6 times; waiting for the next launch, foreground or network change.",
            next.capture.of(LogCode.NET_RETRY_EXHAUSTED).single().message,
        )
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertEquals(6, next.net.calls(Routes.EVENTS).size)
        assertNull(next.engine().nextDue(next.clock.seconds))
        next.advance(4 * 3600.0)
        assertEquals(6, next.net.calls(Routes.EVENTS).size)
        assertEquals(2, next.engine().events.pending.size)
        next.close()
    }

    @Test
    fun test_whilePushServiceMakesTheEventsWait_aNewPushQueuesNoRun() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.error(429, "rate_limited", retryAfter = "3600") }
        push(next, "msg_b18")
        // The wake meets the 429 (a run inside the first 5 s would still find the debounce running: a 5 s follow-up).
        next.advance(5.0)
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertEquals("a run never shortens the server's own wait", 1, next.net.calls(Routes.EVENTS).size)
        val followUp = next.work.replacingFlushes().last().delaySeconds
        assertTrue("follow-up $followUp", followUp in 3595L..3600L)
        next.work.scheduled.clear()
        push(next, "msg_b19")
        assertEquals(4, next.engine().events.pending.size)
        assertTrue(next.work.of("flush").isEmpty())
        next.close()
    }

    @Test
    fun test_whilePushServiceAnswers5xx_aRunKeepsTheWait_andANewPushQueuesNoRun() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.error(503, "server_error", retryAfter = "120") }
        push(next, "msg_b28")
        next.advance(5.0)
        assertEquals(FlushOutcome.SUCCESS, next.runWorker())
        assertEquals("a run never shortens a 5xx wait", 1, next.net.calls(Routes.EVENTS).size)
        val followUp = next.work.replacingFlushes().last().delaySeconds
        assertTrue("follow-up $followUp", followUp in 115L..120L)
        next.work.scheduled.clear()
        push(next, "msg_b29")
        assertEquals(4, next.engine().events.pending.size)
        assertTrue(next.work.of("flush").isEmpty())
        next.close()
    }

    @Test
    fun test_onceA5xxWaitIsOver_aNewPushQueuesTheWorkAgain() {
        h.registered()
        val next = processStartedByFcm()
        next.net.on("POST", Routes.EVENTS) { Http.error(503, "server_error", retryAfter = "60") }
        push(next, "msg_b30")
        next.advance(5.0)
        // The process froze during the 60 s wait, and FCM thaws it for a new push after the wait ended: the overdue
        // retry goes out and gets no answer yet.
        next.net.on("POST", Routes.EVENTS) { null }
        next.clock.millis += 61_000
        next.work.scheduled.clear()
        push(next, "msg_b31")
        assertEquals(1, next.net.held().size)
        val flush = next.work.of("flush").single()
        assertEquals("a wait that is over holds nothing back, and the oldest event is a minute old", 0L, flush.delaySeconds)
        assertTrue(flush.replace)
        next.close()
    }

    // ── A tap ──

    @Test
    fun test_aTapFromTheBackground_queuesNoWork_andTheForegroundSendsOpened() {
        h.registered()
        h.background()
        h.work.scheduled.clear()
        Robolectric.buildActivity(SallaPushNotificationOpenActivity::class.java, Messages.openIntent(h, Messages.native(id = "msg_b20"))).create()
        h.settle()
        assertEquals(listOf("opened"), h.engine().events.pending.map { it.type })
        assertTrue(h.work.scheduled.isEmpty())
        // The tap launches the app: its foreground sends `opened` at once.
        h.foreground()
        assertEquals(listOf("opened"), types(h.net.calls(Routes.EVENTS).single()))
        assertTrue("no flush and no cancelFlush", h.work.scheduled.isEmpty())
    }

    // ── Helpers ──

    private fun push(harness: Harness, id: String) {
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(harness.context, Messages.remote(Messages.native(id = id))))
        harness.settle()
    }

    private fun dismiss(harness: Harness, id: String) {
        SallaPushNotificationDismissReceiver().onReceive(harness.context, Intent().putExtra(NotificationDisplayer.EXTRA_NOTIFICATION_ID, id))
        harness.settle()
    }

    /** A new process on the same phone that FCM started: SallaPush initialised in Application.onCreate, no activity. */
    private fun processStartedByFcm(from: Harness = h, laterSeconds: Double = 60.0, config: SallaPushConfig? = null): Harness =
        copyOf(from, laterSeconds).also { it.init(config = config) }

    /** A new process WorkManager started for its work, in an app that initialises SallaPush later. */
    private fun processStartedByWorkManager(from: Harness, laterSeconds: Double): Harness = copyOf(from, laterSeconds)

    private fun copyOf(from: Harness, laterSeconds: Double): Harness {
        val next = Harness(h.context)
        val storage = File(from.root, "sallapush")
        if (storage.exists()) storage.copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = from.clock.millis + (laterSeconds * 1000.0).toLong()
        next.install()
        next.healthyServer()
        return next
    }

    private fun sentEvents(call: FakeCall): List<JSONObject> {
        val events = call.body?.getJSONArray("events") ?: return emptyList()
        return (0 until events.length()).map { events.getJSONObject(it) }
    }

    private fun types(call: FakeCall): List<String> = sentEvents(call).map { it.getString("type") }

    private fun healthyEvents(harness: Harness) {
        harness.net.on("POST", Routes.EVENTS) { call -> Http.events(call.body?.getJSONArray("events")?.length() ?: 0) }
    }
}
