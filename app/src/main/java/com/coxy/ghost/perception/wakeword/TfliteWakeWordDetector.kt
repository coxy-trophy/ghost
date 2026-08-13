package com.coxy.ghost.perception.wakeword

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Phase 5.2 TFLite wake-word detector for "Hey Ghost".
 *
 * Loads `assets/hey_ghost.tflite` (waveform -> spectrogram -> logits) and runs a 1-second
 * sliding window over 16 kHz mono PCM. Returns the wake-word probability on every chunk.
 *
 * The interpreter is driven from a single audio-thread, so no extra synchronization is needed.
 */
class TfliteWakeWordDetector : WakeWordDetector {

    companion object {
        private const val TAG = "TfliteWakeWordDetector"
        private const val MODEL_ASSET = "hey_ghost.tflite"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_MS = 1000
        private const val SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000  // 16000
        private const val WAKE_LOGIT_INDEX = 1
    }

    private var interpreter: Interpreter? = null
    private var inputShape: IntArray? = null
    private var outputShape: IntArray? = null
    private var outputIndex = 0

    private val ring = ShortArray(SAMPLES)
    private var ringPos = 0
    private var ringFilled = false

    private val inputBuf = ByteBuffer.allocateDirect(SAMPLES * 4).order(ByteOrder.nativeOrder())
    private var outputBuf: ByteBuffer? = null

    @Synchronized
    override fun init(context: Context): Boolean {
        release()
        val modelFile = File(context.filesDir, MODEL_ASSET)
        try {
            val assetFd = context.assets.openFd(MODEL_ASSET)
            if (!modelFile.exists() || modelFile.length() != assetFd.length) {
                context.assets.open(MODEL_ASSET).use { input ->
                    modelFile.outputStream().use { input.copyTo(it) }
                }
            }
            assetFd.close()
        } catch (e: Exception) {
            Log.w(TAG, "wake-word model asset not found — KWS disabled", e)
            return false
        }

        return try {
            val opts = Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)
            val interp = Interpreter(modelFile, opts)
            interpreter = interp

            val inputTensor = interp.getInputTensor(0)
            inputShape = inputTensor.shape()
            val outputTensor = interp.getOutputTensor(0)
            outputShape = outputTensor.shape()
            outputIndex = runCatching { interp.getOutputIndex(outputTensor.name()) }.getOrDefault(0)

            // Prepare output buffer sized to the actual output shape.
            val outSize = outputShape?.filter { it > 0 }?.reduce { a, b -> a * b } ?: 2
            outputBuf = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())

            Log.i(TAG, "loaded KWS model; in=${inputShape?.contentToString()} out=${outputShape?.contentToString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to load wake-word model", e)
            false
        }
    }

    override fun process(samples: ShortArray): Float {
        val interp = interpreter ?: return 0f

        // Append to the ring buffer.
        for (s in samples) {
            ring[ringPos] = s
            ringPos++
            if (ringPos >= SAMPLES) {
                ringPos = 0
                ringFilled = true
            }
        }

        if (!ringFilled) return 0f

        // Copy the most recent SAMPLES into a float input buffer, normalising int16 -> [-1, 1].
        inputBuf.clear()
        var i = ringPos
        var written = 0
        while (written < SAMPLES) {
            inputBuf.putFloat(ring[i] / 32768.0f)
            written++
            i++
            if (i >= SAMPLES) i = 0
        }
        inputBuf.flip()

        // Run inference. Use the byte buffer directly if the interpreter accepts it.
        val outputs = HashMap<Int, Any>().apply { put(outputIndex, outputBuf!!) }
        interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

        outputBuf!!.rewind()
        val outFloatBuf = outputBuf!!.asFloatBuffer()
        val logits = FloatArray(outFloatBuf.remaining())
        outFloatBuf.get(logits)

        return if (logits.size > WAKE_LOGIT_INDEX) {
            softmax(logits)[WAKE_LOGIT_INDEX]
        } else {
            0f
        }
    }

    /**
     * Clear the audio ring only. The interpreter stays loaded — this runs after every command
     * capture, and rebuilding the interpreter there (which is what re-calling [init] used to do)
     * put a model load on the mic thread on every single wake.
     */
    override fun reset() {
        ring.fill(0)
        ringPos = 0
        ringFilled = false
    }

    @Synchronized
    override fun release() {
        runCatching { interpreter?.close() }
        interpreter = null
        ringFilled = false
        ringPos = 0
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        var sum = 0.0
        val exps = DoubleArray(logits.size)
        for (i in logits.indices) {
            val v = exp((logits[i] - max).toDouble())
            exps[i] = v
            sum += v
        }
        val inv = if (sum > 0.0) 1.0 / sum else 0.0
        return FloatArray(logits.size) { i -> (exps[i] * inv).toFloat() }
    }
}
