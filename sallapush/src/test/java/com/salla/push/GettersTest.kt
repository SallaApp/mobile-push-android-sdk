package com.salla.push

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import com.salla.push.internal.api.Routes
import com.salla.push.internal.permission.SallaPushPermissionActivity
import com.salla.push.testing.Harness
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * The public getters reflect a call from the moment it returns, like OneSignal v5 models, and the value they show does
 * not change when SallaPush applies the call a few milliseconds later. Every case reads the getters right after the
 * calls, then again once the engine has run.
 */
class GettersTest : SdkTestBase() {

    private class Seen(val loggedIn: Boolean, val externalId: String?, val tags: Map<String, String>, val optedIn: Boolean) {
        override fun toString(): String = "loggedIn=$loggedIn externalId=$externalId tags=$tags optedIn=$optedIn"

        override fun equals(other: Any?): Boolean =
            other is Seen && loggedIn == other.loggedIn && externalId == other.externalId && tags == other.tags && optedIn == other.optedIn

        override fun hashCode(): Int = tags.hashCode()
    }

    private fun seen() = Seen(
        SallaPush.User.isLoggedIn,
        SallaPush.User.externalId,
        SallaPush.User.getTags(),
        SallaPush.User.pushSubscription.optedIn,
    )

    /** What the getters show right after the calls, which must also be what they show once SallaPush applied them. */
    private fun rightAfterAndApplied(calls: () -> Unit): Seen {
        calls()
        val rightAfter = seen()
        h.settle()
        assertEquals("the value shown at once is the value SallaPush applies", seen(), rightAfter)
        return rightAfter
    }

    @Test
    fun test_loginAddTagOptOut_areReflectedAtOnce() {
        h.registered()
        val after = rightAfterAndApplied {
            SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
            SallaPush.User.addTag("PLAN", "gold")
            SallaPush.User.pushSubscription.optOut()
        }
        assertTrue(after.loggedIn)
        // The external id is the call's, from the moment it returns.
        assertEquals(Harness.CUSTOMER_ID, after.externalId)
        assertEquals(mapOf("plan" to "gold"), after.tags)
        assertFalse(after.optedIn)
        val optedBackIn = rightAfterAndApplied { SallaPush.User.pushSubscription.optIn() }
        assertTrue(optedBackIn.optedIn)
    }

