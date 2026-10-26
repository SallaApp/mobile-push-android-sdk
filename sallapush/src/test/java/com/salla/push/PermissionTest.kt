package com.salla.push

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.permission.SallaPushPermissionActivity
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Permission at 23 and 26 (no runtime permission: the current state is the answer, nothing is ever prompted) and at
 * 34 (the Android 13+ POST_NOTIFICATIONS prompt). Prompt tests run where the prompt exists.
 */
@Config(sdk = [23, 26, 34])
class PermissionTest : SdkTestBase() {

    @Test
    fun test_grantWhileActivityResumed_updatesOptedIn() {
        h.permission = false
        h.registered()
        val changes = ArrayList<Boolean>()
        SallaPush.Notifications.addPermissionObserver { changes.add(it) }
        h.permission = true
        h.background()
        h.foreground()
        h.advance(3.0)
        assertEquals(listOf(true), changes)
        assertTrue(SallaPush.Notifications.permission)
        assertTrue(SallaPush.User.pushSubscription.optedIn)
        assertTrue(h.net.calls(Routes.DEVICE).single().body?.getBoolean("opted_in") ?: false)
    }

    @Test
    fun test_refreshPermission_reReadsNow() {
        h.registered()
        val changes = ArrayList<Boolean>()
        SallaPush.Notifications.addPermissionObserver { changes.add(it) }
        h.permission = false
        SallaPush.Notifications.refreshPermission()
        h.settle()
        assertEquals(listOf(false), changes)
        assertFalse(SallaPush.Notifications.permission)
    }

    @Test
    @Config(sdk = [23, 26])
    fun test_requestPermission_belowApi33_returnsTheCurrentState() {
        h.start()
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(true) { answer = it }
        h.settle()
        assertEquals(true, answer)
        assertFalse(SallaPush.Notifications.canRequestPermission())
        assertNull(nextStarted())
    }

    @Test
    @Config(sdk = [23, 26])
    fun test_optIn_belowApi33_neverPrompts_beforeOrAfterInit() {
        SallaPush.User.pushSubscription.optIn()
        h.start()
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        h.background()
        h.foreground()
        assertNull(nextStarted())
        // No prompt exists below Android 13: nothing is deferred either.
        assertFalse(h.capture.has(LogCode.PERMISSION_PROMPT_DEFERRED))
    }

    @Test
    @Config(sdk = [23, 26])
    fun test_requestPermissionSuspend_belowApi33_resumesWithTheCurrentState() {
        h.init()
        var result: Result<Boolean>? = null
        suspend { SallaPush.Notifications.requestPermissionSuspend(false) }
            .startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        h.settle()
        assertEquals(true, result?.getOrNull())
    }

    @Test
    @Config(sdk = [34])
    fun test_requestPermission_inBackground_isFalse_andWarns() {
        h.init()
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(false) { answer = it }
        h.settle()
        assertEquals(false, answer)
        assertTrue(h.capture.has(LogCode.API_INVALID_ARGUMENT))
    }

