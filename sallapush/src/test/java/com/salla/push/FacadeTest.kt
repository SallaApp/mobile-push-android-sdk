package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.engine.EngineMode
import com.salla.push.internal.engine.Snapshot
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Meta
import com.salla.push.internal.storage.HostTag
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import com.salla.push.testing.keysList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacadeTest : SdkTestBase() {

    @Test
    fun test_gettersBeforeInitialize_areSafeDefaults() {
        assertFalse(SallaPush.isInitialized)
        assertNull(SallaPush.appId)
        assertEquals("1.1.0", SallaPush.sdkVersion)
        assertNull(SallaPush.User.externalId)
        assertFalse(SallaPush.User.isLoggedIn)
        assertNull(SallaPush.User.pushSubscription.id)
        assertNull(SallaPush.User.pushSubscription.token)
        assertFalse(SallaPush.User.pushSubscription.optedIn)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertFalse(SallaPush.Notifications.permission)
        assertFalse(SallaPush.Notifications.canRequestPermission())
        assertEquals(SallaPushLogLevel.WARN, SallaPush.Debug.logLevel)
    }

    @Test
    fun test_callsBeforeInitialize_areBufferedAndReplayedInOrder() {
        h.healthyServer()
        SallaPush.Notifications.onNewToken(h.context, Harness.FCM_TOKEN)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        SallaPush.User.removeTag("promo")
        SallaPush.User.setLanguage("en")
        h.settle()
        assertTrue(h.net.calls.isEmpty())
        h.start()
        h.advance(15.0)
        assertEquals(listOf(Routes.REGISTER, Routes.LINK, Routes.TAGS), h.net.calls.map { it.route })
        val register = h.net.calls[0].body ?: throw AssertionError()
        assertEquals("en", register.getString("language"))
        assertEquals(setOf("plan"), h.net.calls[2].body?.getJSONObject("add")?.keysList()?.toSet())
        assertTrue(SallaPush.isInitialized)
        assertEquals(Harness.APP_ID, SallaPush.appId)
    }

    @Test
    fun test_bufferOverflow_keepsIdentityCalls() {
        h.healthyServer()
        for (index in 0 until 150) {
            SallaPush.User.addTag("k$index", "v")
            if (index == 75) SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        }
        h.settle()
        // Reported once, with the total, when the engine takes the buffer (as on iOS), not while calls keep coming.
        assertEquals(0, h.capture.count(LogCode.INIT_BUFFER_FULL))
        h.start()
        h.deliverToken()
        h.advance(15.0)
        assertEquals(1, h.net.calls(Routes.LINK).size)
        assertTrue(SallaPush.User.isLoggedIn)
        val full = h.capture.of(LogCode.INIT_BUFFER_FULL).single()
        assertEquals(
            "Too many calls before SallaPush started; 51 tag/language/opt-in calls were dropped (login/logout calls are kept).",
            full.message,
        )
        assertEquals("51", full.metadata[Meta.COUNT])
    }

    @Test
    fun test_bufferOverflow_ofEventsAndOneCall_isReportedOnce_withBoth() {
        h.healthyServer()
        for (index in 0 until 101) SallaPush.User.addTag("k$index", "v")
        // Opens recorded before initWithContext: the buffer keeps the last 20.
        repeat(22) { index -> SallaPush.Notifications.trackOpened(org.json.JSONObject().put("push_ref", "msg_%04x".format(index))) }
        h.settle()
        h.start()
        h.settle()
        val full = h.capture.of(LogCode.INIT_BUFFER_FULL).single()
        assertEquals(
            "Too many calls before SallaPush started; 1 tag/language/opt-in call and 2 notification events were dropped " +
                "(login/logout calls are kept).",
            full.message,
        )
        assertEquals("3", full.metadata[Meta.COUNT])
    }

    @Test
    fun test_invalidAppId_staysDisabled_laterCallsAreNoOps() {
        h.healthyServer()
        SallaPush.initWithContext(h.context, "St.42")
        h.settle()
        assertTrue(h.capture.has(LogCode.INIT_INVALID_APP_ID))
        assertFalse(SallaPush.isInitialized)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.initWithContext(h.context, Harness.APP_ID)
        h.deliverToken()
        h.advance(10.0)
        assertTrue(h.net.calls.isEmpty())
        assertEquals(1, h.capture.count(LogCode.API_INVALID_ARGUMENT))
    }

    @Test
    fun test_secondInitialize_sameAppId_isNoOp() {
        h.registered()
        SallaPush.initWithContext(h.context, Harness.APP_ID)
        h.advance(5.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(1, h.core.registry.all().size)
    }

    @Test
    fun test_configBuilder_autoClearBadgeIsOffAndAutoUpdateBadgeOnByDefault_bothSettable() {
        assertFalse(SallaPushConfig.Builder().build().autoClearBadge)
        assertTrue(SallaPushConfig.Builder().setAutoClearBadge(true).build().autoClearBadge)
        assertFalse(SallaPushConfig.Builder().setAutoClearBadge(true).setAutoClearBadge(false).build().autoClearBadge)
        assertTrue(SallaPushConfig.Builder().setAutoClearBadge(false).build().autoUpdateBadge)
        assertTrue(SallaPushConfig.Builder().build().autoUpdateBadge)
        assertTrue(SallaPushConfig.Builder().setAutoClearBadge(true).setAutoUpdateBadge(false).build().autoClearBadge)
        assertFalse(SallaPushConfig.Builder().setAutoUpdateBadge(false).build().autoUpdateBadge)
        assertTrue(SallaPushConfig.Builder().setAutoUpdateBadge(false).setAutoUpdateBadge(true).build().autoUpdateBadge)
    }

    @Test
    fun test_secondInitialize_sameAppId_otherConfig_changesNothing() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val other = SallaPushConfig.Builder()
            .setClearTagsOnLogout(false)
            .setLogLevel(SallaPushLogLevel.VERBOSE)
            .setOpenLaunchUrls(true)
            .setRequiresUserConsent(true)
            .setAppVersion("9.9.9")
            .build()
        SallaPush.initWithContext(h.context, Harness.APP_ID, other)
        h.settle()
        // The second call changes nothing and says so (as on iOS).
        val started = h.capture.of(LogCode.INIT_STARTED)
        assertEquals(2, started.size)
        assertEquals("SallaPush is already initialized with app st_42_android; this call changes nothing.", started[1].message)
        assertEquals(Harness.APP_ID, started[1].metadata[Meta.APP_ID])
        assertEquals(SallaPushLogLevel.WARN, SallaPush.Debug.logLevel)
        assertFalse(h.capture.has(LogCode.CONSENT_WAITING))
        SallaPush.logout()
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.UNLINK).size)
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    @Test
    fun test_appIdOrHostChange_namesWhatChanged() {
        h.healthyServer()
        h.start()
        SallaPush.initWithContext(h.context, "st_43_android")
        SallaPush.initWithContext(h.context, "st_43_android", SallaPushConfig.Builder().apply { apiBaseUrl = "https://push.example.com" }.build())
        SallaPush.initWithContext(h.context, "st_44_android")
        h.settle()
        val changed = h.capture.of(LogCode.INIT_APP_ID_CHANGED)
        assertEquals(
            listOf(
                "Re-initialized with app st_43_android (was st_42_android); the previous app finishes its pending logout first.",
                "Re-initialized with app st_43_android on another server; the previous server finishes its pending logout first.",
                "Re-initialized with app st_44_android (was st_43_android); the previous app finishes its pending logout first.",
            ),
            changed.map { it.message },
        )
        assertEquals(listOf("st_43_android", "st_43_android", "st_44_android"), changed.map { it.metadata[Meta.APP_ID] })
    }

    @Test
    fun test_invalidAppIdAfterAValidOne_isIgnored_andSaysTheAppKeepsRunning() {
        h.registered()
        SallaPush.initWithContext(h.context, "St.43")
        h.settle()
        assertEquals(
            "App id \"St.43\" is invalid (expected 1-64 chars A-Z, a-z, 0-9, _, -); this call was ignored, and SallaPush keeps running with app st_42_android.",
            h.capture.of(LogCode.INIT_INVALID_APP_ID).single().message,
        )
        assertTrue(h.console.toString(), h.console.any { it.startsWith("ERROR init.invalid_app_id: App id \"St.43\" is invalid") && it.endsWith("keeps running with app st_42_android.") })
        assertTrue(SallaPush.isInitialized)
        assertEquals(Harness.APP_ID, SallaPush.appId)
        SallaPush.User.setLanguage("en")
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.DEVICE).size)
    }

    @Test
    fun test_startUpErrors_reachAListenerAddedRightAfterInitWithContext() {
        val events = java.util.concurrent.CopyOnWriteArrayList<SallaPushLogEvent>()
        SallaPush.initWithContext(h.context, "St.42")
        SallaPush.Debug.addLogListener(SallaPushLogListener { events.add(it) }, SallaPushLogLevel.WARN)
        h.settle()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (events.none { it.code == "init.invalid_app_id" }) {
            if (System.nanoTime() > deadline) throw AssertionError("the start-up error did not reach the listener: $events")
            Thread.sleep(2)
        }
        val error = events.first { it.code == "init.invalid_app_id" }
        assertEquals(SallaPushLogLevel.ERROR, error.level)
        assertTrue(error.message, error.message.startsWith("App id \"St.42\" is invalid"))
    }

    @Test
    fun test_invalidAppId_reachesLogcat_atTheDefaultLevel() {
        SallaPush.initWithContext(h.context, "St.42")
        h.settle()
        assertTrue(h.console.toString(), h.console.any { it.startsWith("ERROR init.invalid_app_id: App id \"St.42\" is invalid") })
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        assertTrue(h.console.any { it.startsWith("WARN api.invalid_argument: login ignored: SallaPush is disabled") })
    }

    @Test
    fun test_invalidAppId_withAConfigLevel_reachesLogcatAtOnce() {
        SallaPush.initWithContext(h.context, "", SallaPushConfig.Builder().setLogLevel(SallaPushLogLevel.VERBOSE).build())
        assertTrue(h.console.toString(), h.console.any { it.startsWith("ERROR init.invalid_app_id:") })
        assertEquals(SallaPushLogLevel.VERBOSE, SallaPush.Debug.logLevel)
    }

    @Test
    fun test_subscriptionReplay_isTheStateAtDelivery_neverOlderThanADeliveredChange() {
        val before = Snapshot(true, Http.DEVICE_ID, Harness.FCM_TOKEN, true, false, null, false, emptyMap(), true)
        val after = before.copy(optedIn = false)
        var engineState = before
        val seen = ArrayList<Pair<Boolean, Boolean>>()
        // The process already started from its restored state, so an observer added now gets its own replay.
        h.core.observers.restored(Snapshot.EMPTY)
        // The engine publishes a change right after the state is read for the replay, and posts that change's delivery.
        // A replay built from a read made when the observer was added would then arrive after the change, stale.
        val readThenChange = {
            val read = engineState
            if (read === before) {
                engineState = after
                h.core.observers.snapshotChanged(before, after)
            }
            read
        }
        h.core.observers.addSubscriptionObserver(SallaPushSubscriptionObserver { seen.add(it.previous.optedIn to it.current.optedIn) }, readThenChange)
        h.main.runDue()
        assertEquals(listOf(true to true, true to false), seen)
        assertFalse("the observer ends on the engine's state", seen.last().second)
    }

    @Test
    fun test_subscriptionReplay_skippedWhileNotRegistered_thenChangesFlow() {
        h.healthyServer()
        h.start()
        val seen = ArrayList<SallaPushSubscriptionChangedState>()
        SallaPush.User.pushSubscription.addObserver { seen.add(it) }
        h.settle()
        assertTrue(seen.isEmpty())
        h.deliverToken()
        h.advance(2.0)
        assertEquals(Http.DEVICE_ID, seen.last().current.id)
        val replayed = ArrayList<SallaPushSubscriptionChangedState>()
        SallaPush.User.pushSubscription.addObserver { replayed.add(it) }
        h.settle()
        assertEquals(1, replayed.size)
        assertEquals(Http.DEVICE_ID, replayed.single().previous.id)
        assertEquals(Http.DEVICE_ID, replayed.single().current.id)
    }

    @Test
    fun test_secondInitialize_otherAppId_drainsOldEngine() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { null }
        SallaPush.logout()
        h.advance(2.0)
        val held = h.net.held().single()
        SallaPush.initWithContext(h.context, "st_43_android")
        h.settle()
        assertTrue(h.capture.has(LogCode.INIT_APP_ID_CHANGED))
        val old = h.engine()
        assertEquals(EngineMode.DRAINING, old.mode)
        held.respond(com.salla.push.testing.Http.empty())
        h.advance(2.0)
        assertTrue(old.stopped)
        val newKey = EngineKey("st_43_android", HostTag.of(Routes.DEFAULT_BASE_URL) ?: "")
        h.advance(3.0)
        assertEquals(2, h.net.calls(Routes.REGISTER).size)
        assertTrue(h.net.calls(Routes.REGISTER).last().url.contains("/apps/st_43_android/devices"))
        assertEquals(EngineMode.MAIN, h.engine(newKey).mode)
    }

    @Test
    fun test_logout_returnsWithoutNetwork() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        val before = h.net.calls.size
        SallaPush.logout()
        assertEquals(before, h.net.calls.size)
        assertTrue(h.engineRunner.pendingCount() > 0)
    }

    @Test
    fun test_initialize_setsNoListenerOrDelegateOfItsOwn_andRegistersNothingBeforeTasksRun() {
        SallaPush.initWithContext(h.context, Harness.APP_ID)
        assertTrue(SallaPush.isInitialized)
        assertTrue(h.net.calls.isEmpty())
        assertEquals(0, h.firebase.fetches)
        h.settle()
        assertEquals(1, h.firebase.fetches)
    }

    @Test
    fun test_firebaseToken_readAtEngineStart_whenAvailable() {
        h.healthyServer()
        h.firebase.result = com.salla.push.internal.FirebaseToken.Token(Harness.FCM_TOKEN)
        h.start()
        h.advance(3.0)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
        assertEquals(Harness.FCM_TOKEN, h.net.calls(Routes.REGISTER).single().body?.getString("push_token"))
    }

    @Test
    fun test_firebaseAutoInitDisabled_waitsForOnNewToken() {
        h.firebase.result = com.salla.push.internal.FirebaseToken.AutoInitDisabled
        h.start()
        assertTrue(h.capture.has(LogCode.TOKEN_AUTO_INIT_DISABLED))
        h.firebase.result = com.salla.push.internal.FirebaseToken.Failed("IOException")
        val other = Harness(h.context).install()
        other.firebase.result = com.salla.push.internal.FirebaseToken.Failed("IOException")
        other.start()
        assertTrue(other.capture.has(LogCode.TOKEN_FETCH_FAILED))
        other.close()
    }

    @Test
    fun test_theServerUrl_isNoSettingAppCodeCanReach() {
        val seam = (SallaPushConfig::class.java.declaredMethods + SallaPushConfig.Builder::class.java.declaredMethods)
            .filter { it.name.contains("ApiBaseUrl") }
        assertTrue(seam.isNotEmpty())
        assertTrue(seam.joinToString { it.name }, seam.all { it.isSynthetic })
    }

    @Test
    fun test_rejectedBaseUrl_fallsBackToDefault() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().apply { apiBaseUrl = "http://push.example.com/notifications" }.build())
        h.deliverToken()
        h.advance(3.0)
        assertEquals("${Routes.DEFAULT_BASE_URL}/apps/st_42_android/devices", h.net.calls(Routes.REGISTER).single().url)
        assertEquals(h.key.hostTag, h.core.registry.keys().single().hostTag)
    }

    @Test
    fun test_localHttpBaseUrl_isAllowed_andChangesHostTag() {
        h.healthyServer()
        h.start(config = SallaPushConfig.Builder().apply { apiBaseUrl = "http://10.0.2.2:8787/" }.build())
        h.deliverToken()
        h.advance(3.0)
        assertEquals("http://10.0.2.2:8787/apps/st_42_android/devices", h.net.calls(Routes.REGISTER).single().url)
        assertTrue(h.core.registry.keys().single().hostTag != h.key.hostTag)
    }

    @Test
    fun test_manifestMetaData_setsTheLogLevel_andAServerUrlInItIsIgnored() {
        val info = org.robolectric.Shadows.shadowOf(h.context.packageManager).getInternalMutablePackageInfo(h.context.packageName)
        info.applicationInfo?.metaData = android.os.Bundle().apply {
            putString(com.salla.push.internal.ManifestConfig.KEY_LOG_LEVEL, "verbose")
            putString("com.salla.push.API_BASE_URL", "https://push.example.com")
        }
        h.manifest = com.salla.push.internal.ManifestConfig.read(h.context)
        h.healthyServer()
        h.start()
        h.deliverToken()
        h.advance(3.0)
        assertEquals(SallaPushLogLevel.VERBOSE, SallaPush.Debug.logLevel)
        assertEquals("${Routes.DEFAULT_BASE_URL}/apps/st_42_android/devices", h.net.calls(Routes.REGISTER).single().url)
    }

    // ── The console level is settled by whichever entry builds the platform first ──

    @Test
    fun test_aPushHandledBeforeInit_thenInit_theManifestLevelApplies() {
        h.manifest = com.salla.push.internal.ManifestConfig(SallaPushLogLevel.VERBOSE, 0, 0, 0, null, null, null)
        SallaPush.Notifications.handleFirebaseMessage(h.context, com.salla.push.testing.Messages.remote(com.salla.push.testing.Messages.native()))
        h.settle()
        h.init()
        assertEquals(SallaPushLogLevel.VERBOSE, SallaPush.Debug.logLevel)
        h.console.clear()
        SallaPush.User.addTag(" ", "x")
        h.settle()
        assertTrue(h.console.toString(), h.console.any { it.startsWith("WARN api.invalid_argument: addTag ignored") })
    }

    @Test
    fun test_aPushHandledBeforeInit_thenInit_warningsReachLogcatAtTheDefaultLevel() {
        SallaPush.Notifications.handleFirebaseMessage(h.context, com.salla.push.testing.Messages.remote(com.salla.push.testing.Messages.native()))
        h.settle()
        h.init()
        h.console.clear()
        SallaPush.User.addTag(" ", "x")
        h.settle()
        assertTrue(h.console.toString(), h.console.any { it.startsWith("WARN api.invalid_argument: addTag ignored") })
    }

    @Test
    fun test_withoutInit_aWarningFromHandleFirebaseMessage_reachesLogcatAtTheDefaultLevel() {
        org.robolectric.Shadows.shadowOf(h.context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, com.salla.push.testing.Messages.remote(com.salla.push.testing.Messages.native())))
        h.settle()
        assertFalse(SallaPush.isInitialized)
        assertTrue(h.console.toString(), h.console.any { it.startsWith("WARN display.default_icon:") })
    }

    @Test
    fun test_withoutInit_aWarningFromOnNewToken_reachesLogcatAtTheDefaultLevel() {
        SallaPush.Notifications.onNewToken(h.context, "short")
        h.settle()
        assertTrue(h.console.toString(), h.console.any { it.startsWith("WARN token.invalid:") })
        assertFalse(h.console.any { it.startsWith("INFO") || it.startsWith("DEBUG") })
    }

    // ── Observers and listeners are held strongly until removed (OneSignal v5 Android semantics) ──

    @Test
    fun test_anObserverReferencedOnlyBySallaPush_keepsGettingChanges_untilRemoved() {
        h.registered()
        val changes = java.util.concurrent.atomic.AtomicInteger()
        SallaPush.User.pushSubscription.addObserver { changes.incrementAndGet() }
        h.settle()
        val replayed = changes.get()
        repeat(3) { System.gc() }
        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        assertEquals(replayed + 1, changes.get())
    }

    @Test
    fun test_appVersionOverride_winsOverPackageVersion_andValuesAreTruncated() {
        val withOverride = com.salla.push.internal.device.AndroidDeviceInfo(h.context) { "9.9.9" }
        assertEquals("9.9.9", withOverride.facts().appVersion)
        assertEquals("1.1.0", withOverride.facts().sdkVersion)
        val longOverride = com.salla.push.internal.device.AndroidDeviceInfo(h.context) { "v".repeat(40) }
        assertEquals(32, longOverride.facts().appVersion?.length)
        // The device language is reduced to its language part; an app language is sent as given (UpdateTest).
        assertEquals("ar", com.salla.push.internal.device.AndroidDeviceInfo.languageOf(java.util.Locale.forLanguageTag("ar-SA")))
        assertEquals("he", com.salla.push.internal.device.AndroidDeviceInfo.languageOf(java.util.Locale("iw")))
        assertNull(com.salla.push.internal.device.AndroidDeviceInfo.languageOf(java.util.Locale.ROOT))
    }

    // ── com.salla.push.LOG_LEVEL that is not a level is said once, never ignored silently ──

    /** Reads the manifest meta-data after setting com.salla.push.LOG_LEVEL to [value] on this test app. */
    private fun manifestWithLogLevel(value: Any): com.salla.push.internal.ManifestConfig {
        val info = org.robolectric.Shadows.shadowOf(h.context.packageManager).getInternalMutablePackageInfo(h.context.packageName)
        val metaData = android.os.Bundle()
        when (value) {
            is String -> metaData.putString(com.salla.push.internal.ManifestConfig.KEY_LOG_LEVEL, value)
            is Int -> metaData.putInt(com.salla.push.internal.ManifestConfig.KEY_LOG_LEVEL, value)
        }
        info.applicationInfo?.metaData = metaData
        return com.salla.push.internal.ManifestConfig.read(h.context)
    }

    @Test
    fun test_manifestLogLevel_thatIsNotALevel_isReportedOnce_withTheAcceptedValues() {
        val trace = manifestWithLogLevel("trace")
        assertNull(trace.logLevel)
        assertEquals("trace", trace.invalidLogLevel)
        assertEquals("5", manifestWithLogLevel(5).invalidLogLevel)
        val debug = manifestWithLogLevel(" Debug ")
        assertEquals(SallaPushLogLevel.DEBUG, debug.logLevel)
        assertNull(debug.invalidLogLevel)
        // The level is "off", as on iOS: "none" is not a level.
        assertEquals("none", manifestWithLogLevel("none").invalidLogLevel)
        assertEquals(SallaPushLogLevel.OFF, manifestWithLogLevel("off").logLevel)
        assertEquals(SallaPushLogLevel.WARN, manifestWithLogLevel("warning").logLevel)

        h.manifest = trace
        h.healthyServer()
        h.start()
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native()))
        h.advance(2.0)
        val expected = "The com.salla.push.LOG_LEVEL meta-data \"trace\" is not a log level (accepted: off, error, warn, info, debug, verbose); it is ignored."
        assertEquals(listOf(expected), h.capture.of(LogCode.API_INVALID_ARGUMENT).map { it.message })
        // The default level applies, so the warning reaches Logcat.
        assertEquals(SallaPushLogLevel.WARN, SallaPush.Debug.logLevel)
        assertTrue(h.console.contains("WARN api.invalid_argument: $expected"))
    }
}
