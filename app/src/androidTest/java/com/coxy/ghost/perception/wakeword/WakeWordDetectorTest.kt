package com.coxy.ghost.perception.wakeword

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5.2 instrumented gate test for the on-device wake-word detector.
 *
 * Feeds the detector a synthetic positive "Hey Ghost" clip (from androidTest/assets) and a negative
 * speech clip, and verifies that the positive clip crosses the trigger threshold while the negative
 * clip stays below it.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordDetectorTest {

    private val targetCtx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx: Context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun tflite_detector_triggers_on_hey_ghost() {
        val detector = TfliteWakeWordDetector()
        assertTrue("detector init failed (missing model asset?)", detector.init(targetCtx))

        val samples = loadMono16kWav(testCtx, "hey_ghost_test.wav").padTo16000()
        val chunkSize = 800 // 50 ms at 16 kHz
        var maxProb = 0f
        for (i in 0 until samples.size step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(samples.size)
            val chunk = samples.copyOfRange(i, end)
            val prob = detector.process(chunk)
            if (prob > maxProb) maxProb = prob
        }
        detector.release()
        assertTrue("wake-word probability too low: $maxProb", maxProb >= 0.75f)
    }

    @Test
    fun tflite_detector_stays_low_on_negative_speech() {
        val detector = TfliteWakeWordDetector()
        assertTrue("detector init failed", detector.init(targetCtx))

        val samples = loadMono16kWav(testCtx, "negative_test.wav").padTo16000()
        val chunkSize = 800
        var maxProb = 0f
        for (i in 0 until samples.size step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(samples.size)
            val chunk = samples.copyOfRange(i, end)
            val prob = detector.process(chunk)
            if (prob > maxProb) maxProb = prob
        }
        detector.release()
        assertTrue("negative clip falsely triggered: $maxProb", maxProb < 0.60f)
    }

    /**
     * After [TfliteWakeWordDetector.reset] the ring must be empty, so audio fed before the reset
     * cannot contribute to a score after it.
     *
     * This is what stops a double-trigger: `resumeKws()` hands the mic back to the detector with the
     * second of audio that just triggered it still in the ring, and re-scoring that would fire
     * again immediately. Before `reset()` existed, MicListener got the same effect by re-running
     * `init()` — a full interpreter teardown and rebuild on the mic thread on every single wake.
     */
    @Test
    fun reset_clears_buffered_audio_without_unloading_the_model() {
        val detector = TfliteWakeWordDetector()
        assertTrue("detector init failed", detector.init(targetCtx))

        val samples = loadMono16kWav(testCtx, "hey_ghost_test.wav").padTo16000()
        val chunkSize = 800
        var maxProb = 0f
        for (i in samples.indices step chunkSize) {
            val chunk = samples.copyOfRange(i, (i + chunkSize).coerceAtMost(samples.size))
            maxProb = maxOf(maxProb, detector.process(chunk))
        }
        assertTrue("positive clip should have scored high first: $maxProb", maxProb >= 0.75f)

        detector.reset()

        // One 50 ms chunk of silence. The ring needs a full second before it scores at all, so a
        // correctly cleared ring returns exactly 0f rather than re-scoring the wake word.
        val afterReset = detector.process(ShortArray(chunkSize))
        assertEquals("reset must empty the ring, not just rewind it", 0f, afterReset, 1e-6f)

        // The interpreter must still be loaded — reset is not release.
        var reProb = 0f
        for (i in samples.indices step chunkSize) {
            val chunk = samples.copyOfRange(i, (i + chunkSize).coerceAtMost(samples.size))
            reProb = maxOf(reProb, detector.process(chunk))
        }
        detector.release()
        assertTrue("detector should still work after reset: $reProb", reProb >= 0.75f)
    }

    /** Pad a short array to one second at 16 kHz so the detector's 1-second window fills. */
    private fun ShortArray.padTo16000(): ShortArray {
        if (size >= 16000) return this
        return ShortArray(16000) { i -> if (i < size) this[i] else 0 }
    }

    /** Minimal RIFF/WAV parser for 16-bit PCM mono @ 16 kHz test assets. */
    private fun loadMono16kWav(context: Context, assetName: String): ShortArray {
        context.assets.open(assetName).use { stream ->
            val header = ByteArray(44)
            stream.read(header)

            fun leInt(offset: Int, len: Int): Int {
                var v = 0
                for (i in 0 until len) {
                    v = v or ((header[offset + i].toInt() and 0xFF) shl (8 * i))
                }
                return v
            }

            val channels = leInt(22, 2)
            val sampleRate = leInt(24, 4)
            val bitsPerSample = leInt(34, 2)
            check(sampleRate == 16000) { "$assetName sample rate is $sampleRate, expected 16000" }
            check(channels == 1) { "$assetName is not mono: $channels channels" }
            check(bitsPerSample == 16) { "$assetName bits per sample is $bitsPerSample, expected 16" }

            val dataBytes = stream.readBytes()
            val shorts = ShortArray(dataBytes.size / 2)
            for (i in shorts.indices) {
                shorts[i] = ((dataBytes[2 * i].toInt() and 0xFF) or
                        ((dataBytes[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            return shorts
        }
    }
}
