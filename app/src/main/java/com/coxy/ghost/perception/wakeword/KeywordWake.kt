package com.coxy.ghost.perception.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*

/** Local streaming keyword gate. Audio is ephemeral and is never transcribed or retained. */
class KeywordWake(
    private val context: Context,
    private val blockedReason: () -> String?,
    private val onReady: () -> Unit,
    private val onKeyword: () -> Unit,
    private val onError: () -> Unit,
) {
    companion object {
        private val modelLock = Any()
        private var cachedModel: KeywordSpotter? = null
        fun releaseCache() {
            Thread({ synchronized(modelLock) { cachedModel?.release(); cachedModel = null } },
                "ghost-keyword-release").apply { isDaemon = true }.start()
        }
    }
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    fun start() {
        if (running) return
        running = true
        worker = Thread({ listen() }, "ghost-keyword").apply { isDaemon = true; start() }
    }
    fun stop() {
        running = false
        // Unblock read; native stream/model are released only by their owning thread.
        runCatching { recorder?.stop() }
    }
    fun stopThen(released: () -> Unit) {
        stop()
        Thread({ worker?.join(); released() }, "ghost-keyword-handoff").apply { isDaemon = true }.start()
    }
    private fun listen() { synchronized(modelLock) { listenLocked() } }
    private fun listenLocked() {
        var spotter: KeywordSpotter? = null
        var stream: OnlineStream? = null
        var audio: AudioRecord? = null
        val diagnostics = WakeDiagnostics()
        var lastReport = SystemClock.elapsedRealtime()
        android.util.Log.i("GhostKeyword", "worker acquired model lock")
        try {
            val base = "ghost-kws"
            for (name in listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")) context.assets.open("$base/$name").close()
            spotter = cachedModel ?: KeywordSpotter(context.assets, KeywordSpotterConfig(
                modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                    encoder = "$base/encoder.onnx", decoder = "$base/decoder.onnx", joiner = "$base/joiner.onnx"),
                    tokens = "$base/tokens.txt", numThreads = 2, provider = "cpu"),
                keywordsFile = "$base/keywords.txt", keywordsScore = 1.5f,
                keywordsThreshold = 0.35f, numTrailingBlanks = 2)).also { cachedModel = it }
            stream = spotter.createStream()
            if (!running) return
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            val input = audio
            recorder = input
            if (!running) return
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            android.util.Log.i("GhostKeyword", "recording started sampleRate=${audio.sampleRate}")
            lastReport = SystemClock.elapsedRealtime()
            onReady()
            val samples = ShortArray(1600)
            var allowAfter = SystemClock.elapsedRealtime() + 1200
            while (running) {
                val n = input.read(samples, 0, samples.size)
                if (!running) break
                check(n > 0)
                val now = SystemClock.elapsedRealtime()
                val blocked = blockedReason()
                val suppression = blocked ?: if (now < allowAfter) "warmup" else null
                diagnostics.read(samples, n, suppression)
                if (now - lastReport >= 5000) {
                    val window = diagnostics.snapshot()
                    android.util.Log.i("GhostKeyword", "windowMs=${now - lastReport} audioMs=${window.samples / 16} $window")
                    lastReport = now
                }
                if (blocked != null) { spotter.reset(stream); allowAfter = now + 1500; continue }
                if (suppression != null) continue
                stream.acceptWaveform(FloatArray(n) { samples[it] / 32768f }, 16000)
                while (running && spotter.isReady(stream)) {
                    spotter.decode(stream)
                    diagnostics.decoded()
                    if (spotter.getResult(stream).keyword == "HEY_GHOST") {
                        diagnostics.detected()
                        android.util.Log.i("GhostKeyword", "keyword detected")
                        running = false
                        // Release the microphone before handing it to SpeechRecognizer.
                        input.stop(); input.release(); audio = null; recorder = null
                        onKeyword()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (running) { android.util.Log.w("GhostKeyword", "Keyword listener unavailable", e); onError() }
        } finally {
            android.util.Log.i("GhostKeyword", "worker finished ${diagnostics.snapshot()}")
            running = false; recorder = null
            runCatching { audio?.stop() }; runCatching { audio?.release() }
            stream?.release()
        }
    }
}
