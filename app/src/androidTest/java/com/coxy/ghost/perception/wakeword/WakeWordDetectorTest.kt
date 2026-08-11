package com.coxy.ghost.perception.wakeword

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
