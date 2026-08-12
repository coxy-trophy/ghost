package com.coxy.ghost.perception

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ContextStore is the one bus every perception modality writes to, from six different threads.
 * A read-modify-write here loses whole fields — a sensor bucket, a transcript, a frame timestamp —
 * without any error, and the loss only shows up later as a [CONTEXT] block that disagrees with what
 * the device actually sensed.
 */
@RunWith(AndroidJUnit4::class)
class ContextStoreTest {

    @Before
    fun reset() = ContextStore.reset()

    @After
    fun cleanup() = ContextStore.reset()

    @Test
    fun update_doesNotLoseConcurrentWritesToOneField() {
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    ContextStore.update { s -> s.copy(voiceCommandSeq = s.voiceCommandSeq + 1L) }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("writers timed out", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(
            "every increment must survive — a lost update means a dropped perception field",
            (threads * perThread).toLong(),
            ContextStore.state.value.voiceCommandSeq
        )
    }

    @Test
    fun update_doesNotLoseWritesToIndependentFields() {
        val threads = 6
        val perThread = 300
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        // Two writers touching different fields, as the mic and the service coroutine do.
        repeat(threads) { idx ->
            pool.execute {
                start.await()
                repeat(perThread) {
                    if (idx % 2 == 0) {
                        ContextStore.update { s -> s.copy(voiceCommandSeq = s.voiceCommandSeq + 1L) }
                    } else {
                        ContextStore.update { s -> s.copy(stepRatePerMin = s.stepRatePerMin + 1) }
                    }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("writers timed out", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val expected = (threads / 2 * perThread)
        assertEquals(expected.toLong(), ContextStore.state.value.voiceCommandSeq)
        assertEquals(expected, ContextStore.state.value.stepRatePerMin)
    }
}
