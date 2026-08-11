package com.coxy.ghost.memory

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * The on-device sentence embedder for Phase 6 memory (TFLite, no native rebuild of `:lib`).
 *
 * Runs `assets/embedder.tflite` — the INT8-quantised `all-MiniLM-L6-v2` (384-dim, pooled output)
 * — via a raw [Interpreter]. Tokenisation is done by [BertTokenizer] (the MiniLM TFLite takes
 * pre-tokenised `input_ids` + `attention_mask`, it does not tokenise internally). Output is
 * L2-normalised so cosine similarity is a plain dot product in [MemoryStore].
 *
 * The [Interpreter] is not thread-safe, so [embed] synchronises on the instance. Callers drive it
 * on a single-threaded dispatcher ([MemoryStore.embedDispatcher]) — never the engine's
 * `llamaDispatcher` — so embedding never contends with inference.
 *
 * The HF quant model's output tensor is FLOAT32 (the conversion keeps the embedding head
 * unquantised — the reference Python example L2-normalises it directly), so we read it as float.
 * If a future model emits a quantised output, [init] logs the dtype and recall will visibly fail
 * the gate — at which point dequant is added with the tensor's scale/zero-point.
 */
object Embedder {
    private const val TAG = "Embedder"
    private const val MODEL_ASSET = "embedder.tflite"
    private const val VOCAB_ASSET = "vocab.txt"
    private const val MAX_LEN = 128

    @Volatile private var ready = false
    private var interpreter: Interpreter? = null
    private var tokenizer: BertTokenizer? = null
    private lateinit var inputIdsIdx: IntArray   // [input_ids index, attention_mask index]
    private var outputIdx = 0
    private var outputDim = 384

    /** Copy the model asset to a file (Interpreter takes a File) + load the vocab. Idempotent. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        try {
            val modelFile = File(context.filesDir, MODEL_ASSET)
            val assetLen = context.assets.openFd(MODEL_ASSET).length
            if (!modelFile.exists() || modelFile.length() != assetLen) {
                context.assets.open(MODEL_ASSET).use { input ->
                    modelFile.outputStream().use { input.copyTo(it) }
                }
            }
            tokenizer = BufferedReader(InputStreamReader(context.assets.open(VOCAB_ASSET)))
                .use { BertTokenizer.load(it) }

            val opts = Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)
            val interp = Interpreter(modelFile, opts)
            interpreter = interp

            // Locate the two int32 inputs by name (fall back to indices 0/1). tensorflow-lite-api
            // 2.16.1 exposes getInputTensor(i)/getOutputTensor(i) + getInputTensorCount() rather
            // than the old getInputDetails()/getOutputDetails() arrays.
            var idsIdx = 0
            var maskIdx = 1
            for (i in 0 until interp.getInputTensorCount()) {
                val nm = interp.getInputTensor(i).name() ?: continue
                when {
                    nm.contains("input_ids") -> idsIdx = i
                    nm.contains("attention_mask") -> maskIdx = i
                }
            }
            if (maskIdx == idsIdx) maskIdx = if (idsIdx == 0) 1 else 0
            inputIdsIdx = intArrayOf(idsIdx, maskIdx)

            val out = interp.getOutputTensor(0)
            // runForMultipleInputsOutputs output map is keyed by OUTPUT SLOT INDEX, not the
            // absolute tensor index returned by Tensor.index() (which can be a large graph id).
            outputIdx = runCatching { interp.getOutputIndex(out.name()) }.getOrDefault(0)
            outputDim = out.shape().filter { it > 0 }.lastOrNull() ?: 384
            if (out.dataType() != DataType.FLOAT32) {
                Log.w(TAG, "output dtype is ${out.dataType()}, not FLOAT32 — recall will likely be broken; add dequant")
            }
            ready = true
            Log.i(TAG, "ready: dim=$outputDim in=[$idsIdx,$maskIdx]")
        } catch (e: Exception) {
            Log.e(TAG, "init failed — memory recall will be disabled", e)
            ready = false
        }
    }

    /** L2-normalised embedding, or null if the embedder isn't initialised / the call fails. */
    fun embed(text: String): FloatArray? {
        val interp = interpreter ?: return null
        val tok = tokenizer ?: return null
        return synchronized(this) {
            try {
                val t = tok.encode(text, MAX_LEN)
                val inputs = arrayOfNulls<Any>(2)
                inputs[inputIdsIdx[0]] = arrayOf(t.inputIds)
                inputs[inputIdsIdx[1]] = arrayOf(t.attentionMask)

                val buf = Array(1) { FloatArray(outputDim) }
                val outputs = HashMap<Int, Any>().apply { put(outputIdx, buf) }
                interp.runForMultipleInputsOutputs(inputs, outputs)
                l2normalize(buf[0])
            } catch (e: Exception) {
                Log.w(TAG, "embed failed for '$text'", e)
                null
            }
        }
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (f in v) sum += (f * f).toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm < 1e-6f) return v
        val inv = 1f / norm
        for (i in v.indices) v[i] *= inv
        return v
    }

    fun isReady(): Boolean = ready
}