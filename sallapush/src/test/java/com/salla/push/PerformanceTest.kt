package com.salla.push

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.salla.push.internal.CoreEnv
import com.salla.push.internal.CorePlatform
import com.salla.push.internal.Sdk
import com.salla.push.internal.SdkCore
import com.salla.push.internal.api.Routes
import com.salla.push.testing.Harness
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The caller's thread (the app's main thread) never does SDK I/O: every public call only posts to the SDK's own
 * threads. StrictMode cannot see JVM file I/O under Robolectric, so storage, manifest and process reads are recorded
 * with the thread that made them, and the production platform is built against a Context that records every access.
 */
class PerformanceTest : SdkTestBase() {

    @Test
    fun test_initAndEveryCall_doNoIoOnTheCallerThread() {
        h.healthyServer()
        SallaPush.initWithContext(h.context, Harness.APP_ID, SallaPushConfig.Builder().setLogLevel(SallaPushLogLevel.DEBUG).build())
        SallaPush.Notifications.onNewToken(h.context, Harness.FCM_TOKEN)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        SallaPush.User.addTags(mapOf("tier" to "1"))
        SallaPush.User.removeTag("tier")
        SallaPush.User.setLanguage("en")
        SallaPush.User.pushSubscription.optOut()
        SallaPush.Debug.logLevel = SallaPushLogLevel.INFO
        SallaPush.Migration.setDisplayCompatPayloads(true)
        readEveryGetter()
        SallaPush.logout()
        assertEquals(0, h.ioCount.get())

        h.settle()
        h.foreground()
        h.advance(15.0)
        assertTrue(h.ioCount.get() > 0)
        assertEquals(emptyList<String>(), h.ioOffSdkThreads.toList())
    }

    @Test
    fun test_relaunchWithStoredState_initAndGetters_doNoIoOnTheCallerThread() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.install()
        next.healthyServer()
        SallaPush.initWithContext(next.context, Harness.APP_ID)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        readEveryGetter()
        assertEquals(0, next.ioCount.get())
        next.settle()
        next.foreground()
        next.advance(5.0)
        assertTrue(next.ioCount.get() > 0)
        assertEquals(emptyList<String>(), next.ioOffSdkThreads.toList())
        next.close()
    }

    @Test
    fun test_steadyStateLaunches_sendNothing_signedInAndGuest() {
        // A launch where nothing changed sends no request, whichever identity call the app makes.
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)

        val signedIn = h.relaunch(keepCalls = true) { SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN) }
        signedIn.advance(30.0)
        assertEquals(emptyList<String>(), requests(signedIn))

        SallaPush.logout()
        SallaPush.User.addTag("plan", "silver")
        signedIn.advance(15.0)
        assertEquals(listOf("PATCH ${Routes.TAGS}", "POST ${Routes.UNLINK}", "PATCH ${Routes.TAGS}"), requests(signedIn))

        val guest = signedIn.relaunch(keepCalls = true) { SallaPush.logout() }
        guest.advance(30.0)
        assertEquals(emptyList<String>(), requests(guest))
        assertEquals(mapOf("plan" to "silver"), SallaPush.User.getTags())
        signedIn.close()
        guest.close()
    }

    @Test
    fun test_handleFirebaseMessage_doesNoIoOnTheCallerThread() {
        h.registered()
        val before = h.ioCount.get()
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native())))
        assertEquals(before, h.ioCount.get())
        h.advance(6.0)
        assertEquals(emptyList<String>(), h.ioOffSdkThreads.toList())
    }

    @Test
    fun test_productionPlatform_isBuiltWithoutTouchingStorage() {
        val context = RecordingContext(h.context)
        val platform = CorePlatform.production(context, h.env) { null }
        assertEquals(emptyList<String>(), context.accesses.map { it.what })
        assertTrue(platform.files.root() != null)
        assertTrue(context.accesses.any { it.what == "noBackupFilesDir" })
    }

    @Test
    fun test_productionCore_publicCallsLeaveAllIoToTheSdkThreads() {
        val context = RecordingContext(h.context)
        val env = CoreEnv.production()
        val core = SdkCore(env)
        Sdk.install(core)
        val caller = Thread.currentThread()
        // A token is never fetched (no FirebaseApp), so nothing is sent; the base URL is a closed local port anyway.
        SallaPush.initWithContext(context, Harness.APP_ID, SallaPushConfig.Builder().apply { apiBaseUrl = "http://127.0.0.1:9" }.build())
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        SallaPush.User.setLanguage("en")
        SallaPush.logout()
        val idle = CountDownLatch(1)
        env.engineRunner.execute(Runnable { idle.countDown() })
        assertTrue(idle.await(20, TimeUnit.SECONDS))

        assertTrue(context.accesses.any { it.what == "noBackupFilesDir" })
        assertEquals(emptyList<String>(), context.accesses.filter { it.thread === caller }.map { it.what })
        assertTrue(File(context.noBackupFilesDir, "sallapush/last_init.json").isFile)

        val stopped = CountDownLatch(1)
        env.engineRunner.execute(Runnable {
            for (engine in core.registry.all()) engine.stop()
            stopped.countDown()
        })
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
    }

    private fun requests(harness: Harness): List<String> = harness.net.calls.map { "${it.method} ${it.route}" }

    private fun readEveryGetter() {
        SallaPush.isInitialized
        SallaPush.appId
        SallaPush.sdkVersion
        SallaPush.User.isLoggedIn
        SallaPush.User.externalId
        SallaPush.User.getTags()
        SallaPush.User.pushSubscription.id
        SallaPush.User.pushSubscription.token
        SallaPush.User.pushSubscription.optedIn
        SallaPush.Notifications.canRequestPermission()
        SallaPush.Debug.logLevel
    }

    private class Access(val what: String, val thread: Thread)

    /** Records every storage, preferences and package-manager access, with the thread that made it. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val accesses = CopyOnWriteArrayList<Access>()

        private fun record(what: String) {
            accesses.add(Access(what, Thread.currentThread()))
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File {
            record("noBackupFilesDir")
            return super.getNoBackupFilesDir()
        }

        override fun getFilesDir(): File {
            record("filesDir")
            return super.getFilesDir()
        }

        override fun getCacheDir(): File {
            record("cacheDir")
            return super.getCacheDir()
        }

        override fun getDataDir(): File {
            record("dataDir")
            return super.getDataDir()
        }

        override fun getDatabasePath(name: String?): File {
            record("databasePath")
            return super.getDatabasePath(name)
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            record("sharedPreferences")
            return super.getSharedPreferences(name, mode)
        }

        override fun getPackageManager(): PackageManager {
            record("packageManager")
            return super.getPackageManager()
        }

        override fun getApplicationInfo(): ApplicationInfo {
            record("applicationInfo")
            return super.getApplicationInfo()
        }
    }
}
