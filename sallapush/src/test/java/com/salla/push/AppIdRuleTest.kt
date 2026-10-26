package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Http
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdRuleTest : SdkTestBase() {

    /** The id the server creates for an app when it is given none: a random UUID. */
    private val uuidAppId = "3b241101-e2bb-4255-8caf-4136c566a962"

    @Test
    fun test_aUuidAppId_initializes_andRegistersUnderIt() {
        h.registered(uuidAppId)
        assertEquals(listOf("${Routes.DEFAULT_BASE_URL}/apps/$uuidAppId/devices"), h.net.calls(Routes.REGISTER).map { it.url })
        assertTrue(SallaPush.isInitialized)
        assertEquals(uuidAppId, SallaPush.appId)
        assertEquals(Http.DEVICE_ID, SallaPush.User.pushSubscription.id)
    }

    @Test
    fun test_mixedCaseAndDashes_areAccepted() {
        h.registered("Store-42_A")
        assertEquals(listOf("${Routes.DEFAULT_BASE_URL}/apps/Store-42_A/devices"), h.net.calls(Routes.REGISTER).map { it.url })
        assertTrue(SallaPush.isInitialized)
        assertEquals("Store-42_A", SallaPush.appId)
    }

    @Test
    fun test_invalidIds_areStillRefused_withTheNewRule() {
        h.registered()
        for (appId in listOf("a b", "a".repeat(65), "é", "st.42")) SallaPush.initWithContext(h.context, appId)
        h.settle()
        val refused = "is invalid (expected 1-64 chars A-Z, a-z, 0-9, _, -); this call was ignored, and SallaPush keeps running with app st_42_android."
        assertEquals(
            listOf("a b", "a".repeat(64), "?", "st.42").map { shown -> "App id \"$shown\" $refused" },
            h.capture.of(LogCode.INIT_INVALID_APP_ID).map { it.message },
        )
        assertEquals(Harness.APP_ID, SallaPush.appId)
        assertEquals(1, h.net.calls(Routes.REGISTER).size)
    }
}
