package com.coxy.ghost.perception

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.perception.wakeword.TfliteWakeWordDetector
import com.coxy.ghost.perception.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Phase 5.2 microphone perception.
 *
 * Continuous listening is now done via a single always-on [AudioRecord] stream feeding a tiny
 * on-device TFLite wake-word detector for "Hey Ghost". Only when the detector fires do we spin
 * up a transient [SpeechRecognizer] session to capture the rest of the utterance. That avoids
 * the audible HAL on/off blip caused by repeatedly restarting the recognizer in a loop.
 *
 * Two phrasings still work:
 *  - "Hey Ghost, <command>" — the detector fires, the recognizer captures the whole phrase,
 *    the wake words are stripped, and the remainder is dispatched.
 *  - "Hey Ghost" … (pause) … "<command>" — if the first recognizer only hears the wake phrase,
 *    a second command-only recognizer listens for up to [COMMAND_AWAIT_MS].
 *
 * The [AudioRecord] thread owns the detector and never touches the engine; SpeechRecognizer callbacks
 * run on the main thread and only update [ContextStore].
 */
class MicListener(
    private val ctx: Context,
    private val retention: RetentionManager? = null,
) {

    companion object {
        private const val TAG = "MicListener"

        // Explicit AiAi/SODA on-device recognition service. Relying on the system default
        // `voice_recognition_service` setting is fragile — it reverts to Google TTS on reboot.
        private val AIAI_RECOGNIZER = ComponentName(
            "com.google.android.as",
            "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"
        )

        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        // AudioRecord: 16 kHz mono 16-bit PCM — matches the TFLite KWS model.
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_SAMPLES = 800            // 50 ms at 16 kHz
        private const val RMS_WRITE_THROTTLE_MS = 250L

        // Wake-word detector threshold and cooldown.
        private const val WAKE_THRESHOLD = 0.80f
        private const val TRIGGER_COOLDOWN_MS = 2000L

        // SpeechRecognizer settings for the command capture after a trigger.
        private const val COMMAND_SILENCE_MS = 1500L
        private const val COMMAND_MAX_MS = 8000L
        private const val COMMAND_AWAIT_MS = 5000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val micThread = HandlerThread("ghost-mic").apply { start() }
    private val micHandler = Handler(micThread.looper)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var detector: WakeWordDetector? = null
    private var commandRecognizer: SpeechRecognizer? = null

    @Volatile private var enabledByPref: Boolean = true
    @Volatile private var capturingCommand: Boolean = false
    @Volatile private var awaitingCommand: Boolean = false
    @Volatile private var lastRmsWriteMs: Long = 0L
    @Volatile private var lastTriggerMs: Long = 0L
    @Volatile private var consecutiveErrors: Int = 0

    // Token for the command-capture timeout so we only cancel our own main-thread work,
    // not every callback posted to the shared main looper.
    private val commandToken = Any()

    fun start() {
        if (isRunning) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.i(TAG, "RECORD_AUDIO not granted — mic disabled")
            ContextStore.update { it.copy(micAvailable = false) }
            return
        }
        scope.launch {
            enabledByPref = GhostPrefs.isMicEnabled(ctx)
            micHandler.post {
                if (enabledByPref) startPipeline() else setMicOff()
            }
        }
    }

    /**
     * Toggle continuous listening on/off at runtime. Called from GhostService when the user taps
     * the mic button in MainActivity. The new state is persisted so it survives service restarts.
     */
    fun setEnabled(enabled: Boolean) {
        enabledByPref = enabled
        micHandler.post {
            if (enabled) {
                if (!isRunning) startPipeline()
            } else {
                stopPipeline(setOff = true)
            }
        }
    }

    fun stop() {
        micHandler.post {
            stopPipeline(setOff = false)
            micThread.quitSafely()
        }
        scope.cancel()
    }

    private fun setMicOff() {
        ContextStore.update {
            it.copy(
                micState = MicState.OFF,
                micAvailable = true,
                audioLevelDb = -1f,
                transcriptSnippet = null
            )
        }
    }

    private fun startPipeline() {
        if (isRunning) return
        isRunning = true
        capturingCommand = false
        awaitingCommand = false
        consecutiveErrors = 0
        Log.i(TAG, "starting Phase 5.2 continuous mic perception")

        val d = TfliteWakeWordDetector()
        detector = d
        if (!d.init(ctx)) {
            Log.w(TAG, "wake-word detector unavailable — mic disabled")
            ContextStore.update { it.copy(micAvailable = false) }
            isRunning = false
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
            .coerceAtLeast(READ_SAMPLES * 2)
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, FORMAT, bufferSize)
        }

        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialise — mic disabled")
            ContextStore.update { it.copy(micAvailable = false) }
            isRunning = false
            return
        }
        audioRecord = record
        record.startRecording()
        ContextStore.update { it.copy(micState = MicState.SILENT) }
        micHandler.post(AudioLoop())
        Log.i(TAG, "AudioRecord started at ${SAMPLE_RATE} Hz")
    }

    private inner class AudioLoop : Runnable {
        private val readBuf = ShortArray(READ_SAMPLES)
        override fun run() {
            if (!isRunning || capturingCommand) return
            val record = audioRecord ?: return
            val read = record.read(readBuf, 0, readBuf.size)
            if (read < 0) {
                Log.w(TAG, "AudioRecord read error: $read")
                consecutiveErrors++
                if (consecutiveErrors > 5) {
                    stopPipeline(setOff = false)
                    ContextStore.update { it.copy(micAvailable = false) }
                } else {
                    micHandler.postDelayed(this, 100)
                }
                return
            }
            if (read == 0) {
                // AudioRecord was stopped (e.g. during command capture). Do not reschedule here;
                // resumeKws() will restart the loop.
                return
            }
            consecutiveErrors = 0

            val chunk = if (read == readBuf.size) readBuf else readBuf.copyOf(read)
            updateAudioLevel(chunk)

            val prob = detector?.process(chunk) ?: 0f
            if (prob >= WAKE_THRESHOLD && !cooldownActive()) {
                lastTriggerMs = System.currentTimeMillis()
                Log.i(TAG, "wake-word triggered (prob=%.2f)".format(prob))
                startCommandCapture()
                return
            }

            micHandler.post(this)
        }
    }

    private fun cooldownActive(): Boolean {
        return System.currentTimeMillis() - lastTriggerMs < TRIGGER_COOLDOWN_MS
    }

    private fun updateAudioLevel(samples: ShortArray) {
        val now = System.currentTimeMillis()
        if (now - lastRmsWriteMs < RMS_WRITE_THROTTLE_MS) return
        lastRmsWriteMs = now
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / samples.size.coerceAtLeast(1))
        val db = if (rms <= 0) -100f else (20f * log10(rms)).toFloat()
        ContextStore.update { it.copy(audioLevelDb = db) }
    }

    /** Called on the mic thread when the detector fires. Hand the mic to SpeechRecognizer. */
    private fun startCommandCapture() {
        if (capturingCommand) return
        capturingCommand = true
        ContextStore.update {
            it.copy(micState = MicState.VOICE, transcriptSnippet = "Hey Ghost", lastTranscriptMs = System.currentTimeMillis())
        }
        audioRecord?.stop()
        mainHandler.post { createAndStartRecognizer(commandOnly = false) }
    }

    /** Resume KWS after a command capture finishes. Must run on the mic thread. */
    private fun resumeKws() {
        if (!isRunning) return
        capturingCommand = false
        awaitingCommand = false
        audioRecord?.startRecording()
        detector?.let { d ->
            // Clear the old audio ring so the next inference doesn't see stale command audio.
            if (d is TfliteWakeWordDetector) {
                // The detector exposes a ring buffer; we can reset via init? Re-init is cheap.
                d.init(ctx)
            }
        }
        ContextStore.update { it.copy(micState = MicState.SILENT, transcriptSnippet = null, voiceCommand = null) }
        micHandler.post(AudioLoop())
    }

    /** SpeechRecognizer must be created and started on the main thread. */
    private fun createAndStartRecognizer(commandOnly: Boolean) {
        if (!isRunning) {
            finishCommandCapture()
            return
        }
        // Use the explicit AiAi/SODA on-device recognition service. The default system setting
        // reverts to Google TTS on reboot, and `createOnDeviceSpeechRecognizer` has been observed
        // to fail to bind with "not connected to the recognition service" on this device.
        @Suppress("DEPRECATION")
        val r = SpeechRecognizer.createSpeechRecognizer(ctx, AIAI_RECOGNIZER)
        r.setRecognitionListener(CommandListener(commandOnly))
        commandRecognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMMAND_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, COMMAND_SILENCE_MS / 2)
        }
        runCatching { r.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening failed, will retry once", it)
                mainHandler.postDelayed({ retryRecognizer(commandOnly, intent) }, commandToken, 500)
            }

        // Hard safety cap: force-stop the recognizer after COMMAND_MAX_MS / COMMAND_AWAIT_MS.
        val timeout = if (commandOnly) COMMAND_AWAIT_MS else COMMAND_MAX_MS
        mainHandler.postDelayed({ finishCommandCapture() }, commandToken, timeout)
    }

    private fun retryRecognizer(commandOnly: Boolean, intent: Intent) {
        if (!isRunning || commandRecognizer != null) {
            finishCommandCapture()
            return
        }
        @Suppress("DEPRECATION")
        val r = SpeechRecognizer.createSpeechRecognizer(ctx, AIAI_RECOGNIZER)
        r.setRecognitionListener(CommandListener(commandOnly))
        commandRecognizer = r
        runCatching { r.startListening(intent) }
            .onFailure {
                Log.w(TAG, "retry startListening also failed", it)
                finishCommandCapture()
            }
    }

    private fun finishCommandCapture() {
        mainHandler.removeCallbacksAndMessages(commandToken)
        val r = commandRecognizer
        commandRecognizer = null
        runCatching { r?.stopListening() }
        runCatching { r?.destroy() }
        micHandler.post { resumeKws() }
    }

    private inner class CommandListener(private val commandOnly: Boolean) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "recognizer ready for speech")
            ContextStore.update { it.copy(micState = MicState.SILENT) }
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "recognizer beginning of speech")
            ContextStore.update { it.copy(micState = MicState.VOICE) }
        }

        override fun onRmsChanged(rms: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRmsWriteMs < RMS_WRITE_THROTTLE_MS) return
            lastRmsWriteMs = now
            ContextStore.update { it.copy(audioLevelDb = rms) }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "recognizer end of speech")
            ContextStore.update { it.copy(micState = MicState.SILENT) }
        }

        override fun onError(error: Int) {
            Log.d(TAG, "recognizer error: ${errorName(error)}")
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.w(TAG, "command recognizer error: ${errorName(error)}")
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                ContextStore.update { it.copy(micAvailable = false) }
                isRunning = false
            }
            finishCommandCapture()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.d(TAG, "recognizer results: \"$text\"")
            this@MicListener.handleCommandResult(text, commandOnly)
        }

        override fun onPartialResults(partial: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Process the command recognizer result. Run on the main thread.
     *
     * If [commandOnly] is true, the result is treated as the command itself (no wake-word check).
     * If false and the user only said the wake phrase, we open a second command-only recognizer.
     */
    private fun handleCommandResult(text: String, commandOnly: Boolean) {
        val now = System.currentTimeMillis()
        if (commandOnly || awaitingCommand) {
            val cmd = stripWakeWord(text)
            if (cmd.isNotBlank()) {
                dispatchVoiceCommand(cmd, now)
                finishCommandCapture()
                return
            }
        }

        when {
            containsWakeWord(text) -> {
                val cmd = stripWakeWord(text)
                if (cmd.isBlank()) {
                    // Bare wake word — listen for the follow-up command.
                    awaitingCommand = true
                    ContextStore.update {
                        it.copy(micState = MicState.SILENT, transcriptSnippet = "Hey Ghost (awaiting command)")
                    }
                    // Cancel the first recognizer's timeout before starting the command-only one.
                    mainHandler.removeCallbacksAndMessages(commandToken)
                    // Restart a command-only recognizer on the same main thread path.
                    val r = commandRecognizer
                    commandRecognizer = null
                    runCatching { r?.stopListening() }
                    runCatching { r?.destroy() }
                    mainHandler.postDelayed({ createAndStartRecognizer(commandOnly = true) }, commandToken, 200)
                } else {
                    dispatchVoiceCommand(cmd, now)
                    finishCommandCapture()
                }
            }
            text.isNotBlank() -> {
                // Detector already confirmed the wake phrase; be forgiving if the recognizer dropped it.
                val cmd = stripWakeWord(text)
                if (cmd.isNotBlank()) dispatchVoiceCommand(cmd, now)
                else Log.d(TAG, "ignored (no usable command): ${text.take(40)}")
                finishCommandCapture()
            }
            else -> finishCommandCapture()
        }
    }

    private fun dispatchVoiceCommand(cmd: String, now: Long) {
        val truncated = cmd.take(200)
        ContextStore.update {
            it.copy(
                micState = MicState.SILENT,
                transcriptSnippet = truncated,
                lastTranscriptMs = now,
                voiceCommand = truncated,
                voiceCommandSeq = it.voiceCommandSeq + 1L,
                voiceResponse = null,
            )
        }
        val r = retention
        if (r != null) scope.launch { r.putTranscript("wake: $truncated", now) }
        Log.i(TAG, "heard (wake): \"$truncated\" -> dispatched as voice command")
    }

    private fun stopPipeline(setOff: Boolean) {
        if (!isRunning && !setOff) return
        isRunning = false
        capturingCommand = false
        awaitingCommand = false
        mainHandler.removeCallbacksAndMessages(commandToken)
        runCatching { commandRecognizer?.stopListening() }
        runCatching { commandRecognizer?.destroy() }
        commandRecognizer = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { detector?.release() }
        detector = null
        ContextStore.update {
            it.copy(
                micState = if (setOff) MicState.OFF else MicState.UNKNOWN,
                audioLevelDb = -1f,
                transcriptSnippet = null,
                micAvailable = true,
                voiceCommand = null,
                voiceResponse = null,
            )
        }
        Log.i(TAG, if (setOff) "mic pipeline stopped (disabled by user)" else "mic pipeline stopped")
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun containsWakeWord(text: String): Boolean = text.contains("ghost", ignoreCase = true)

    private fun stripWakeWord(text: String): String =
        text.replaceFirst(Regex("(?i)(?:hey\\s+)?ghost"), "")
            .replace(Regex("^[,.;:\\s]+"), "")
            .trim()

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "code=$error"
    }
}