    @Test
    fun test_logoutOfALinkedCustomer_clearsTheLoginTheRouteKeyAndTheTags_atOnce() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        assertEquals("1001", SallaPush.User.externalId)
        val after = rightAfterAndApplied { SallaPush.logout() }
        assertFalse(after.loggedIn)
        assertNull(after.externalId)
        assertTrue(after.tags.isEmpty())
    }

    @Test
    fun test_sameOrAnotherCustomer_showsTheCallsExternalId_andASwitchDropsTheTags_atOnce() {
        h.registered()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(15.0)
        val refreshed = rightAfterAndApplied { SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN_2) }
        assertEquals(Harness.CUSTOMER_ID, refreshed.externalId)
        assertEquals(mapOf("plan" to "gold"), refreshed.tags)
        // Another customer: an account switch, which drops the previous customer's tags (clearTagsOnLogout).
        val switched = rightAfterAndApplied { SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2) }
        assertEquals(Harness.CUSTOMER_ID_2, switched.externalId)
        assertTrue(switched.tags.isEmpty())
    }

    @Test
    fun test_aGuestsLaunchLogout_keepsItsTags_atOnce() {
        h.registered()
        SallaPush.logout()
        SallaPush.User.addTag("add_to_cart", "1790000000000")
        h.advance(15.0)
        // Already signed out and not linked: logout is free and the tags stay.
        val after = rightAfterAndApplied { SallaPush.logout() }
        assertEquals(mapOf("add_to_cart" to "1790000000000"), after.tags)
        assertFalse(after.loggedIn)
    }

    @Test
    fun test_aFreshInstallsLaunchLogout_keepsTheTagsSetBeforeIt_atOnce() {
        h.healthyServer()
        h.start()
        // The identity is unknown: the launch logout keeps the tag changes made earlier in this launch.
        val after = rightAfterAndApplied {
            SallaPush.User.addTag("lang", "ar")
            SallaPush.User.addTags(mapOf("Plan" to "gold", "gone" to "1"))
            SallaPush.User.removeTag("gone")
            SallaPush.logout()
        }
        assertEquals(mapOf("lang" to "ar", "plan" to "gold"), after.tags)
        assertFalse(after.loggedIn)
    }

    @Test
    fun test_callsBeforeInit_aTagThenTheLaunchLogout_showTheTagAtOnce_andAfterAFreshInstallStarts() {
        SallaPush.User.addTag("lang", "ar")
        SallaPush.logout()
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        assertFalse(SallaPush.User.isLoggedIn)
        h.healthyServer()
        h.start()
        assertEquals(mapOf("lang" to "ar"), SallaPush.User.getTags())
        assertFalse(SallaPush.User.isLoggedIn)
    }

    @Test
    fun test_aLoginBeforeTheLaunchLogout_makesItAKnownIdentitysLogout_thatClearsTheTags_atOnce() {
        h.healthyServer()
        h.start()
        val after = rightAfterAndApplied {
            SallaPush.User.addTag("lang", "ar")
            SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
            SallaPush.logout()
        }
        assertTrue(after.tags.isEmpty())
        assertFalse(after.loggedIn)
    }

    @Test
    fun test_loginThenLogoutBeforeEitherIsSent_thenATag_thenLogoutAgain_keepsThatTag() {
        h.registered()
        SallaPush.logout()
        h.settle()
        val after = rightAfterAndApplied {
            SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
            SallaPush.logout()
            SallaPush.User.addTag("x", "1")
            SallaPush.logout()
        }
        // The first logout sends nothing, which leaves the device signed out and unlinked: the second one is free.
        assertEquals(mapOf("x" to "1"), after.tags)
        assertFalse(after.loggedIn)
        h.advance(15.0)
        assertTrue(h.net.calls(Routes.LINK).isEmpty())
        assertTrue(h.net.calls(Routes.UNLINK).isEmpty())
    }

    @Test
    fun test_anIgnoredLogin_leavesTheGettersAsTheyWere() {
        h.registered()
        // An invalid external id or token makes login() a no-op (WARN api.invalid_argument).
        val ignored = rightAfterAndApplied {
            SallaPush.login("", Harness.CUSTOMER_TOKEN)
            SallaPush.login(Harness.CUSTOMER_ID, "")
        }
        assertFalse(ignored.loggedIn)
        assertNull(ignored.externalId)
        assertTrue(rightAfterAndApplied { SallaPush.login(Harness.CUSTOMER_ID_2, Harness.CUSTOMER_TOKEN_2) }.loggedIn)
    }

    @Test
    fun test_tagRules_areThoseSallaPushApplies() {
        h.registered()
        val after = rightAfterAndApplied {
            SallaPush.User.addTags(mapOf(" Last_Login " to "x", "first name" to "invalid key", "note" to "v".repeat(300)))
            SallaPush.User.addTag("gone", "1")
            SallaPush.User.addTag("gone", "")
            SallaPush.User.removeTag("NOTE")
        }
        assertEquals(mapOf("last_login" to "x"), after.tags)
        val many = rightAfterAndApplied { SallaPush.User.addTags((1..103).associate { "k$it" to "v" }) }
        assertEquals(100, many.tags.size)
        assertTrue("an existing key is still replaced at the limit", rightAfterAndApplied { SallaPush.User.addTag("last_login", "y") }.tags["last_login"] == "y")
    }

    @Test
    fun test_tagChangesThatSendNothing_showTheSameTagsAtOnceAndOnceApplied() {
        h.registered()
        SallaPush.User.addTags(mapOf("plan" to "gold", "city" to "riyadh"))
        h.advance(11.0)
        // Adding the values the server holds, and a change taken back before it was sent.
        val after = rightAfterAndApplied {
            SallaPush.User.addTags(mapOf("plan" to "gold", "city" to "riyadh"))
            SallaPush.User.addTag("plan", "silver")
            SallaPush.User.addTag("plan", "gold")
        }
        assertEquals(mapOf("plan" to "gold", "city" to "riyadh"), after.tags)
        h.advance(30.0)
        assertEquals(1, h.net.calls(Routes.TAGS).size)
    }

    @Test
    fun test_removingTagsAFreshRowCannotHold_showsTheSameTagsAtOnceAndOnceApplied() {
        h.registered()
        val after = rightAfterAndApplied {
            SallaPush.User.addTag("plan", "gold")
            SallaPush.User.removeTag("plan")
            SallaPush.User.removeTags(listOf("never_set", "PROMO"))
        }
        assertTrue(after.tags.isEmpty())
        h.advance(30.0)
        assertTrue(h.net.calls(Routes.TAGS).isEmpty())
    }

    @Test
    fun test_callsBeforeInit_areShown_andStayAfterInit() {
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        assertTrue(SallaPush.User.isLoggedIn)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
        h.healthyServer()
        h.start()
        assertTrue(SallaPush.User.isLoggedIn)
        assertEquals(mapOf("plan" to "gold"), SallaPush.User.getTags())
    }

    @Test
    fun test_callsDroppedFromAFullPreInitBuffer_areNotShown() {
        for (index in 0 until 150) SallaPush.User.addTag("k$index", "v")
        h.settle()
        // The buffer keeps the last 100 calls; the getters show what it kept, which is what SallaPush applies.
        val buffered = SallaPush.User.getTags()
        assertEquals((50 until 150).map { "k$it" }.toSet(), buffered.keys)
        h.healthyServer()
        h.start()
        assertEquals(buffered, SallaPush.User.getTags())
    }

    // ── Inside the permission callback, the getters already show the answer ──

    @Test
    fun test_insideThePermissionCallback_permissionAndOptedInAgreeWithTheAnswer() {
        h.permission = false
        h.registered()
        assertFalse(SallaPush.User.pushSubscription.optedIn)
        var answer: Boolean? = null
        var permissionInCallback: Boolean? = null
        var optedInInCallback: Boolean? = null
        SallaPush.Notifications.requestPermission(false) { granted ->
            answer = granted
            permissionInCallback = SallaPush.Notifications.permission
            optedInInCallback = SallaPush.User.pushSubscription.optedIn
        }
        grantInThePrompt()
        assertEquals(true, answer)
        assertEquals(true, permissionInCallback)
        assertEquals(true, optedInInCallback)
    }

    @Test
    fun test_afterTheSuspendFormResumes_permissionAndOptedInAgreeWithTheAnswer() {
        h.permission = false
        h.registered()
        var result: Boolean? = null
        var optedInAtResume: Boolean? = null
        suspend { SallaPush.Notifications.requestPermissionSuspend(false) }
            .startCoroutine(Continuation(EmptyCoroutineContext) {
                result = it.getOrNull()
                optedInAtResume = SallaPush.User.pushSubscription.optedIn
            })
        grantInThePrompt()
        assertEquals(true, result)
        assertEquals(true, optedInAtResume)
    }

    /** The Android 13 prompt is shown and the user allows it. */
    private fun grantInThePrompt() {
        val started = shadowOf(h.context as Application).nextStartedActivity ?: throw AssertionError("no prompt")
        val activity = Robolectric.buildActivity(SallaPushPermissionActivity::class.java, started).create().get()
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        h.permission = true
        activity.onRequestPermissionsResult(
            SallaPushPermissionActivity.REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        h.settle()
    }
}
