package com.salla.push

import com.salla.push.internal.lifecycle.AndroidProcessInfo
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessTest : SdkTestBase() {

    @Test
    fun test_nonMainProcess_isANoOp() {
        h.mainProcess = false
        h.healthyServer()
        h.start()
        h.deliverToken()
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.User.addTag("plan", "gold")
        h.advance(30.0)
        assertTrue(h.capture.has(LogCode.INIT_NOT_MAIN_PROCESS))
        assertTrue(h.net.calls.isEmpty())
        assertFalse(File(h.root, "sallapush").exists() && File(h.root, "sallapush").walkTopDown().any { it.isFile })
        assertTrue(h.core.registry.all().isEmpty())
    }

    @Test
    fun test_nonMainProcess_isNotInitialized_andStaysQuiet_unlikeAnInvalidAppId() {
        h.mainProcess = false
        h.init()
        assertFalse(SallaPush.isInitialized)
        SallaPush.User.addTag("plan", "gold")
        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN)
        SallaPush.initWithContext(h.context, Harness.APP_ID)
        h.settle()
        assertFalse(SallaPush.isInitialized)
        assertTrue(SallaPush.User.getTags().isEmpty())
        // A normal multi-process state, not a configuration error: DEBUG lines only, nothing at WARN or ERROR.
        assertEquals(emptyList<String>(), h.console.toList())
        assertFalse(h.capture.has(LogCode.API_INVALID_ARGUMENT))
        val notMain = h.capture.of(LogCode.INIT_NOT_MAIN_PROCESS).map { it.message }
        assertEquals(
            listOf("Not the main process; SallaPush only shows notifications here.", "addTag ignored: this is not the app's main process; SallaPush only shows notifications here."),
            notMain,
        )
    }

    @Test
    fun test_realProcessCheck_onTheMainProcess() {
        assertTrue(AndroidProcessInfo(h.context).isMainProcess())
    }
}
