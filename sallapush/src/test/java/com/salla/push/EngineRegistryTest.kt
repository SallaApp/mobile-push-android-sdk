package com.salla.push

import com.salla.push.internal.api.Routes
import com.salla.push.internal.engine.Engine
import com.salla.push.internal.engine.EngineEnv
import com.salla.push.internal.engine.EngineKey
import com.salla.push.internal.engine.EngineMode
import com.salla.push.internal.engine.EngineRegistry
import com.salla.push.internal.engine.FlushOutcome
import com.salla.push.internal.work.FlushWork
import com.salla.push.testing.FixedJitter
import com.salla.push.testing.Harness
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EngineRegistryTest : SdkTestBase() {

    @Test
    fun test_workerAndInitRacing_shareOneEngine() {
        h.registered()
        repeat(RACES) {
            val next = Harness(h.context)
            File(h.root, "sallapush").copyRecursively(File(next.root, "sallapush"), overwrite = true)
            next.clock.millis = h.clock.millis
            next.install()
            next.healthyServer()
            val go = CountDownLatch(1)
            val outcome = AtomicReference<FlushOutcome>()
            // WorkManager starts the run on its main thread while the app initialises on another one.
            val worker = Thread {
                go.await()
                FlushWork.start(next.context, Harness.APP_ID, Routes.DEFAULT_BASE_URL, false) { outcome.set(it) }
            }
            val init = Thread {
                go.await()
                SallaPush.initWithContext(next.context, Harness.APP_ID)
            }
            worker.start()
            init.start()
            go.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (worker.isAlive || init.isAlive || outcome.get() == null) {
                next.settle()
                if (System.nanoTime() > deadline) throw AssertionError("the race did not finish")
                worker.join(1)
            }
            next.advance(1.0)

            assertEquals(listOf(next.key), next.core.registry.all().map { it.key })
            assertEquals(EngineMode.MAIN, next.engine().mode)
            assertEquals(FlushOutcome.SUCCESS, outcome.get())
            assertTrue(next.net.calls(Routes.REGISTER).isEmpty())
            next.close()
        }
    }

    @Test
    fun test_racingObtains_createOneEngine() {
        val registry = EngineRegistry()
        val key = EngineKey("st_1_android", "abcd1234")
        val created = AtomicInteger()
        val seen = Collections.synchronizedSet(HashSet<Int>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(THREADS)
        repeat(THREADS) {
            Thread {
                start.await()
                val engine = registry.obtain(key) {
                    created.incrementAndGet()
                    newEngine(key)
                }
                seen.add(System.identityHashCode(engine))
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, created.get())
        assertEquals(1, seen.size)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun test_removeOnlyRemovesTheRegisteredInstance() {
        val registry = EngineRegistry()
        val key = EngineKey("st_1_android", "abcd1234")
        val engine = registry.obtain(key) { newEngine(key) }
        registry.remove(newEngine(key))
        assertTrue(registry.get(key) === engine)
        registry.remove(engine)
        assertNull(registry.get(key))
    }

    private fun newEngine(key: EngineKey): Engine = Engine(
        key,
        Routes.DEFAULT_BASE_URL,
        EngineMode.WORKER,
        EngineEnv(h.clock, FixedJitter(1.0), h.engineRunner, h.net, h.files, h.secretBox, h.logger, h.device, { true }, h.work),
        h.core,
    )

    private companion object {
        const val RACES = 20
        const val THREADS = 8
    }
}
