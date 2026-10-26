package com.salla.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * As on iOS, while consent is withheld the getters keep the state the observers last received. A pause keeps it as it
 * was; a withdrawal keeps the device id, the token and the login state, and forgets the route key and the tags, which
 * the user observers hear. A getter never changes without the observer callback that reports it.
 */
class ConsentGettersTest : SdkTestBase() {

    private val consentRequired = SallaPushConfig.Builder().setRequiresUserConsent(true).build()
    private val userChanges = ArrayList<SallaPushUserChangedState>()
    private val subscriptionChanges = ArrayList<SallaPushSubscriptionChangedState>()

    private fun observe() {
        SallaPush.User.addObserver(object : SallaPushUserObserver {
            override fun onUserStateChange(state: SallaPushUserChangedState) {
                userChanges.add(state)
            }
        })
        SallaPush.User.pushSubscription.addObserver { subscriptionChanges.add(it) }
        h.settle()
        // A subscription observer added once the device has an id gets one replay: not a change.
        subscriptionChanges.clear()
    }

    /** Signed in (route key 1001) with a tag, all of it confirmed by the server. */
    private fun signedInWithATag(config: SallaPushConfig? = null) {
        h.healthyServer()
        h.start(config = config)
        if (config != null) SallaPush.setConsentGiven(true)
        h.deliverToken()
        h.advance(3.0)
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals("1001", SallaPush.User.externalId)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
    }

    /** Consent becomes required and is not given. */
    @Test
    fun test_consentRequiredWhileRunning_theGettersKeepTheState_withoutACallback() {
        signedInWithATag()
        observe()
        SallaPush.setConsentRequired(true)
        h.advance(5.0)
        assertTrue(h.capture.has(LogCode.CONSENT_WAITING))
        assertTrue(SallaPush.User.isLoggedIn)
        assertEquals("1001", SallaPush.User.externalId)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(Harness.FCM_TOKEN, SallaPush.User.pushSubscription.token)
        assertTrue(SallaPush.User.pushSubscription.optedIn)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        assertTrue(userChanges.isEmpty())
        assertTrue(subscriptionChanges.isEmpty())

        // Consent given: SallaPush resumes from the same state, which is still no change.
        h.net.clear()
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertEquals("1001", SallaPush.User.externalId)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        assertTrue(userChanges.isEmpty())
        assertTrue(subscriptionChanges.isEmpty())
        assertTrue(h.net.calls.isEmpty())
    }

    /** Consent that was given is withdrawn. */
    @Test
    fun test_withdrawal_keepsTheIdTheTokenAndTheLogin_forgetsTheRouteKeyAndTheTags_andTheObserversHearIt() {
        signedInWithATag(consentRequired)
        observe()
        SallaPush.setConsentGiven(false)
        h.advance(10.0)
        assertEquals(listOf(Routes.DEVICE, Routes.TAGS, Routes.UNLINK), h.net.calls.takeLast(3).map { it.route })
        assertTrue(SallaPush.User.isLoggedIn)
        assertNull(SallaPush.User.externalId)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(Harness.FCM_TOKEN, SallaPush.User.pushSubscription.token)
        assertTrue(SallaPush.User.getTags().isEmpty())
        val change = userChanges.single()
        assertEquals("1001", change.previous.externalId)
        assertTrue(change.previous.isLoggedIn)
        assertNull(change.current.externalId)
        assertTrue(change.current.isLoggedIn)
        assertTrue(subscriptionChanges.isEmpty())

        // Consent given again: the customer is linked again, and the observers hear the route key come back.
        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertEquals("1001", SallaPush.User.externalId)
        assertEquals(2, userChanges.size)
        assertNull(userChanges[1].previous.externalId)
        assertEquals("1001", userChanges[1].current.externalId)
    }

    @Test
    fun test_aWithdrawalBeforeInitWithContext_showsTheStateItLoaded_withoutTheRouteKeyAndTheTags() {
        signedInWithATag(consentRequired)
        val next = Harness(h.context)
        File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
        next.clock.millis = h.clock.millis + 60_000L
        next.serverExternalId = h.serverExternalId
        next.serverTags.putAll(h.serverTags)
        h.close()
        h = next.install()
        h.healthyServer()
        SallaPush.User.addObserver(object : SallaPushUserObserver {
            override fun onUserStateChange(state: SallaPushUserChangedState) {
                userChanges.add(state)
            }
        })
        SallaPush.setConsentRequired(true)
        SallaPush.setConsentGiven(false)
        h.start()
        h.advance(10.0)
        assertTrue(h.capture.has(LogCode.CONSENT_REVOKED))
        assertTrue(SallaPush.User.isLoggedIn)
        assertNull(SallaPush.User.externalId)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        assertEquals(Harness.FCM_TOKEN, SallaPush.User.pushSubscription.token)
        assertTrue(SallaPush.User.getTags().isEmpty())
        // The state it loaded is no change (as at any launch); the withdrawal's dropping of the route key is one.
        val change = userChanges.single()
        assertEquals("1001", change.previous.externalId)
        assertTrue(change.previous.isLoggedIn)
        assertNull(change.current.externalId)
        assertTrue(change.current.isLoggedIn)
    }

    @Test
    fun test_callsMadeWhileConsentIsWithheld_showOnTheKeptState_andApplyWhenItIsGiven() {
        signedInWithATag()
        SallaPush.setConsentRequired(true)
        h.advance(5.0)
        h.net.clear()
        SallaPush.logout()
        assertFalse(SallaPush.User.isLoggedIn)
        assertNull(SallaPush.User.externalId)
        assertTrue(SallaPush.User.getTags().isEmpty())
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
        h.advance(5.0)
        assertTrue(h.net.calls.isEmpty())
        assertFalse(SallaPush.User.isLoggedIn)

        SallaPush.setConsentGiven(true)
        h.advance(5.0)
        assertEquals(listOf(Routes.TAGS, Routes.UNLINK), h.net.calls.map { it.route })
        assertFalse(SallaPush.User.isLoggedIn)
        assertNull(SallaPush.User.externalId)
        assertTrue(SallaPush.User.getTags().isEmpty())
    }

    /** No engine re-reads the permission while consent is withheld, so the getter reads the system's answer then. */
    @Test
    fun test_whileConsentIsWithheld_notificationsPermission_isTheSystemsAnswer() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        h.registered()
        assertTrue(SallaPush.Notifications.permission)
        SallaPush.setConsentRequired(true)
        h.settle()
        assertTrue(SallaPush.Notifications.permission)
        shadowOf(h.context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(SallaPush.Notifications.permission)
    }

    /** As on iOS, the user's opt-out holds while consent is withheld: a SallaPush notification is not shown. */
    @Test
    fun test_anOptedOutUser_whileConsentIsWithheld_isShownNoNotification() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = h.context.getSystemService(NotificationManager::class.java)
        h.registered()
        SallaPush.User.pushSubscription.optOut()
        h.advance(3.0)
        SallaPush.setConsentRequired(true)
        h.settle()
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_a9"))))
        h.settle()
        assertEquals("Notification not shown (opted out).", h.capture.of(LogCode.NOTIF_SUPPRESSED).single().message)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        // The same push is shown once the user opts in again, consent still withheld (the call shows at once).
        SallaPush.User.pushSubscription.optIn()
        assertTrue(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(Messages.native(id = "msg_b9"))))
        h.settle()
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
}