    @Test
    @Config(sdk = [34])
    fun test_requestPermission_inForeground_showsThePrompt_andReportsTheAnswer() {
        h.start()
        assertTrue(SallaPush.Notifications.canRequestPermission())
        val answers = ArrayList<Boolean>()
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        val started = shadowOf(h.context as Application).nextStartedActivity
        assertEquals(SallaPushPermissionActivity::class.java.name, started.component?.className)
        assertNull(shadowOf(h.context as Application).nextStartedActivity)
        val activity = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, started).create().get()
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_GRANTED))
        h.settle()
        assertEquals(listOf(true, true), answers)
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(sdk = [34])
    fun test_permanentDenial_withFallback_opensSettings() {
        h.start()
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(true) { answer = it }
        val started = shadowOf(h.context as Application).nextStartedActivity
        val activity = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, started).create().get()
        activity.onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED))
        h.settle()
        assertEquals(false, answer)
        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertFalse(SallaPush.Notifications.canRequestPermission())
    }

    @Test
    @Config(sdk = [34])
    fun test_permanentDenial_isKnownUntilTheProcessEnds_asTheKDocSays() {
        h.start()
        SallaPush.Notifications.requestPermission(false) { }
        val started = shadowOf(h.context as Application).nextStartedActivity
        val activity = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, started).create().get()
        activity.onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED))
        h.settle()
        assertFalse(SallaPush.Notifications.canRequestPermission())
        // Android cannot tell "permanently denied" from "never asked", and nothing persists the answer:
        // a new process may prompt again, and the system then answers at once without showing anything.
        val next = h.relaunch()
        assertTrue(SallaPush.Notifications.canRequestPermission())
        next.close()
    }

    @Test
    @Config(sdk = [34])
    fun test_permissionActivity_destroyedWithoutAnswer_reportsCurrentState() {
        h.start()
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(false) { answer = it }
        val started = shadowOf(h.context as Application).nextStartedActivity
        Robolectric.buildActivity(SallaPushPermissionActivity::class.java, started).create().destroy()
        h.settle()
        assertEquals(false, answer)
    }

    @Test
    @Config(sdk = [34])
    fun test_requestPermissionSuspend_resumesWithTheAnswer() {
        h.init()
        var result: Result<Boolean>? = null
        suspend { SallaPush.Notifications.requestPermissionSuspend(false) }
            .startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        h.settle()
        assertEquals(false, result?.getOrNull())
    }

    @Test
    @Config(sdk = [34])
    fun test_optIn_promptsWhenThePermissionWasNeverRequested() {
        h.registered()
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        assertEquals(SallaPushPermissionActivity::class.java.name, shadowOf(h.context as Application).nextStartedActivity?.component?.className)
    }

    // ── optIn() before the app is in the foreground prompts at the next foreground (OneSignal v5 parity) ──

    @Test
    @Config(sdk = [34])
    fun test_optInBeforeInit_promptsOnceAtTheFirstForeground() {
        SallaPush.User.pushSubscription.optIn()
        h.init()
        assertNull(nextStarted())
        h.foreground()
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
        assertNull(nextStarted())
        h.background()
        h.foreground()
        assertNull(nextStarted())
    }

    @Test
    @Config(sdk = [34])
    fun test_aDeferredOptInPrompt_isSaidOnceAtInfo_perDeferral() {
        SallaPush.User.pushSubscription.optIn()
        SallaPush.User.pushSubscription.optIn()
        val deferred = h.capture.of(LogCode.PERMISSION_PROMPT_DEFERRED).single()
        assertEquals(SallaPushLogLevel.INFO, deferred.code.level)
        assertEquals("registration", deferred.code.category)
        assertEquals(
            "optIn() was called before initWithContext; the notification permission prompt is shown at the next " +
                "foreground, unless the permission is granted or permanently denied by then.",
            deferred.message,
        )
        h.init()
        h.foreground()
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
        // The prompt was shown: optIn() in the background defers a new one, and says so again.
        h.background()
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        val again = h.capture.of(LogCode.PERMISSION_PROMPT_DEFERRED)
        assertEquals(2, again.size)
        assertTrue(again.last().message, again.last().message.startsWith("optIn() was called while the app is not in the foreground; "))
    }

    @Test
    @Config(sdk = [34])
    fun test_aPermissionCallbackThatThrows_isReported_neverThrown() {
        h.init()
        SallaPush.Notifications.requestPermission(false) { throw IllegalStateException("app bug for customer@example.com") }
        h.settle()
        val report = h.capture.of(LogCode.API_CALLBACK_THREW).single()
        assertEquals(SallaPushLogLevel.ERROR, report.code.level)
        assertTrue(report.message, report.message.startsWith("The callback passed to Notifications.requestPermission threw IllegalStateException at com.salla.push.PermissionTest"))
        assertFalse(report.message.contains("customer@example.com"))
    }

    @Test
    @Config(sdk = [34])
    fun test_optInRightAfterInit_promptsOnceAtTheFirstForeground() {
        SallaPush.initWithContext(h.context, com.salla.push.testing.Harness.APP_ID)
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        assertNull(nextStarted())
        h.foreground()
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
        assertNull(nextStarted())
    }

    @Test
    @Config(sdk = [34])
    fun test_optInInActivityOnCreate_beforeOnStart_promptsOnceAtTheFirstForeground() {
        h.init()
        val controller = Robolectric.buildActivity(OptInActivity::class.java).create()
        h.settle()
        assertNull(nextStarted())
        controller.start().resume()
        h.settle()
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
        assertNull(nextStarted())
    }

    @Test
    @Config(sdk = [34])
    fun test_optIn_permissionAlreadyGranted_noPrompt() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SallaPush.User.pushSubscription.optIn()
        h.start()
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        assertNull(nextStarted())
    }

    @Test
    @Config(sdk = [34])
    fun test_optIn_grantedBeforeTheNextForeground_dropsThePendingPrompt() {
        h.init()
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        h.foreground()
        assertNull(nextStarted())
    }

    // ── The foreground state is known before a permission decision ──

    @Test
    @Config(sdk = [34])
    fun test_lateInit_activityAlreadyResumed_requestPermissionShowsThePrompt() {
        Robolectric.buildActivity(Activity::class.java).setup()
        h.processForeground = true
        SallaPush.initWithContext(h.context, com.salla.push.testing.Harness.APP_ID)
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(false) { answer = it }
        h.settle()
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
        assertNull(answer)
        assertFalse(h.capture.records.any { it.message.contains("not in the foreground") })
    }

    @Test
    @Config(sdk = [34])
    fun test_lateInit_thePromptOverTheHostActivity_leavesTheAppInTheForeground() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        h.processForeground = true
        SallaPush.initWithContext(h.context, com.salla.push.testing.Harness.APP_ID)
        SallaPush.Notifications.requestPermission(false) { }
        h.settle()
        // The translucent prompt opens over the host activity, which only pauses, and closes again.
        host.pause()
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prompt.get().onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_GRANTED))
        prompt.pause()
        host.resume()
        prompt.stop().destroy()
        h.advance(2.0)
        assertTrue(h.core.isForeground())
        host.pause().stop()
        h.advance(2.0)
        assertFalse(h.core.isForeground())
    }

    @Test
    @Config(sdk = [34])
    fun test_lateInit_appInTheBackground_requestPermissionAnswersFalse() {
        SallaPush.initWithContext(h.context, com.salla.push.testing.Harness.APP_ID)
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(false) { answer = it }
        h.settle()
        assertEquals(false, answer)
        assertNull(nextStarted())
        assertTrue(h.capture.records.any { it.message.contains("not in the foreground") })
    }

    // ── The permission activity always answers once ──

    @Test
    @Config(sdk = [34])
    fun test_configurationChangeWhileTheDialogShows_answersWithTheRealResult() {
        h.start()
        val answers = ArrayList<Boolean>()
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        val controller = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        controller.recreate()
        h.settle()
        assertTrue(answers.isEmpty())
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller.get().onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_GRANTED))
        h.settle()
        assertEquals(listOf(true), answers)
    }

    @Test
    @Config(sdk = [34])
    fun test_permissionActivityThatNeverStarts_answersTheCurrentState_andLaterRequestsWork() {
        h.start()
        val answers = ArrayList<Boolean>()
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        // startActivity returned, but the activity is never created (a blocked background start).
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
        h.advance(9.0)
        assertTrue(answers.isEmpty())
        h.advance(2.0)
        assertEquals(listOf(false, false), answers)
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
    }

    @Test
    @Config(sdk = [34])
    fun test_anOlderRequestsActivity_cannotAnswerANewerRequest() {
        h.start()
        val answers = ArrayList<Boolean>()
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        val stale = nextStarted()
        h.advance(11.0)
        assertEquals(listOf(false), answers)
        SallaPush.Notifications.requestPermission(false) { answers.add(it) }
        val current = nextStarted()
        // The first request's activity finally starts and is dismissed: the second request keeps waiting.
        Robolectric.buildActivity(SallaPushPermissionActivity::class.java, stale).create().destroy()
        h.settle()
        assertEquals(listOf(false), answers)
        val activity = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, current).create().get()
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_GRANTED))
        h.settle()
        assertEquals(listOf(false, true), answers)
    }

    // ── A denial is permanent only when Android will not show the prompt again ──

    @Test
    @Config(sdk = [34])
    fun test_anInterruptedRequest_isACancellation_theAppCanAskAgain() {
        h.start()
        rationale(false)
        val answers = ArrayList<Boolean>()
        SallaPush.Notifications.requestPermission(true) { answers.add(it) }
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        // Interrupted before its dialog showed (never paused, no time passed): Android passes empty arrays, no answer.
        prompt.get().onRequestPermissionsResult(SallaPushPermissionActivity.REQUEST_CODE, emptyArray(), IntArray(0))
        h.settle()
        assertEquals(listOf(false), answers)
        assertTrue(prompt.get().isFinishing)
        assertFalse(settingsOpened())
        assertTrue(SallaPush.Notifications.canRequestPermission())
        SallaPush.User.pushSubscription.optIn()
        h.settle()
        assertEquals(SallaPushPermissionActivity::class.java.name, nextStarted()?.component?.className)
    }

    @Test
    @Config(sdk = [34])
    fun test_aDismissedFirstPrompt_canBeShownAgain_andNeverOpensTheSettings() {
        h.start()
        rationale(false)
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(true) { answer = it }
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        // The user tapped outside the dialog: Android reports a denial and the rationale flag stays false.
        deny(prompt, rationaleAfter = false, afterMillis = 2_000)
        assertEquals(false, answer)
        assertFalse(settingsOpened())
        assertTrue(SallaPush.Notifications.canRequestPermission())
    }

    @Test
    @Config(sdk = [34])
    fun test_aFirstExplicitDenial_canBeShownAgain_andNeverOpensTheSettings() {
        h.start()
        rationale(false)
        SallaPush.Notifications.requestPermission(true) { }
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        deny(prompt, rationaleAfter = true, afterMillis = 2_000)
        assertFalse(settingsOpened())
        assertTrue(SallaPush.Notifications.canRequestPermission())
    }

    @Test
    @Config(sdk = [34])
    fun test_aSecondExplicitDenial_isPermanent_andOpensTheSettings() {
        h.start()
        rationale(true)
        var answer: Boolean? = null
        SallaPush.Notifications.requestPermission(true) { answer = it }
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        deny(prompt, rationaleAfter = false, afterMillis = 2_000)
        assertEquals(false, answer)
        assertTrue(settingsOpened())
        assertFalse(SallaPush.Notifications.canRequestPermission())
    }

    @Test
    @Config(sdk = [34])
    fun test_aDenialFasterThanAPersonCanAnswer_isPermanent() {
        h.start()
        rationale(false)
        SallaPush.Notifications.requestPermission(true) { }
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        // Android answered without showing its dialog: the activity paused for a moment only.
        deny(prompt, rationaleAfter = false, afterMillis = SallaPushPermissionActivity.MIN_HUMAN_ANSWER_MILLIS - 400)
        assertTrue(settingsOpened())
        assertFalse(SallaPush.Notifications.canRequestPermission())
    }

    @Test
    @Config(sdk = [34])
    fun test_theRationaleSeenBeforeThePrompt_survivesAConfigurationChange() {
        h.start()
        rationale(true)
        SallaPush.Notifications.requestPermission(true) { }
        val prompt = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, nextStarted()).setup()
        // Rotated while the dialog shows: the new instance does not request again, so it must keep what was seen.
        prompt.recreate()
        deny(prompt, rationaleAfter = false, afterMillis = 2_000)
        assertTrue(settingsOpened())
        assertFalse(SallaPush.Notifications.canRequestPermission())
    }

    /** Whether the app's notification settings were opened (Robolectric also records the system prompt's intent). */
    private fun settingsOpened(): Boolean {
        val started = generateSequence { nextStarted() }.toList()
        return started.any { it.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS }
    }

    private fun rationale(show: Boolean) {
        shadowOf(h.context.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, show)
    }

    /** The system dialog covers the prompt activity for [afterMillis], then Android reports a denial. */
    private fun deny(prompt: ActivityController<SallaPushPermissionActivity>, rationaleAfter: Boolean, afterMillis: Long) {
        prompt.pause()
        h.clock.millis += afterMillis
        rationale(rationaleAfter)
        prompt.get().onRequestPermissionsResult(
            SallaPushPermissionActivity.REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        h.settle()
    }

    private fun nextStarted(): Intent? = shadowOf(h.context as Application).nextStartedActivity

    /** An activity whose onCreate calls optIn() before it is started. */
    class OptInActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            SallaPush.User.pushSubscription.optIn()
        }
    }
}
