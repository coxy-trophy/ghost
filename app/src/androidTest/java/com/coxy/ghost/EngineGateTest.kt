package com.coxy.ghost

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine is single-threaded and three paths drive it (typed, voice, fact extraction), so the
 * gate admitting two callers corrupts the shared conversation context.
 */
@RunWith(AndroidJUnit4::class)
class EngineGateTest {

    @Before
    fun reset() = EngineGate.release()

    @After
    fun cleanup() = EngineGate.release()

    @Test
    fun tryAcquire_isExclusiveUntilReleased() {
        assertTrue(EngineGate.tryAcquire())
        assertTrue(EngineGate.busy)
        assertFalse("a second acquire must fail while the gate is held", EngineGate.tryAcquire())

        EngineGate.release()
        assertFalse(EngineGate.busy)
        assertTrue("the gate must be reusable after release", EngineGate.tryAcquire())
        EngineGate.release()
    }

    /**
     * The regression this guards: with `if (busy) return; busy = true` spread across three threads,
     * two callers could both observe false and both proceed into the engine.
     */
    @Test
    fun tryAcquire_admitsExactlyOneUnderContention() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(50) {
                EngineGate.release()
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                val winners = AtomicInteger(0)
                repeat(threads) {
                    pool.execute {
                        start.await()
                        if (EngineGate.tryAcquire()) winners.incrementAndGet()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue("round timed out", done.await(5, TimeUnit.SECONDS))
                assertEquals("exactly one caller may hold the engine", 1, winners.get())
            }
        } finally {
            pool.shutdownNow()
            EngineGate.release()
        }
    }
}
