package com.coxy.ghost.perception.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the wake-word trigger decision.
 *
 * This is the logic that decides whether a probability stream counts as "Hey Ghost", and until now
 * it was three lines inlined in [com.coxy.ghost.perception.MicListener]'s audio loop — untestable
 * without a device, a mic and a human voice, which is exactly why the Phase 5.2 gate stayed open.
 * Extracted and pinned here so the *decision* is provable on the Mac in milliseconds; only the
 * acoustics still need the device.
 */
class WakeTriggerTest {

    private val t0 = 1_000_000L

    /** Feed [n] frames at [prob], 50 ms apart, returning how many fired. */
    private fun WakeTrigger.feed(n: Int, prob: Float, startMs: Long = t0, stepMs: Long = 50L): Int {
        var fires = 0
        for (i in 0 until n) if (offer(prob, startMs + i * stepMs)) fires++
        return fires
    }

    @Test
    fun `single high frame does not fire`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3)
        assertFalse("one spurious frame must not hand the mic to the recognizer", trigger.offer(0.95f, t0))
    }

    @Test
    fun `fires on the frame that completes the streak`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3)
        assertFalse(trigger.offer(0.95f, t0))
        assertFalse(trigger.offer(0.95f, t0 + 50))
        assertTrue("third consecutive frame should fire", trigger.offer(0.95f, t0 + 100))
    }

    @Test
    fun `a frame below threshold breaks the streak`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3)
        trigger.offer(0.95f, t0)
        trigger.offer(0.95f, t0 + 50)
        trigger.offer(0.10f, t0 + 100)          // dip — streak restarts
        assertFalse(trigger.offer(0.95f, t0 + 150))
        assertFalse(trigger.offer(0.95f, t0 + 200))
        assertTrue(trigger.offer(0.95f, t0 + 250))
    }

    @Test
    fun `sustained high audio fires once per cooldown, not once per frame`() {
        // A real utterance holds the 1 s window high across ~20 overlapping frames. Without the
        // cooldown that is 18 triggers, each one stopping AudioRecord and cycling the audio HAL.
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3, cooldownMs = 2000L)
        val fires = trigger.feed(n = 20, prob = 0.99f)   // 20 frames = 1 s of audio
        assertEquals("one utterance should be one trigger", 1, fires)
    }

    @Test
    fun `fires again after the cooldown expires`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3, cooldownMs = 2000L)
        assertEquals(1, trigger.feed(n = 3, prob = 0.99f, startMs = t0))
        assertEquals(1, trigger.feed(n = 3, prob = 0.99f, startMs = t0 + 3000))
    }

    @Test
    fun `requiredFrames of 1 reproduces the old single-frame behaviour`() {
        // The tuning range must still be able to express what shipped, so a gate run can compare
        // against the old behaviour without reinstalling an old build.
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 1)
        assertTrue(trigger.offer(0.81f, t0))
    }

    @Test
    fun `probability exactly at threshold counts as over`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 1)
        assertTrue("threshold is inclusive", trigger.offer(0.8f, t0))
    }

    @Test
    fun `peak tracks the highest probability seen and resets on demand`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3)
        trigger.offer(0.20f, t0)
        trigger.offer(0.65f, t0 + 50)
        trigger.offer(0.31f, t0 + 100)
        assertEquals(0.65f, trigger.peakProb, 1e-6f)
        trigger.resetPeak()
        assertEquals(0f, trigger.peakProb, 1e-6f)
    }

    @Test
    fun `near miss window covers the band worth logging`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3)
        assertTrue("0.60 is a near miss at threshold 0.80", trigger.isNearMiss(0.60f))
        assertFalse("a frame over threshold is a hit, not a near miss", trigger.isNearMiss(0.85f))
        assertFalse("background noise is not a near miss", trigger.isNearMiss(0.05f))
    }

    @Test
    fun `armCooldown suppresses an immediate re-fire`() {
        // resumeKws() arms this after a command capture: the recognizer just held the mic for the
        // whole cooldown, so without re-arming the detector could fire on its first frame back.
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3, cooldownMs = 2000L)
        trigger.armCooldown(t0)
        assertEquals(0, trigger.feed(n = 10, prob = 0.99f, startMs = t0 + 100))
        assertEquals(1, trigger.feed(n = 3, prob = 0.99f, startMs = t0 + 2500))
    }

    @Test
    fun `resetStreak drops progress without touching the cooldown`() {
        val trigger = WakeTrigger(threshold = 0.8f, requiredFrames = 3)
        trigger.offer(0.99f, t0)
        trigger.offer(0.99f, t0 + 50)
        trigger.resetStreak()
        assertEquals(0, trigger.streak)
        assertFalse("streak restarted, so this is only frame 1 of 3", trigger.offer(0.99f, t0 + 100))
    }

    @Test
    fun `defaults match the values that shipped in v0_9_2`() {
        assertEquals(0.80f, WakeTrigger.DEFAULT_THRESHOLD, 1e-6f)
        assertEquals(2000L, WakeTrigger.DEFAULT_COOLDOWN_MS)
    }
}
