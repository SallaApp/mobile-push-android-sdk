package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.engine.EngineMode
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.storage.HostTag
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineLifecycleTest : SdkTestBase() {

    private val otherApp = "st_43_android"

    @Test
    fun test_stopWithPendingRetryTimer_createsNoRequest() {
        h.registered()
        h.net.on("POST", Routes.LINK) { Http.networkFailure() }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        val before = h.net.calls.size
        h.engine().stop()
        h.advance(3600.0)
        assertEquals(before, h.net.calls.size)
        assertTrue(h.core.registry.all().isEmpty())
    }

    @Test
    fun test_appIdSwitchDuringInFlightRequest_noCrash_newEngineIndependent() {
        h.registered()
        h.net.on("POST", Routes.LINK) { null }
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(2.0)
        val inFlight = h.net.held().single()
        SallaPush.initWithContext(h.context, otherApp)
        h.settle()
        inFlight.respond(Http.empty())
        h.advance(5.0)
        val newKey = EngineKey(otherApp, h.key.hostTag)
        assertEquals(EngineMode.MAIN, h.engine(newKey).mode)
        assertTrue(h.net.calls(Routes.REGISTER).last().url.contains(otherApp))
        assertFalse(SallaPush.User.isLoggedIn)
    }

    @Test
    fun test_appIdSwitch_drainsOldEnginesLogout_andStopsAfter60s() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(2.0)
        val old = h.engine()
        SallaPush.initWithContext(h.context, otherApp)
        h.settle()
        assertEquals(EngineMode.DRAINING, old.mode)
        h.advance(61.0)
        assertTrue(old.stopped)
        assertTrue(h.identityJson().contains("\"kind\":\"logout\""))
        val logoutUrls = h.net.calls(Routes.UNLINK).map { it.url }.toSet()
        assertEquals(setOf("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}/unlink"), logoutUrls)
    }

    @Test
    fun test_drainOnlyEngine_finishesAnotherAppsLogout_atTheNextLaunch() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(2.0)
        SallaPush.initWithContext(h.context, otherApp)
        h.advance(61.0)

        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis
        next.install()
        next.healthyServer()
        next.start(otherApp)
        next.advance(5.0)
        assertTrue(next.capture.has(LogCode.INIT_DRAIN_STARTED))
        val logout = next.net.calls(Routes.UNLINK).single()
        assertEquals("${Routes.DEFAULT_BASE_URL}/devices/${Http.DEVICE_ID}/unlink", logout.url)
        assertEquals(Http.SECRET, logout.authorization)
        assertTrue(next.net.calls(Routes.REGISTER).none { it.url.contains(Harness.APP_ID) })
        val drained = next.core.registry.get(h.key)
        assertTrue(drained == null || drained.stopped)
        assertFalse(next.identityJson().contains("\"kind\":\"logout\""))
        next.close()
    }

    @Test
    fun test_hostSwitch_neverSendsOtherHostsCredentials() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        h.net.on("POST", Routes.UNLINK) { Http.networkFailure() }
        SallaPush.logout()
        h.advance(2.0)
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2, secret = "b3RoZXItaG9zdC1zZWNyZXQ") }
        h.net.on("POST", Routes.UNLINK) { Http.empty() }
        SallaPush.initWithContext(h.context, Harness.APP_ID, SallaPushConfig.Builder().apply { apiBaseUrl = "https://push.example.com" }.build())
        h.advance(5.0)
        val devHost = h.net.calls.filter { it.url.startsWith("https://push.example.com") }
        assertTrue(devHost.isNotEmpty())
        assertTrue(devHost.none { it.authorization == Http.SECRET })
        assertTrue(devHost.first { it.route == Routes.REGISTER }.authorization == null)
        val prodHost = h.net.calls.filter { it.url.startsWith(Routes.DEFAULT_BASE_URL) && it.route == Routes.UNLINK }
        assertTrue(prodHost.all { it.authorization == Http.SECRET })
        val devKey = EngineKey(Harness.APP_ID, HostTag.of("https://push.example.com") ?: "")
        assertTrue(File(h.appDirectory(devKey), "credentials.bin").isFile)
        assertTrue(File(h.appDirectory(), "credentials.bin").isFile)
    }

    @Test
    fun test_manyReschedules_keepOneWake() {
        h.registered()
        val baseline = h.engineRunner.pendingCount()
        repeat(10_000) { SallaPush.User.addTag("k${it % 50}", "v$it") }
        h.settle()
        assertTrue(h.engineRunner.pendingCount() <= baseline + 1)
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
        assertEquals(50, h.net.calls(Routes.TAGS).single().body?.getJSONObject("add")?.length())
    }

    @Test
    fun test_initCyclesBetweenApps_leaveNoEnginesOrTimersBehind() {
        h.registered()
        repeat(20) { cycle ->
            SallaPush.initWithContext(h.context, if (cycle % 2 == 0) otherApp else Harness.APP_ID)
            h.settle()
        }
        h.advance(61.0)
        assertEquals(listOf(h.key), h.core.registry.all().map { it.key })
        assertEquals(EngineMode.MAIN, h.engine().mode)
        assertTrue(h.engineRunner.pendingCount() <= 2)
        SallaPush.User.addTag("plan", "gold")
        h.advance(11.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }
}
