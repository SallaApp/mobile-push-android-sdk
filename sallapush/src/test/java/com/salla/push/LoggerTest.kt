package com.salla.push

import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.LogcatWriter
import com.salla.push.internal.log.Logger
import com.salla.push.internal.log.Meta
import com.salla.push.testing.FakeClock
import com.salla.push.testing.LogCapture
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoggerTest : SdkTestBase() {

    private val lines = CopyOnWriteArrayList<String>()
    private val logger = Logger(FakeClock()) { level, line -> lines.add("$level $line") }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(2)
        }
    }

    @Test
    fun test_defaultWarn_onceTheLevelIsKnown() {
        logger.setManifestLevel(null)
        logger.log(LogCode.REGISTER_OK) { "info" }
        logger.log(LogCode.TOKEN_INVALID) { "warn" }
        logger.log(LogCode.REGISTER_DORMANT) { "error" }
        assertEquals(listOf("WARN token.invalid: warn", "ERROR register.dormant: error"), lines)
    }

    @Test
    fun test_failureLines_nameTheRequestIdStatusAndCode_whenTheMessageDoesNot() {
        logger.setManifestLevel(null)
        val rejected = mapOf(Meta.HTTP_STATUS to "403", Meta.ERROR_CODE to "invalid_customer_token", Meta.REQUEST_ID to "req-403")
        logger.log(LogCode.LOGIN_REJECTED, rejected) { "The Store API rejected the customer token (403 invalid_customer_token)." }
        logger.log(LogCode.NET_REDIRECT_REFUSED, mapOf(Meta.HTTP_STATUS to "302", Meta.REQUEST_ID to "req-302")) { "Redirect refused." }
        val limited = mapOf(Meta.HTTP_STATUS to "429", Meta.ERROR_CODE to "rate_limited", Meta.REQUEST_ID to "req-429")
        logger.log(LogCode.NET_RATE_LIMITED, limited) { "Rate limited (429 rate_limited, request req-429); retry in 60 s." }
        logger.log(LogCode.TOKEN_INVALID, mapOf(Meta.APP_ID to "st_1")) { "No failure details." }
        assertEquals(
            listOf(
                "WARN login.rejected: The Store API rejected the customer token (403 invalid_customer_token). [request req-403]",
                "ERROR net.redirect_refused: Redirect refused. [HTTP 302, request req-302]",
                "WARN net.rate_limited: Rate limited (429 rate_limited, request req-429); retry in 60 s.",
                "WARN token.invalid: No failure details.",
            ),
            lines,
        )
    }

    @Test
    fun test_aConfigurationWithoutALevel_clearsAnEarlierExplicitOne_withoutFlushingEarly() {
        logger.log(LogCode.REGISTER_OK) { "early" }
        logger.setConfigLevel(null)
        assertTrue(lines.isEmpty())
        logger.setConfigLevel(SallaPushLogLevel.INFO)
        assertEquals(listOf("INFO register.ok: early"), lines)
        logger.setConfigLevel(null)
        assertEquals(SallaPushLogLevel.WARN, logger.consoleLevel)
    }

    @Test
    fun test_earlyEntries_heldUntilTheLevelIsKnown_atMost50() {
        repeat(60) { index -> logger.log(LogCode.REGISTER_OK) { "early $index" } }
        assertTrue(lines.isEmpty())
        logger.setManifestLevel(SallaPushLogLevel.INFO)
        assertEquals(50, lines.size)
        assertEquals("INFO register.ok: early 10", lines.first())
    }

    @Test
    fun test_precedence_runtimeOverConfigOverManifest() {
        logger.setManifestLevel(SallaPushLogLevel.ERROR)
        assertEquals(SallaPushLogLevel.ERROR, logger.consoleLevel)
        logger.setConfigLevel(SallaPushLogLevel.INFO)
        assertEquals(SallaPushLogLevel.INFO, logger.consoleLevel)
        logger.setRuntimeLevel(SallaPushLogLevel.DEBUG)
        assertEquals(SallaPushLogLevel.DEBUG, logger.consoleLevel)
        logger.setConfigLevel(SallaPushLogLevel.VERBOSE)
        assertEquals(SallaPushLogLevel.DEBUG, logger.consoleLevel)
    }

    @Test
    fun test_offSilencesTheConsoleOnly_listenerKeepsItsOwnLevel() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        val events = CopyOnWriteArrayList<SallaPushLogEvent>()
        logger.addListener({ events.add(it) }, SallaPushLogLevel.INFO)
        logger.log(LogCode.REGISTER_OK, mapOf("device_id" to "d1")) { "registered" }
        logger.log(LogCode.TOKEN_UNCHANGED) { "debug only" }
        waitUntil { events.size == 1 }
        assertTrue(lines.isEmpty())
        val event = events.single()
        assertEquals("register.ok", event.code)
        assertEquals("registration", event.category)
        assertEquals(SallaPushLogLevel.INFO, event.level)
        assertEquals(mapOf("device_id" to "d1"), event.metadata)
    }

    @Test
    fun test_levelCheckRunsBeforeTheMessageIsBuilt() {
        logger.setRuntimeLevel(SallaPushLogLevel.ERROR)
        var built = 0
        logger.log(LogCode.REGISTER_OK) {
            built += 1
            "x"
        }
        assertEquals(0, built)
    }

    @Test
    fun test_failingListener_doesNotStopOthers_isReportedOnce_andRemovalWorks() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        val capture = LogCapture().also { logger.addSink(it) }
        val received = CopyOnWriteArrayList<String>()
        val failing = SallaPushLogListener { throw IllegalStateException("app bug for customer@example.com") }
        logger.addListener(failing, SallaPushLogLevel.WARN)
        logger.addListener({ received.add(it.code) }, SallaPushLogLevel.WARN)
        logger.log(LogCode.TOKEN_INVALID) { "a" }
        logger.log(LogCode.TOKEN_INVALID) { "b" }
        // Both entries reach the other listener. The failing one is reported once, as ERROR api.callback_threw: its
        // report reaches the listeners too, and the failing listener throwing at it is not reported again.
        waitUntil { received.count { it == "token.invalid" } == 2 && "api.callback_threw" in received }
        val report = capture.of(LogCode.API_CALLBACK_THREW).single()
        assertEquals(SallaPushLogLevel.ERROR, report.code.level)
        assertTrue(report.message, report.message.startsWith("The listener passed to Debug.addLogListener threw IllegalStateException at com.salla.push.LoggerTest"))
        assertFalse(report.message.contains("customer@example.com"))
        logger.removeListener(failing)
        logger.log(LogCode.TOKEN_INVALID) { "c" }
        waitUntil { received.count { it == "token.invalid" } == 3 }
        assertEquals(1, received.count { it == "api.callback_threw" })
        assertEquals(1, capture.count(LogCode.API_CALLBACK_THREW))
    }

    @Test
    fun test_concurrentAddAndRemove_neverLeaveARegisteredListenerWithoutItsEntries() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        val missed = java.util.concurrent.atomic.AtomicInteger()
        // A lost update is rare in any one round: 200 rounds make it show up in every run.
        repeat(200) {
            val churners = 6
            val start = java.util.concurrent.CyclicBarrier(churners + 1)
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val threads = (0 until churners).map {
                Thread {
                    val own = SallaPushLogListener { }
                    start.await()
                    while (running.get()) {
                        logger.addListener(own, SallaPushLogLevel.WARN)
                        logger.removeListener(own)
                    }
                }.apply { start() }
            }
            val stayer = SallaPushLogListener { }
            start.await()
            logger.addListener(stayer, SallaPushLogLevel.VERBOSE)
            // While other threads keep adding and removing their own listeners, the registered one always gets its entries.
            repeat(2_000) { if (!logger.wants(SallaPushLogLevel.VERBOSE)) missed.incrementAndGet() }
            running.set(false)
            threads.forEach { it.join() }
            if (!logger.wants(SallaPushLogLevel.VERBOSE)) missed.incrementAndGet()
            logger.removeListener(stayer)
        }
        assertEquals(0, missed.get())
        val received = CopyOnWriteArrayList<String>()
        logger.addListener({ received.add(it.code) }, SallaPushLogLevel.VERBOSE)
        logger.log(LogCode.TOKEN_UNCHANGED) { "debug entry" }
        waitUntil { received.size == 1 }
    }

    @Test
    fun test_listenerQueueBoundedAt500_reportsDropped() {
        val capture = LogCapture()
        logger.addSink(capture)
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val received = CopyOnWriteArrayList<SallaPushLogEvent>()
        logger.addListener({ event ->
            if (received.isEmpty()) {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            received.add(event)
        }, SallaPushLogLevel.VERBOSE)
        logger.log(LogCode.TOKEN_INVALID) { "first" }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // The listener holds the first entry: 500 fit in the queue, and each of the other 200 drops the oldest.
        repeat(700) { index -> logger.log(LogCode.TOKEN_INVALID) { "entry $index" } }
        gate.countDown()
        waitUntil { received.any { it.code == "log.dropped" } }
        val dropped = received.first { it.code == "log.dropped" }
        val count = dropped.metadata["count"]?.toInt() ?: 0
        assertEquals(200, count)
        assertEquals(SallaPushLogLevel.WARN, dropped.level)
        assertEquals(1, received.count { it.code == "log.dropped" })
        waitUntil { received.size == 701 - count + 1 }
        assertEquals(1, capture.count(LogCode.LOG_DROPPED))
    }

    @Test
    fun test_aSingleDroppedEntry_isSaidInTheSingular() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val received = CopyOnWriteArrayList<SallaPushLogEvent>()
        logger.addListener({ event ->
            if (received.isEmpty()) {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            received.add(event)
        }, SallaPushLogLevel.VERBOSE)
        logger.log(LogCode.TOKEN_INVALID) { "first" }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // The listener holds the first entry: 500 fit in the queue, the 501st drops the oldest.
        repeat(501) { index -> logger.log(LogCode.TOKEN_INVALID) { "entry $index" } }
        gate.countDown()
        waitUntil { received.any { it.code == "log.dropped" } }
        val dropped = received.first { it.code == "log.dropped" }
        assertEquals("The log listener fell behind; 1 entry was dropped.", dropped.message)
        assertEquals("1", dropped.metadata["count"])
    }

    // ── The first listener also gets the warnings and errors logged before it (as the first iOS handler does) ──

    @Test
    fun test_earlyWarningsAndErrors_reachTheFirstListener_filteredByItsLevel_beforeLaterEntries() {
        logger.setManifestLevel(null)
        logger.log(LogCode.TOKEN_INVALID) { "early warning" }
        logger.log(LogCode.REGISTER_OK) { "early info" }
        logger.log(LogCode.REGISTER_DORMANT) { "early error" }
        val received = CopyOnWriteArrayList<String>()
        logger.addListener({ received.add("${it.code}: ${it.message}") }, SallaPushLogLevel.ERROR)
        logger.log(LogCode.NET_CLIENT_ERROR) { "later error" }
        waitUntil { received.size == 2 }
        assertEquals(listOf("register.dormant: early error", "net.client_error: later error"), received)
    }

    @Test
    fun test_earlyEntries_onlyTheFirst100Warnings_onlyForTheFirstListener() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        repeat(120) { index -> logger.log(LogCode.TOKEN_INVALID) { "warning $index" } }
        logger.log(LogCode.TOKEN_UNCHANGED) { "debug" }
        logger.log(LogCode.REGISTER_OK) { "info" }
        val first = CopyOnWriteArrayList<String>()
        logger.addListener({ first.add(it.message) }, SallaPushLogLevel.VERBOSE)
        waitUntil { first.size == 100 }
        assertEquals((0 until 100).map { "warning $it" }, first.toList())
        val second = CopyOnWriteArrayList<String>()
        logger.addListener({ second.add(it.message) }, SallaPushLogLevel.VERBOSE)
        logger.log(LogCode.TOKEN_INVALID) { "after" }
        waitUntil { second.size == 1 && first.size == 101 }
        assertEquals(listOf("after"), second.toList())
    }

    @Test
    fun test_earlyEntries_goToTheFirstListenerOnly_evenWhenASecondIsAddedBeforeTheyAreDelivered() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        repeat(3) { index -> logger.log(LogCode.TOKEN_INVALID) { "early $index" } }
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val first = CopyOnWriteArrayList<String>()
        logger.addListener({ event ->
            if (first.isEmpty()) {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            first.add(event.message)
        }, SallaPushLogLevel.WARN)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // The first listener holds its first early entry while a second listener is added.
        val second = CopyOnWriteArrayList<String>()
        logger.addListener({ second.add(it.message) }, SallaPushLogLevel.WARN)
        logger.log(LogCode.TOKEN_INVALID) { "after" }
        gate.countDown()
        waitUntil { first.size == 4 && second.size == 1 }
        assertEquals(listOf("early 0", "early 1", "early 2", "after"), first.toList())
        assertEquals(listOf("after"), second.toList())
    }

    @Test
    fun test_withNoSinkTakingThem_messagesAreBuiltOnlyWhileTheyCanBeKept() {
        logger.setRuntimeLevel(SallaPushLogLevel.OFF)
        var built = 0
        repeat(105) {
            logger.log(LogCode.TOKEN_INVALID) {
                built += 1
                "w"
            }
        }
        logger.log(LogCode.REGISTER_OK) {
            built += 1
            "info"
        }
        assertEquals(100, built)
    }

    @Test
    fun test_entriesLoggedWhileTheFirstListenerIsAdded_eachArriveOnce_inOrder_afterTheEarlyOnes() {
        repeat(100) { round ->
            val logger = Logger(FakeClock()) { _, _ -> }
            logger.setRuntimeLevel(SallaPushLogLevel.OFF)
            repeat(5) { index -> logger.log(LogCode.TOKEN_INVALID) { "early $index" } }
            val received = CopyOnWriteArrayList<String>()
            val start = java.util.concurrent.CyclicBarrier(2)
            val writer = Thread {
                start.await()
                repeat(90) { index -> logger.log(LogCode.TOKEN_INVALID) { "late $index" } }
            }.apply { start() }
            start.await()
            logger.addListener({ received.add(it.message) }, SallaPushLogLevel.WARN)
            writer.join()
            waitUntil { received.size >= 95 }
            val expected = (0 until 5).map { "early $it" } + (0 until 90).map { "late $it" }
            assertEquals("round $round", expected, received.toList())
        }
    }

    @Test
    fun test_logcatWriter_usesTheSallaPushTag() {
        ShadowLog.clear()
        LogcatWriter.write(SallaPushLogLevel.WARN, "token.invalid: x")
        val logged = ShadowLog.getLogsForTag("SallaPush")
        assertEquals(1, logged.size)
        assertEquals("token.invalid: x", logged[0].msg)
    }

    @Test
    fun test_debugFacade_setsTheRuntimeLevel() {
        SallaPush.Debug.logLevel = SallaPushLogLevel.VERBOSE
        assertEquals(SallaPushLogLevel.VERBOSE, h.logger.consoleLevel)
        val events = CopyOnWriteArrayList<SallaPushLogEvent>()
        val listener = SallaPushLogListener { events.add(it) }
        SallaPush.Debug.addLogListener(listener)
        h.logger.log(LogCode.TOKEN_INVALID) { "x" }
        h.logger.log(LogCode.REGISTER_OK) { "y" }
        waitUntil { events.size == 1 }
        SallaPush.Debug.removeLogListener(listener)
        SallaPush.Debug.addLogListener(listener, SallaPushLogLevel.VERBOSE)
        h.logger.log(LogCode.REGISTER_OK) { "z" }
        waitUntil { events.size == 2 }
    }
}
