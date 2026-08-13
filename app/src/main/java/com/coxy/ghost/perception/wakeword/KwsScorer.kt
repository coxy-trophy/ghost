package com.coxy.ghost.perception.wakeword

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Runs the shipped wake-word model over recorded WAV clips and reports what it scored.
 *
 * This exists because the Phase 5.2 live gate has been open since 2026-08-06 with no way to tell
 * *why*. "Hey Ghost didn't wake it" has two completely different causes and opposite fixes:
 *
 *  - the model scores the clip at 0.9 but the trigger never fires — a threshold/debounce problem,
 *    fixable in seconds with ACTION_SET_KWS_TUNING; or
 *  - the model scores it at 0.05 — the model has genuinely never heard this voice, and no amount of
 *    tuning helps. The shipped `hey_ghost.tflite` is trained 100% on macOS `say` synthesis
 *    (GHOST.md §9 v0.9.3), so this is the likely answer and it means retraining.
 *
 * Scoring replays each clip through the *same* code path as live audio — [TfliteWakeWordDetector] in
 * 50 ms chunks with the same 1 s ring — so a peak here is directly comparable to a peak in logcat.
 *
 * Clips come from [KwsSampleRecorder], which captures through the same AudioRecord/HAL path the
 * detector listens on, so the acoustics match too.
 */
object KwsScorer {

    private const val TAG = "KwsScorer"

    /** 50 ms at 16 kHz — MicListener's read size, so the frame cadence matches live audio. */
    private const val CHUNK = 800

    data class ClipScore(
        val name: String,
        val peakProb: Float,
        val framesOverDefault: Int,
        val durationSec: Float,
    )

    data class Report(
        val clips: List<ClipScore>,
        val label: String,
    ) {
        val peak: Float get() = clips.maxOfOrNull { it.peakProb } ?: 0f
        val median: Float
            get() = clips.map { it.peakProb }.sorted().let {
                if (it.isEmpty()) 0f else it[it.size / 2]
            }

        /** Fraction of clips that would fire at [threshold] with a 1-frame requirement. */
        fun hitRate(threshold: Float): Float =
            if (clips.isEmpty()) 0f else clips.count { it.peakProb >= threshold }.toFloat() / clips.size
    }

    /**
     * Score every `<label>_*.wav` in the app's external `kws/` dir.
     *
     * Blocking — call from a background dispatcher. Returns null if the model will not load or
     * there is nothing to score.
     */
    fun scoreRecordedClips(context: Context, label: String): Report? {
        val dir = File(context.getExternalFilesDir(null), "kws")
        val clips = dir.listFiles { f -> f.name.startsWith("${label}_") && f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (clips.isEmpty()) {
            Log.w(TAG, "no '$label' clips in ${dir.absolutePath} — record some with ACTION_CAPTURE_KWS first")
            return null
        }

        val detector = TfliteWakeWordDetector()
        if (!detector.init(context)) {
            Log.e(TAG, "wake-word model failed to load — cannot score")
            return null
        }

        val scored = try {
            clips.map { file -> scoreOne(detector, file) }
        } finally {
            detector.release()
        }

        val report = Report(scored, label)
        logReport(report)
        return report
    }

    private fun scoreOne(detector: TfliteWakeWordDetector, file: File): ClipScore {
        // Fresh ring per clip, so one clip's tail cannot contribute to the next clip's window.
        detector.reset()
        val pcm = readMono16kWav(file)
        var peak = 0f
        var over = 0
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + CHUNK, pcm.size)
            val prob = detector.process(pcm.copyOfRange(i, end))
            if (prob > peak) peak = prob
            if (prob >= WakeTrigger.DEFAULT_THRESHOLD) over++
            i = end
        }
        return ClipScore(
            name = file.name,
            peakProb = peak,
            framesOverDefault = over,
            durationSec = pcm.size / 16000f,
        )
    }

    private fun logReport(r: Report) {
        Log.i(TAG, "=== KWS score report: ${r.clips.size} '${r.label}' clips ===")
        for (c in r.clips) {
            Log.i(TAG, "  %-34s peak=%.3f frames>=0.80=%-3d (%.1fs)"
                .format(c.name, c.peakProb, c.framesOverDefault, c.durationSec))
        }
        Log.i(TAG, "  peak=%.3f median=%.3f".format(r.peak, r.median))
        // The sweep is the actionable part: it says what threshold would have worked, or that no
        // threshold would have.
        val sweep = listOf(0.30f, 0.50f, 0.60f, 0.70f, 0.80f, 0.90f)
            .joinToString(" ") { "%.2f:%.0f%%".format(it, r.hitRate(it) * 100) }
        Log.i(TAG, "  hit rate by threshold -> $sweep")
        Log.i(TAG, "=== end KWS score report ===")
    }

    /**
     * Minimal RIFF/WAV reader for the 16 kHz mono 16-bit files [KwsSampleRecorder] writes.
     *
     * Walks the chunk table rather than assuming a 44-byte header — the recorder writes canonical
     * 44-byte headers, but a clip pushed in from elsewhere may carry a LIST/INFO chunk first, and
     * silently reading that as samples would score noise and look like a model failure.
     */
    private fun readMono16kWav(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size < 44) return ShortArray(0)

        fun le(off: Int, len: Int): Int {
            var v = 0
            for (i in 0 until len) v = v or ((bytes[off + i].toInt() and 0xFF) shl (8 * i))
            return v
        }

        var pos = 12 // past "RIFF" <size> "WAVE"
        var dataOff = -1
        var dataLen = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le(pos + 4, 4)
            if (id == "fmt ") {
                channels = le(pos + 10, 2)
                rate = le(pos + 12, 4)
                bits = le(pos + 22, 2)
            }
            if (id == "data") {
                dataOff = pos + 8
                dataLen = minOf(size, bytes.size - dataOff)
                break
            }
            pos += 8 + size + (size and 1) // chunks are word-aligned
        }

        // Refuse rather than silently score garbage. The detector assumes 16 kHz mono 16-bit; a
        // 44.1 kHz or stereo clip would be read as noise and score ~0, which reads exactly like
        // "the model does not recognise this voice" — the wrong conclusion, drawn confidently.
        if (rate != 16000 || channels != 1 || bits != 16) {
            Log.w(TAG, "${file.name}: expected 16kHz mono 16-bit, got ${rate}Hz ${channels}ch ${bits}bit — skipping")
            return ShortArray(0)
        }
        if (dataOff < 0 || dataLen <= 0) {
            Log.w(TAG, "${file.name}: no data chunk found")
            return ShortArray(0)
        }

        val out = ShortArray(dataLen / 2)
        for (i in out.indices) {
            out[i] = ((bytes[dataOff + 2 * i].toInt() and 0xFF) or
                    ((bytes[dataOff + 2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        return out
    }
}
