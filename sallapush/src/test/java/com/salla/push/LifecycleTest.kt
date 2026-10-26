package com.salla.push

import android.app.Activity
import android.os.PowerManager
import com.salla.push.internal.lifecycle.AppLifecycle
import com.salla.push.internal.lifecycle.ForegroundListener
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The foreground tracker: activity callbacks, the process importance read for a late initialisation, activities that
 * started before the tracker was registered, and the screen turning off (an unlock is no new visit).
 */
class LifecycleTest : SdkTestBase() {

    private val events = ArrayList<String>()
    private val listener = object : ForegroundListener {
        override fun onForeground(afterScreenOff: Boolean) {
            events.add(if (afterScreenOff) "foreground after the screen was off" else "foreground")
        }

        override fun onBackground() {
            events.add("background")
        }

        override fun onActivityResumed() = Unit
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun test_lateInit_aTranslucentActivityOpenedAndClosedOverTheHost_staysInTheForeground() {
        val tracker = AppLifecycle(h.main, listener)
        val host = activity()
        val prompt = activity()
        // Registered while the host already shows: the importance read seeds the foreground.
        tracker.seed(true)
        h.main.runDue()
        // A translucent activity (the permission prompt) opens over the host, which only pauses, then closes.
        tracker.onActivityStarted(prompt)
        tracker.onActivityResumed(prompt)
        tracker.onActivityResumed(host)
        tracker.onActivityStopped(prompt)
        h.advance(2.0)
        assertEquals(AppLifecycle.State.FOREGROUND, tracker.state)
        tracker.onActivityStopped(host)
        h.advance(2.0)
        assertEquals(AppLifecycle.State.BACKGROUND, tracker.state)
        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun test_seededBeforeTheFirstActivity_thatActivitysStop_reportsTheBackground() {
        val tracker = AppLifecycle(h.main, listener)
        // initWithContext in Application.onCreate of a process launched for an activity.
        tracker.seed(true)
        h.main.runDue()
        val first = activity()
        tracker.onActivityStarted(first)
        tracker.onActivityResumed(first)
        tracker.onActivityStopped(first)
        h.advance(2.0)
        assertEquals(AppLifecycle.State.BACKGROUND, tracker.state)
        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun test_lateInit_theStopOfTheActivityThatAlreadyShowed_reportsTheBackground() {
        val tracker = AppLifecycle(h.main, listener)
        tracker.seed(true)
        h.main.runDue()
        tracker.onActivityStopped(activity())
        h.advance(2.0)
        assertEquals(AppLifecycle.State.BACKGROUND, tracker.state)
        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun test_lateInit_anImportanceReadOlderThanTheActivitysStop_isIgnored() {
        val tracker = AppLifecycle(h.main, listener)
        // The importance was read on the engine thread as "foreground"; the user left before it reached the main thread.
        tracker.onActivityStopped(activity())
        tracker.seed(true)
        h.main.runDue()
        h.advance(2.0)
        assertEquals(AppLifecycle.State.BACKGROUND, tracker.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun test_lateInit_anActivitySeenResumedFirst_isTheForeground_andALaterImportanceReadChangesNothing() {
        val tracker = AppLifecycle(h.main, listener)
        val host = activity()
        tracker.onActivityResumed(host)
        tracker.seed(false)
        h.main.runDue()
        assertEquals(AppLifecycle.State.FOREGROUND, tracker.state)
        tracker.onActivityStopped(host)
        h.advance(2.0)
        assertEquals(AppLifecycle.State.BACKGROUND, tracker.state)
    }

    @Test
    fun test_aConfigurationChange_neverReportsTheBackground() {
        val tracker = AppLifecycle(h.main, listener)
        val before = activity()
        tracker.onActivityStarted(before)
        tracker.onActivityStopped(before)
        tracker.onActivityStarted(activity())
        h.advance(2.0)
        assertEquals(AppLifecycle.State.FOREGROUND, tracker.state)
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun test_aBackgroundWithTheScreenOff_makesTheNextForegroundAnUnlock_andOnlyThatOne() {
        val tracker = AppLifecycle(h.main, listener)
        val host = Robolectric.buildActivity(Activity::class.java).create().get()
        val power = shadowOf(h.context.getSystemService(PowerManager::class.java))
        tracker.onActivityStarted(host)
        // The screen turns off with the app on screen: the lock screen stops the activity.
        power.setIsInteractive(false)
        tracker.onActivityStopped(host)
        h.advance(2.0)
        // The unlock shows the app again.
        power.setIsInteractive(true)
        tracker.onActivityStarted(host)
        // Home, then the app again: a new visit.
        tracker.onActivityStopped(host)
        h.advance(2.0)
        tracker.onActivityStarted(host)
        assertEquals(
            listOf("foreground", "background", "foreground after the screen was off", "background", "foreground"),
            events,
        )
    }

    @Test
    fun test_theScreenOffAndOnWithinTheGracePeriod_isNoBackground_andLeavesNoUnlockBehind() {
        val tracker = AppLifecycle(h.main, listener)
        val host = Robolectric.buildActivity(Activity::class.java).create().get()
        val power = shadowOf(h.context.getSystemService(PowerManager::class.java))
        tracker.onActivityStarted(host)
        power.setIsInteractive(false)
        tracker.onActivityStopped(host)
        power.setIsInteractive(true)
        tracker.onActivityStarted(host)
        h.advance(2.0)
        tracker.onActivityStopped(host)
        h.advance(2.0)
        tracker.onActivityStarted(host)
        assertEquals(listOf("foreground", "background", "foreground"), events)
    }

    @Test
    fun test_theImportanceSeed_isNoUnlock() {
        val tracker = AppLifecycle(h.main, listener)
        h.context.getSystemService(PowerManager::class.java).let { shadowOf(it).setIsInteractive(false) }
        tracker.seed(true)
        h.main.runDue()
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun test_decisionsWaitingForTheState_runOnceItIsKnown() {
        val tracker = AppLifecycle(h.main, listener)
        val ran = ArrayList<AppLifecycle.State>()
        tracker.runWhenKnown(Runnable { ran.add(tracker.state) })
        h.main.runDue()
        assertTrue(ran.isEmpty())
        tracker.seed(false)
        h.main.runDue()
        assertEquals(listOf(AppLifecycle.State.BACKGROUND), ran)
        tracker.runWhenKnown(Runnable { ran.add(tracker.state) })
        h.main.runDue()
        assertEquals(listOf(AppLifecycle.State.BACKGROUND, AppLifecycle.State.BACKGROUND), ran)
    }
}
