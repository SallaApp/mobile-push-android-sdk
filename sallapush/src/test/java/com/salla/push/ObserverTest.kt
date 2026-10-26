package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What observers hear, as on iOS: the state an earlier launch left is no change, a
 * subscription observer gets one replay, and a refreshed token of the same customer is no change for user observers.
 */
class ObserverTest : SdkTestBase() {

    private val heard = ArrayList<String>()

    private val userObserver = object : SallaPushUserObserver {
        override fun onUserStateChange(state: SallaPushUserChangedState) {
            heard.add("user ${state.previous.isLoggedIn}/${state.previous.externalId} -> ${state.current.isLoggedIn}/${state.current.externalId}")
        }
    }

    private val subscriptionObserver = SallaPushSubscriptionObserver { state ->
        heard.add("subscription ${state.previous.id} -> ${state.current.id}")
    }

    /**
     * A registered device of a linked customer, then a cold start from its files: Application.onCreate calls
     * initWithContext, adds the observers, and makes the launch identity call [launch].
     */
    private fun coldStart(launch: () -> Unit) {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        assertEquals("1001", SallaPush.User.externalId)
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 60_000L
        next.serverExternalId = h.serverExternalId
        h.close()
        h = next.install()
        h.healthyServer()
        SallaPush.initWithContext(h.context, Harness.APP_ID)
        SallaPush.User.addObserver(userObserver)
        SallaPush.User.pushSubscription.addObserver(subscriptionObserver)
        launch()
        h.settle()
        h.foreground()
        h.deliverToken()
        h.advance(5.0)
    }

    @Test
    fun test_coldStart_restoresSilently_andReplaysOnceForSubscriptionObservers() {
        coldStart { SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN) }
        assertEquals(listOf("subscription ${Http.DEVICE_ID} -> ${Http.DEVICE_ID}"), heard)
        assertTrue(h.net.calls.isEmpty())
        // A real change after the restored state is heard.
        SallaPush.logout()
        h.advance(3.0)
        assertEquals("user true/1001 -> false/null", heard.last())
    }

    @Test
    fun test_coldStart_withARefreshedTokenOfTheSameCustomer_isNoChangeForUserObservers() {
        coldStart { SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN_2) }
        // The device is linked to that customer: nothing is sent, and the external id stays (never 1001 -> null -> 1001).
        assertEquals(0, h.net.calls(Routes.LINK).size)
        assertEquals(listOf("subscription ${Http.DEVICE_ID} -> ${Http.DEVICE_ID}"), heard)
        assertEquals("1001", SallaPush.User.externalId)
        assertTrue(SallaPush.User.isLoggedIn)
    }

    @Test
    fun test_coldStart_withoutALaunchCall_isNoChangeEither() {
        coldStart { }
        assertEquals(listOf("subscription ${Http.DEVICE_ID} -> ${Http.DEVICE_ID}"), heard)
    }

    @Test
    fun test_observersAddedAfterTheStart_aSubscriptionObserverGetsOneReplay_aUserObserverNone() {
        h.registered()
        SallaPush.User.addObserver(userObserver)
        SallaPush.User.pushSubscription.addObserver(subscriptionObserver)
        h.settle()
        assertEquals(listOf("subscription ${Http.DEVICE_ID} -> ${Http.DEVICE_ID}"), heard)
    }

    @Test
    fun test_aFreshInstall_subscriptionObserverAddedBeforeInit_hearsTheTokenAndTheRegistration_withoutAReplay() {
        val changes = ArrayList<SallaPushSubscriptionChangedState>()
        SallaPush.User.pushSubscription.addObserver { changes.add(it) }
        h.healthyServer()
        h.start()
        h.settle()
        assertTrue(changes.isEmpty())
        h.deliverToken()
        h.advance(3.0)
        // Real changes only: the token arrives, then the registration gives the device its id. No replay without an id.
        assertEquals(listOf(null to Harness.FCM_TOKEN, Harness.FCM_TOKEN to Harness.FCM_TOKEN), changes.map { it.previous.token to it.current.token })
        assertEquals(listOf(null to null, null to Http.DEVICE_ID), changes.map { it.previous.id to it.current.id })
    }

    @Test
    fun test_anObserverThatThrows_isReportedWithWhereItThrew_andTheOthersStillHear() {
        h.registered()
        SallaPush.User.addObserver(object : SallaPushUserObserver {
            override fun onUserStateChange(state: SallaPushUserChangedState) = throw IllegalStateException("app bug for customer@example.com")
        })
        SallaPush.User.addObserver(userObserver)
        h.settle()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.settle()
        assertEquals(listOf("user false/null -> true/1001"), heard)
        // An app callback that threw is ERROR api.callback_threw, with where it threw, never its message.
        val report = h.capture.of(LogCode.API_CALLBACK_THREW).single()
        assertEquals(SallaPushLogLevel.ERROR, report.code.level)
        assertTrue(report.message, report.message.startsWith("The observer passed to User.addObserver threw IllegalStateException at com.salla.push.ObserverTest"))
        assertTrue(report.message, report.message.endsWith("; the other observers still ran."))
        assertFalse(report.message.contains("customer@example.com"))
    }

    @Test
    fun test_anAppIdSwitch_isARealChange() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        h.advance(3.0)
        SallaPush.User.addObserver(userObserver)
        SallaPush.User.pushSubscription.addObserver(subscriptionObserver)
        h.settle()
        heard.clear()
        h.net.on("POST", Routes.REGISTER) { Http.registered(deviceId = Http.DEVICE_ID_2) }
        SallaPush.initWithContext(h.context, "st_43_android")
        h.advance(5.0)
        // The new app id starts from its own, empty files: observers hear the change, then the new registration.
        assertTrue(heard.toString(), heard.contains("subscription ${Http.DEVICE_ID} -> null"))
        assertTrue(heard.toString(), heard.contains("user true/1001 -> false/null"))
        assertEquals("subscription null -> ${Http.DEVICE_ID_2}", heard.last())
    }
}
