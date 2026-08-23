package com.coxy.ghost.perception

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Piper, running on the phone.
 *
 * Android's own engine tops out at a voice that is clear and obviously synthetic; this is a neural
 * model that sounds like a person, and it runs entirely on device — no network, nothing leaving the
 * phone, and it works in a basement.
 *
 * The piece that makes this possible is not the model. Piper takes **phonemes**, not text, and
 * turning English into phonemes is espeak-ng — a C library with its own 1.2 MB of pronunciation
 * data. sherpa-onnx bundles both that and the ONNX runtime, which is why it is here rather than a
 * bare onnxruntime dependency: without a phonemiser the model is a 23 MB file that cannot be spoken
 * to.
 *
 * Streamed to an [AudioTrack] rather than written to a file and played, so the first words start
 * while the rest is still being generated. On a Unisoc T612 already carrying a 1.5B language model
 * that difference is the whole difference between a reply and a wait.
 */
object PiperVoice {

    private const val TAG = "PiperVoice"

    /** Where [installDir] expects its files. Laid down by `tools/install_piper_voice.sh`. */
    const val DIR_NAME = "piper"

    private const val MODEL = "voice.onnx"
    private const val TOKENS = "tokens.txt"
    private const val ESPEAK = "espeak-ng-data"

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null

    /** True once a model is loaded and she can actually speak in it. */
    val ready: Boolean get() = tts != null

    /**
     * Threads for generation.
     *
     * Two, not four. The T612's other cores are carrying the language model, and taking them for
     * speech is how a reply gets slower overall while the sentence being spoken gets faster.
     */
    private const val THREADS = 2

    fun installDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** True when the files are present — checked before offering Piper as an option. */
    fun isInstalled(context: Context): Boolean {
        val dir = installDir(context)
        return File(dir, MODEL).exists() &&
            File(dir, TOKENS).exists() &&
            File(dir, ESPEAK).isDirectory
    }

    /**
     * Load the model. Slow — several seconds — so never on the main thread.
     *
     * @return true if she can now speak in this voice.
     */
    @Synchronized
    fun load(context: Context): Boolean {
        if (tts != null) return true
        if (!isInstalled(context)) {
            Log.i(TAG, "no Piper voice installed at ${installDir(context)}")
            return false
        }
        val dir = installDir(context).absolutePath
        return runCatching {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$dir/$MODEL",
                        tokens = "$dir/$TOKENS",
                        // espeak-ng's data directory. This is the phonemiser; without it the model
                        // loads and then produces nothing intelligible.
                        dataDir = "$dir/$ESPEAK"
                    ),
                    numThreads = THREADS,
                    debug = false
                )
            )
            tts = OfflineTts(config = config)
            Log.i(TAG, "Piper loaded, ${tts?.sampleRate()} Hz")
            true
        }.getOrElse {
            Log.w(TAG, "Piper failed to load", it)
            tts = null
            false
        }
    }

    /**
     * Speak [text], calling [onProgress] as each chunk of audio is produced.
     *
     * The callback is what feeds the orb. Android's own engine reports word boundaries; Piper does
     * not, so the beat comes from audio arriving instead — which is arguably the more honest
     * signal, since it is the sound itself rather than a marker alongside it.
     *
     * @return false if nothing could be spoken, so the caller can fall back.
     */
    fun speak(
        text: String,
        speed: Float = 1.0f,
        onProgress: () -> Unit,
        onDone: () -> Unit
    ): Boolean {
        val engine = tts ?: return false
        val rate = engine.sampleRate()
        val out = ensureTrack(rate)
        if (out == null) {
            onDone()
            return false
        }

        return runCatching {
            out.play()
            engine.generateWithCallback(
                text = text,
                sid = 0,
                speed = speed,
                callback = SamplesCallback { samples ->
                    // 16-bit PCM: sherpa hands back floats in -1..1.
                    val pcm = ShortArray(samples.size) {
                        (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    out.write(pcm, 0, pcm.size)
                    onProgress()
                }
            )
            // Let the buffer drain before the state flips, or she stops "speaking" mid-word.
            out.stop()
            onDone()
            true
        }.getOrElse {
            Log.w(TAG, "Piper generation failed", it)
            runCatching { out.stop() }
            onDone()
            false
        }
    }

    fun stop() {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    @Synchronized
    fun release() {
        runCatching { track?.release() }
        track = null
        runCatching { tts?.release() }
        tts = null
    }

    /**
     * One [AudioTrack], reused.
     *
     * `USAGE_ASSISTANT` on purpose: it ducks music rather than fighting it, and follows the
     * assistant volume rather than the media slider — so turning a film down does not silence her.
     */
    private fun ensureTrack(sampleRate: Int): AudioTrack? {
        track?.let { if (it.sampleRate == sampleRate) return it else { it.release(); track = null } }
        return runCatching {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate))   // ~1s of headroom
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { track = it }
        }.getOrElse {
            Log.w(TAG, "no audio track", it)
            null
        }
    }

    /** Unused, but names the manager so the ducking above is not mysterious. */
    private fun audioManager(context: Context) =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
