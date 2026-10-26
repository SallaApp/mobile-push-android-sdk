package com.salla.push

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.ProgressUpdater
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.salla.push.internal.api.Routes
import com.salla.push.internal.click.SallaPushNotificationDismissReceiver
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.engine.Engine
import com.salla.push.internal.engine.EngineMode
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.engine.IdentityOpKind
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.work.FlushWork
import com.salla.push.internal.work.FlushWorker
import com.salla.push.internal.work.UniqueWorkQueue
import com.salla.push.internal.work.WorkManagerScheduler
import com.salla.push.internal.work.WorkResultFuture
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** The worker body through its doWork delegate, and the unique work the SDK asks WorkManager for. */
class WorkerTest : SdkTestBase() {

    private class Enqueued(val name: String, val policy: ExistingWorkPolicy, val request: OneTimeWorkRequest)

    /** Records what the scheduler asks WorkManager for; [failing] makes every call throw like a missing WorkManager. */
    private class RecordingQueue(private val failing: Boolean = false) : UniqueWorkQueue {
        val enqueued = ArrayList<Enqueued>()
        val cancelled = ArrayList<String>()

        override fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            if (failing) throw IllegalStateException("not initialised")
            enqueued.add(Enqueued(name, policy, request))
        }

        override fun cancel(name: String) {
            if (failing) throw IllegalStateException("not initialised")
            cancelled.add(name)
        }
    }

    @Test
    fun test_flushWork_keepOrReplace_identityWorkReplaces_consentRequiredTravelsWithTheWork() {
        val queue = RecordingQueue()
        val scheduler = WorkManagerScheduler(h.logger, queue)
        scheduler.scheduleFlush("st_1_android", "abcd1234", Routes.DEFAULT_BASE_URL, 90, replace = false, consentRequired = false)
        scheduler.scheduleIdentity("st_1_android", "abcd1234", Routes.DEFAULT_BASE_URL, 0, replace = true, consentRequired = true)
        scheduler.scheduleFlush("st_1_android", "abcd1234", Routes.DEFAULT_BASE_URL, 3_600, replace = true, consentRequired = true)

        val flush = queue.enqueued[0]
        assertEquals("sallapush.flush.st_1_android.abcd1234", flush.name)
        assertEquals(ExistingWorkPolicy.KEEP, flush.policy)
        assertEquals(TimeUnit.SECONDS.toMillis(90), flush.request.workSpec.initialDelay)
        assertFalse(flush.request.workSpec.input.getBoolean(FlushWorker.KEY_CONSENT_REQUIRED, true))
        val identity = queue.enqueued[1]
        assertEquals("sallapush.identity.st_1_android.abcd1234", identity.name)
        assertEquals(ExistingWorkPolicy.REPLACE, identity.policy)
        assertEquals(0L, identity.request.workSpec.initialDelay)
        assertTrue(identity.request.workSpec.input.getBoolean(FlushWorker.KEY_CONSENT_REQUIRED, false))
        // A follow-up is enqueued while its own run is still RUNNING: KEEP would keep that run and drop the follow-up.
        val replacing = queue.enqueued[2]
        assertEquals("sallapush.flush.st_1_android.abcd1234", replacing.name)
        assertEquals(ExistingWorkPolicy.REPLACE, replacing.policy)
        assertEquals(TimeUnit.SECONDS.toMillis(3_600), replacing.request.workSpec.initialDelay)
        assertTrue(replacing.request.workSpec.input.getBoolean(FlushWorker.KEY_CONSENT_REQUIRED, false))

        for (work in queue.enqueued) {
            val spec = work.request.workSpec
            assertEquals(FlushWorker::class.java.name, spec.workerClassName)
            assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
            assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
            assertFalse(spec.expedited)
            assertEquals("st_1_android", spec.input.getString(FlushWorker.KEY_APP_ID))
            assertEquals(Routes.DEFAULT_BASE_URL, spec.input.getString(FlushWorker.KEY_API_BASE_URL))
        }

        scheduler.cancel("st_1_android", "abcd1234")
        assertEquals(listOf("sallapush.flush.st_1_android.abcd1234", "sallapush.identity.st_1_android.abcd1234"), queue.cancelled)
        assertFalse(h.capture.has(LogCode.WORK_UNAVAILABLE))

        // While a run is attached to the engine, the identity work is kept: a running run is never cancelled.
        scheduler.scheduleIdentity("st_1_android", "abcd1234", Routes.DEFAULT_BASE_URL, 0, replace = false, consentRequired = false)
        assertEquals(ExistingWorkPolicy.KEEP, queue.enqueued.last().policy)
        assertEquals("sallapush.identity.st_1_android.abcd1234", queue.enqueued.last().name)
    }

    @Test
    fun test_workManagerNotInitialised_warnsOnce_noThrow() {
        val scheduler = WorkManagerScheduler(h.context, h.logger)
        scheduler.scheduleFlush(Harness.APP_ID, "abcd1234", Routes.DEFAULT_BASE_URL, 60, replace = false, consentRequired = false)
        scheduler.scheduleIdentity(Harness.APP_ID, "abcd1234", Routes.DEFAULT_BASE_URL, 0, replace = true, consentRequired = false)
        scheduler.cancel(Harness.APP_ID, "abcd1234")
        assertEquals(1, h.capture.count(LogCode.WORK_UNAVAILABLE))
    }

    @Test
    fun test_enqueueFailing_warnsOnce() {
        val scheduler = WorkManagerScheduler(h.logger, RecordingQueue(failing = true))
        repeat(3) { scheduler.scheduleFlush(Harness.APP_ID, "abcd1234", Routes.DEFAULT_BASE_URL, 60, replace = true, consentRequired = false) }
        scheduler.cancel(Harness.APP_ID, "abcd1234")
        assertEquals(1, h.capture.count(LogCode.WORK_UNAVAILABLE))
    }

    @Test
    fun test_cancelFlush_cancelsTheFlushWorkAlone_andNeverThrows() {
        val queue = RecordingQueue()
        WorkManagerScheduler(h.logger, queue).cancelFlush("st_1_android", "abcd1234")
        assertEquals(listOf("sallapush.flush.st_1_android.abcd1234"), queue.cancelled)
        assertTrue(queue.enqueued.isEmpty())
        // Without a usable WorkManager nothing was enqueued either: no throw, and no work.unavailable of its own.
        WorkManagerScheduler(h.logger, RecordingQueue(failing = true)).cancelFlush("st_1_android", "abcd1234")
        WorkManagerScheduler(h.context, h.logger).cancelFlush(Harness.APP_ID, "abcd1234")
        assertFalse(h.capture.has(LogCode.WORK_UNAVAILABLE))
    }

    @Test
    fun test_nothingDue_success() {
        h.registered()
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
    }

    @Test
    fun test_opsRemainWithAFutureRetry_retry() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(503, "server_error") }
        SallaPush.logout()
        h.advance(2.0)
        assertEquals(FlushOutcome.RETRY, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
    }

    @Test
    fun test_freshProcess_oneRunFlushesEverythingDue() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.EVENTS) { Http.networkFailure() }
        SallaPushNotificationDismissReceiver().onReceive(h.context, Intent().putExtra(NotificationDisplayer.EXTRA_NOTIFICATION_ID, "msg_e5"))
        h.advance(6.0)
        h.net.on("POST", Routes.UNLINK) { null }
        SallaPush.logout()
        h.advance(1.0)

        val next = freshProcess()
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        assertEquals(1, next.net.calls(Routes.EVENTS).size)
        assertEquals(0, next.net.calls(Routes.REGISTER).size)
        assertEquals(0, next.net.calls(Routes.DEVICE).size)
        assertEquals(EngineMode.WORKER, next.engine().mode)
        assertFalse(next.identityJson().contains("\"kind\":\"logout\""))
        next.close()
    }

    @Test
    fun test_freshProcess_withoutInput_usesLastInit() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { null }
        SallaPush.logout()
        h.advance(1.0)
        val next = freshProcess()
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, null, null))
        assertEquals(1, next.net.calls(Routes.UNLINK).size)
        next.close()
    }

    @Test
    fun test_freshProcess_nothingStored_success() {
        val next = freshProcess()
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, null, null))
        assertTrue(next.net.calls.isEmpty())
        assertTrue(next.core.registry.all().isEmpty())
        next.close()
    }

    @Test
    fun test_workerThenAppInit_promotesTheSameEngine() {
        h.registered()
        val next = freshProcess()
        runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL)
        val engine = next.engine()
        assertEquals(EngineMode.WORKER, engine.mode)
        next.init()
        assertEquals(1, next.core.registry.all().size)
        assertTrue(engine === next.engine())
        assertEquals(EngineMode.MAIN, engine.mode)
        next.close()
    }

    // ── A run in a process the app has not initialised leaves the identity to the app's engine ──

    @Test
    fun test_aRunBeforeInit_neverDecidesAnUnknownIdentity_andTheAppsLaunchLoginKeepsTheLinkAndTheTags() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("tier", "gold")
        h.advance(15.0)
        assertEquals(mapOf("tier" to "gold"), h.serverTags)
        SallaPush.User.setLanguage("en")
        h.settle()
        // identity.json is lost; WorkManager runs the pending update in a process the app has not initialised (it
        // initialises SallaPush lazily).
        File(h.appDirectory(), "identity.json").delete()
        val next = freshProcess(laterSeconds = 60.0)
        next.serverTags.putAll(h.serverTags)
        runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL)
        next.advance(60.0)
        assertEquals(listOf(Routes.DEVICE), next.net.calls.map { it.route })
        assertEquals(Harness.CUSTOMER_ID, next.serverExternalId)
        assertEquals(mapOf("tier" to "gold"), next.serverTags)
        // The app initialises later and signs the customer in, as at every launch: the link and the tags stay.
        next.init()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        next.advance(30.0)
        assertTrue(next.net.calls(Routes.UNLINK).isEmpty())
        assertEquals(1, next.net.calls(Routes.LINK).size)
        assertEquals(Harness.CUSTOMER_ID, next.serverExternalId)
        assertEquals(mapOf("tier" to "gold"), next.serverTags)
        assertEquals("1001", SallaPush.User.externalId)
        next.close()
    }

    @Test
    fun test_aRunBeforeInit_leavesALoginWithoutItsTokenToTheAppsEngine_andSendsNoTagBeforeIt() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        // B's login is refused with a 503 (it applies nothing), B sets a tag, and the process dies.
        h.net.on("POST", Routes.LINK) { Http.error(503, "server_error") }
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        SallaPush.User.addTag("b_pref", "1")
        h.advance(3.0)
        val next = freshProcess(laterSeconds = 120.0)
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        next.advance(60.0)
        assertTrue(next.net.calls(Routes.LINK).isEmpty())
        assertTrue(next.net.calls(Routes.TAGS).isEmpty())
        assertFalse(next.capture.has(LogCode.IDENTITY_TOKEN_NEEDED))
        assertEquals(1, next.engine().identity.ops.count { it.kind == IdentityOpKind.LOGIN })
        // The app initialises in its first activity and signs B in: B's login goes first, then B's tag.
        next.init()
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        next.foreground()
        next.advance(20.0)
        assertEquals(listOf(Routes.LINK, Routes.TAGS), next.net.calls.map { it.route }.filter { it == Routes.LINK || it == Routes.TAGS })
        assertEquals(mapOf("b_pref" to "1"), next.serverTags)
        next.close()
    }

    @Test
    fun test_consentWithdrawn_workerDoesNothing() {
        h.registered()
        File(h.root, "sallapush/consent.json").writeText("{\"v\":1,\"given\":false}")
        val next = freshProcess()
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertTrue(next.net.calls.isEmpty())
        next.close()
    }

    @Test
    fun test_logoutBehindALongBackoff_identityWorkWaitsOnlyForTheLogout() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PATCH", Routes.TAGS) { Http.error(429, "rate_limited", retryAfter = "3600") }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(listOf(3600L), h.work.of("flush").map { it.delaySeconds })
        h.work.scheduled.clear()
        h.net.on("POST", Routes.UNLINK) { null }
        SallaPush.logout()
        h.settle()
        // The head op's remaining wait: the 1 s enqueue debounce, never the general work's backoff.
        val identity = h.work.of("identity").single()
        assertEquals(Engine.DEBOUNCE_SECONDS.toLong(), identity.delaySeconds)
        assertEquals(Harness.APP_ID, identity.appId)
        assertEquals(h.key.hostTag, identity.hostTag)
        assertEquals(Routes.DEFAULT_BASE_URL, identity.apiBaseUrl)
    }

    @Test
    fun test_identityWork_followsTheHeadOpsRetry() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(429, "rate_limited", retryAfter = "300") }
        SallaPush.logout()
        h.advance(2.0)
        h.work.scheduled.clear()
        h.background()
        val delay = h.work.of("identity").single().delaySeconds
        assertTrue("delay $delay", delay in 295L..300L)
    }

    @Test
    fun test_backgroundWithPendingOps_enqueuesFlushWork() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        h.work.scheduled.clear()
        h.background()
        assertTrue(h.work.of("flush").isNotEmpty())
    }

    @Test
    fun test_backgroundWithNothingPending_enqueuesNothing() {
        h.registered()
        h.work.scheduled.clear()
        h.background()
        assertTrue(h.work.scheduled.isEmpty())
    }

    @Test
    fun test_retryMoreThan30sAhead_enqueuesFlushWorkAtTheRetryTime() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "120") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.NET_RATE_LIMITED))
        assertEquals(listOf(120L), h.work.of("flush").map { it.delaySeconds })
    }

    // ── A worker continues the work the app left pending ──

    @Test
    fun test_aWorkerBeforeTheBackoffEnds_retries_thenSendsAtTheNextRun() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        h.background()
        val next = freshProcess()
        assertEquals(FlushOutcome.RETRY, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertTrue(next.net.calls(Routes.TAGS).isEmpty())
        next.advance(30.0)
        next.net.clear()
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, next.net.calls(Routes.TAGS).size)
        next.close()
    }

    @Test
    fun test_offlineTag_background_freshProcessWorkerSendsTheTags() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("add_to_cart", "1790000000000")
        h.advance(11.0)
        h.background()
        val flush = h.work.of("flush").last()

        // WorkManager starts the process when the work is due and the network is back.
        val next = freshProcess(laterSeconds = flush.delaySeconds + 60.0)
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals("1790000000000", next.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("add_to_cart"))
        assertFalse(next.stateJson().contains("\"kind\":\"tags\""))
        next.close()
    }

    @Test
    fun test_offlineTag_freshProcessInitialisedInApplicationOnCreate_workerSendsTheTags() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("add_to_cart", "1790000000000")
        h.advance(11.0)
        h.background()

        val next = freshProcess()
        // WorkManager started the process: Application.onCreate initialises SallaPush, no activity starts.
        next.init()
        next.advance(30.0)
        assertTrue(next.engine().backgroundLaunch)
        assertTrue(next.net.calls(Routes.TAGS).isEmpty())
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, next.net.calls(Routes.TAGS).size)
        next.close()
    }

    @Test
    fun test_pendingUpdate_freshProcessWorkerSendsIt() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.networkFailure() }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.background()
        val flush = h.work.of("flush").last()

        val next = freshProcess(laterSeconds = flush.delaySeconds + 60.0)
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals("en", next.net.calls(Routes.DEVICE).single().body?.getString("language"))
        next.close()
    }

    @Test
    fun test_failedRegistration_processDeath_workerRegisters() {
        h.healthyServer()
        h.net.on("POST", Routes.REGISTER) { Http.networkFailure() }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertTrue(SallaPush.User.pushSubscription.id == null)
        h.background()

        val next = freshProcess()
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(Harness.FCM_TOKEN, next.net.calls(Routes.REGISTER).single().body?.getString("push_token"))
        assertTrue(next.stateJson().contains(Http.DEVICE_ID))
        next.close()
    }

    @Test
    fun test_processStartedByFcm_stillSendsNoTagsUntilTheForeground() {
        h.registered()
        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        val next = freshProcess()
        next.init()
        next.advance(120.0)
        assertTrue(next.net.calls(Routes.TAGS).isEmpty())
        next.foreground()
        next.advance(12.0)
        assertEquals(1, next.net.calls(Routes.TAGS).size)
        next.close()
    }

    // ── How a run ends: dormant waits never make WorkManager wake the app on its own backoff ──

    @Test
    fun test_unknownApp_dormant_workerSucceeds_withAFollowUpAtTheEndOfTheWait() {
        h.net.on("POST", Routes.REGISTER) { Http.error(404, "unknown_app") }
        h.start()
        h.deliverToken()
        h.advance(2.0)
        assertTrue(h.capture.has(LogCode.REGISTER_DORMANT))
        h.work.scheduled.clear()
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertDailyFollowUp()
    }

    @Test
    fun test_routesClosed_workerSucceeds_withAFollowUpAtTheEndOfTheWait() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(404, "not_found", message = "Not found") }
        SallaPush.logout()
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.NET_ROUTES_CLOSED))
        h.work.scheduled.clear()
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertDailyFollowUp()
    }

    @Test
    fun test_identityOpDormantAfterA4xx_workerSucceeds_withAFollowUpAtTheEndOfTheWait() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.error(400, "invalid_request") }
        SallaPush.logout()
        h.advance(3.0)
        assertTrue(h.capture.has(LogCode.IDENTITY_CLIENT_ERROR))
        h.work.scheduled.clear()
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertDailyFollowUp()
    }

    @Test
    fun test_retryFarAhead_workerSucceeds_withAFollowUpAtTheRetryTime() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "600") }
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        h.work.scheduled.clear()
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        val followUp = h.work.replacingFlushes().single()
        assertTrue("delay ${followUp.delaySeconds}", followUp.delaySeconds in 595L..600L)
    }

    @Test
    fun test_logoutConfirm_isNeverSentEarlyByAWorkerFlush() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.timeout() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(1.5)
        SallaPush.logout()
        h.advance(2.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        h.advance(5.0)
        h.work.scheduled.clear()
        // The confirmation's minute is deliberate: a worker 5 s later does not send it.
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        val followUp = h.work.replacingFlushes().single()
        assertTrue("delay ${followUp.delaySeconds}", followUp.delaySeconds in 50L..55L)
        h.advance(45.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        h.advance(10.0)
        assertEquals(2, h.net.calls(Routes.UNLINK).size)
    }

    // ── The flush work is due when the first pending request can go out ──

    @Test
    fun test_dormantUpdate_plusAPendingTagAtBackground_flushWorkAtOnce_replacingTheFarFollowUp() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(400, "invalid_request") }
        SallaPush.User.setLanguage("en")
        h.advance(2.0)
        // A run while the update is dormant for 24 h ends with a follow-up at the end of that wait.
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertTrue(h.work.replacingFlushes().single().delaySeconds > 86_000L)
        h.work.scheduled.clear()

        h.net.on("PATCH", Routes.TAGS) { Http.networkFailure() }
        SallaPush.User.addTag("cart", "1")
        h.background()
        // The tag is due now: the work runs as soon as the network allows, not when the update's wait ends.
        val flush = h.work.of("flush").single()
        assertEquals(0L, flush.delaySeconds)
        assertTrue("an earlier run replaces the far follow-up (KEEP would keep it)", flush.replace)

        val next = freshProcess(laterSeconds = 60.0)
        assertEquals(FlushOutcome.SUCCESS, runWorker(next, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        assertEquals("1", next.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.getString("cart"))
        assertTrue(next.net.calls(Routes.DEVICE).isEmpty())
        next.close()
    }

    @Test
    fun test_aLaterDueTime_keepsTheEarlierEnqueuedWork_noSecondWork() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "600") }
        SallaPush.User.setLanguage("en")
        h.advance(2.0)
        assertEquals(listOf(600L), h.work.of("flush").map { it.delaySeconds })
        // Another op backing off for longer changes nothing: the enqueued run comes first anyway.
        h.net.on("PATCH", Routes.TAGS) { Http.error(429, "rate_limited", retryAfter = "3600") }
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(1, h.work.of("flush").size)
        // Background with only those waits: still nothing earlier to enqueue.
        h.background()
        assertEquals(1, h.work.of("flush").size)
    }

    @Test
    fun test_aWorkerRunningInThisProcess_isNeverReplaced() {
        h.registered()
        h.net.on("PUT", Routes.DEVICE) { Http.error(429, "rate_limited", retryAfter = "60") }
        h.net.on("PATCH", Routes.TAGS) { Http.error(429, "rate_limited", retryAfter = "120") }
        SallaPush.User.setLanguage("en")
        SallaPush.User.addTag("plan", "gold")
        h.advance(2.0)
        h.work.scheduled.clear()
        // The run sends the tags (flushOnce makes them due), which back off 120 s while the run is attached.
        assertEquals(FlushOutcome.SUCCESS, runWorker(h, Harness.APP_ID, Routes.DEFAULT_BASE_URL))
        val duringTheRun = h.work.of("flush").filter { !it.replace }
        assertTrue("the request made while the run was attached is KEEP", duringTheRun.isNotEmpty())
        assertEquals(1, h.work.replacingFlushes().size)
    }

    private fun assertDailyFollowUp() {
        val followUps = h.work.replacingFlushes()
        assertEquals(1, followUps.size)
        assertTrue("delay ${followUps.single().delaySeconds}", followUps.single().delaySeconds in 86_000L..86_400L)
        assertEquals(Harness.APP_ID, followUps.single().appId)
    }

    /** Runs the worker body on its own thread (it blocks until the engine answers) while this thread drives the engine. */
    private fun runWorker(harness: Harness, appId: String?, baseUrl: String?): FlushOutcome = harness.runWorker(appId, baseUrl)

    /** A process started by WorkManager [laterSeconds] after this one: same storage and server, the app not initialised. */
    private fun freshProcess(laterSeconds: Double = 0.0): Harness {
        val next = Harness(h.context)
        val storage = File(h.root, "sallapush")
        if (storage.exists()) storage.copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + (laterSeconds * 1000.0).toLong()
        next.serverExternalId = h.serverExternalId
        next.install()
        next.healthyServer()
        return next
    }

    // ── A ListenableWorker: no WorkManager thread waits while requests are on the wire ──

    /** A registered device whose login request the server holds. */
    private fun loginOnTheWire() {
        h.registered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        assertEquals(1, h.net.held().size)
    }

    @Test
    fun test_aRun_returnsAtOnce_whileARequestIsOnTheWire_andEndsWhenTheEngineSettles() {
        loginOnTheWire()
        var outcome: FlushOutcome? = null
        // Called on this thread like WorkManager's main-thread startWork: it only posts to the engine thread.
        FlushWork.start(h.context, Harness.APP_ID, Routes.DEFAULT_BASE_URL, false) { outcome = it }
        h.settle()
        assertNull(outcome)
        assertTrue(h.engine().workerAttached)
        h.net.held().single().respond(Http.empty())
        h.settle()
        assertEquals(FlushOutcome.SUCCESS, outcome)
        assertFalse(h.engine().workerAttached)
    }

    @Test
    fun test_aStoppedRun_leavesTheEngineAtOnce_andIsAnsweredOnce() {
        loginOnTheWire()
        val outcomes = ArrayList<FlushOutcome>()
        val run = FlushWork.start(h.context, Harness.APP_ID, Routes.DEFAULT_BASE_URL, false) { outcomes.add(it) }
        h.settle()
        assertTrue(h.engine().workerAttached)
        // WorkManager stopped it (cancelled, replaced by REPLACE, constraints lost, its own time limit).
        run.cancel()
        h.settle()
        assertFalse(h.engine().workerAttached)
        assertEquals(listOf(FlushOutcome.SUCCESS), outcomes)
        // The engine's own work goes on without it.
        h.net.held().single().respond(Http.empty())
        h.settle()
        assertEquals("1001", SallaPush.User.externalId)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun test_aRunWaitsAtMostFiveMinutes_thenRetries_andLeavesTheEngine() {
        loginOnTheWire()
        var outcome: FlushOutcome? = null
        FlushWork.start(h.context, Harness.APP_ID, Routes.DEFAULT_BASE_URL, false) { outcome = it }
        h.settle()
        h.advance(299.0)
        assertNull(outcome)
        assertTrue(h.engine().workerAttached)
        h.advance(2.0)
        assertEquals(FlushOutcome.RETRY, outcome)
        assertFalse(h.engine().workerAttached)
    }

    @Test
    fun test_identityWork_isKeptWhileARunIsAttached_andReplacedOtherwise() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("PUT", Routes.DEVICE) { null }
        SallaPush.User.setLanguage("en")
        h.advance(1.5)
        assertEquals(1, h.net.held().size)
        FlushWork.start(h.context, Harness.APP_ID, Routes.DEFAULT_BASE_URL, false) { }
        h.settle()
        assertTrue(h.engine().workerAttached)
        SallaPush.logout()
        h.settle()
        assertFalse("KEEP while a run is attached", h.work.of("identity").last().replace)
        h.net.held().single().respond(Http.empty())
        h.settle()
        assertFalse(h.engine().workerAttached)
        SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2)
        h.settle()
        assertTrue("REPLACE when no run is attached", h.work.of("identity").last().replace)
    }

    @Test
    fun test_flushWorker_startWork_returnsAFutureTheEngineCompletes_andOnStoppedReleasesTheRun() {
        loginOnTheWire()
        val stopped = FlushWorker(h.context, workerParameters())
        val stoppedFuture = stopped.startWork()
        h.settle()
        assertFalse(stoppedFuture.isDone)
        assertTrue(h.engine().workerAttached)
        stopped.onStopped()
        h.settle()
        assertFalse(h.engine().workerAttached)

        val worker = FlushWorker(h.context, workerParameters())
        val future = worker.startWork()
        h.settle()
        assertFalse(future.isDone)
        h.net.held().single().respond(Http.empty())
        h.settle()
        assertTrue(future.isDone)
        assertEquals(ListenableWorker.Result.success(), future.get())
        assertFalse(h.engine().workerAttached)
    }

    @Test
    fun test_aWorkerStoppedBeforeItsRunStarted_neverAttaches_andShortensNoWait() {
        h.registered()
        SallaPush.User.addTag("plan", "gold")
        h.settle()
        val worker = FlushWorker(h.context, workerParameters())
        // WorkManager stopped the work before startWork ran (onStopped then found no run to release).
        worker.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
        val future = worker.startWork()
        h.settle()
        assertEquals(ListenableWorker.Result.success(), future.get(1, TimeUnit.SECONDS))
        assertFalse(h.engine().workerAttached)
        // A run makes the enqueue debounces ready at once; this one never ran, so the tag keeps its 10 s debounce.
        assertTrue(h.net.calls(Routes.TAGS).isEmpty())
        h.advance(10.5)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_workResultFuture_isDoneOnce_andRunsEachListenerOnce() {
        val future = WorkResultFuture<String>()
        val heard = ArrayList<String>()
        future.addListener(Runnable { heard.add("before") }, Executor { it.run() })
        assertTrue(future.complete("ok"))
        assertFalse(future.complete("again"))
        assertFalse(future.cancel(true))
        future.addListener(Runnable { heard.add("after") }, Executor { it.run() })
        assertEquals("ok", future.get())
        assertEquals("ok", future.get(1, TimeUnit.MILLISECONDS))
        assertEquals(listOf("before", "after"), heard)

        val cancelled = WorkResultFuture<String>()
        assertTrue(cancelled.cancel(false))
        assertTrue(cancelled.isCancelled)
        assertFalse(cancelled.complete("late"))
        assertTrue(runCatching { cancelled.get() }.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    /** WorkerParameters for a FlushWorker of this harness's app, with minimal WorkManager plumbing. */
    private fun workerParameters(): WorkerParameters {
        val direct = Executor { it.run() }
        val serial = object : SerialExecutor {
            override fun hasPendingTasks(): Boolean = false

            override fun execute(command: Runnable) = command.run()
        }
        val tasks = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = direct

            override fun getSerialTaskExecutor(): SerialExecutor = serial
        }
        val input = Data.Builder()
            .putString(FlushWorker.KEY_APP_ID, Harness.APP_ID)
            .putString(FlushWorker.KEY_API_BASE_URL, Routes.DEFAULT_BASE_URL)
            .build()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(
            UUID.randomUUID(), input, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0, direct, tasks, factory,
            ProgressUpdater { _, _, _ -> WorkResultFuture<Void>() },
            ForegroundUpdater { _, _, _ -> WorkResultFuture<Void>() },
        )
    }
}
