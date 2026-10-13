package com.coxy.ghost.perception

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.coxy.ghost.ipc.GhostIsland
import com.coxy.ghost.GhostPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Explicit command capture plus an opt-in local keyword gate. Ambient audio never enters ASR. */
class MicListener(
    private val ctx: Context,
    private val retention: RetentionManager? = null,
) {

    enum class MicMode { OFF, PUSH_TO_TALK, OPEN, WAKE_WORD }

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

        /**
         * The recognizer's raw loudness, for the UI only.
         *
         * Deliberately not [com.coxy.ghost.perception.ContextState.audioLevelDb]: that field is
         * specified as dBFS and is rendered into the `[CONTEXT]` block the model reads, and this
         * number is on `SpeechRecognizer`'s own undocumented scale (roughly -2..10). Telling the
         * model "-2 dB" when the figure is not dB would be a lie in the prompt.
         *
         * A waveform does not need calibrated units — only something that genuinely moves when
         * sound arrives — so it reads this instead. [RMS_FLOOR]/[RMS_CEIL] bound the observed range.
         */
        @Volatile
        @JvmStatic
        var lastRms: Float = RMS_FLOOR
            internal set

        /** Bottom of the recognizer's observed RMS range: treated as silence. */
        const val RMS_FLOOR = -2f

        /** Top of the recognizer's observed RMS range: treated as full deflection. */
        const val RMS_CEIL = 10f

        /** Current mode, readable by the UI so the Live screen can reflect the real state. */
        @Volatile
        @JvmStatic
        var mode: MicMode = MicMode.OFF
            private set

        /**
         * Endpointing for OPEN mode: how much silence ends an utterance. Long enough not to chop a
         * pause mid-sentence, short enough that a finished sentence is acted on promptly.
         */
        private const val OPEN_SILENCE_MS = 1200L

        /**
         * Push-to-talk deliberately uses a very long silence threshold. The release of the button is
         * the endpoint — not the recognizer's own VAD — so a user who pauses to think mid-sentence
         * does not get cut off and half their command dispatched.
         */
        private const val PTT_SILENCE_MS = 10_000L

        /** Safety cap so a stuck press or a lost callback cannot hold the mic open forever. */
        private const val PTT_MAX_MS = 30_000L

        /** Gap before restarting the recognizer in OPEN mode after a result or a no-match. */
        private const val OPEN_RESTART_MS = 400L

        /** Backoff after repeated hard errors, so a broken recogniser cannot spin. */
        private const val OPEN_BACKOFF_MS = 4000L
        private const val MAX_FAST_RESTARTS = 8
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var recognizer: SpeechRecognizer? = null

    /** Token so we only cancel our own posted work, not everything on the shared main looper. */
    private val token = Any()

    @Volatile private var pttHeld: Boolean = false
    private var consecutiveErrors: Int = 0
    @Volatile private var generation = 0L
    private var oneShot = false
    @Volatile private var stopped = false
    private var wake: com.coxy.ghost.perception.wakeword.KeywordWake? = null
    var onWake: (() -> Unit)? = null
    var canCapture: () -> Boolean = { true }
    var captureBlockReason: () -> String? = { if (canCapture()) null else "capture_blocked" }
    fun wakeEnabled() = GhostPrefs.isWakeWordEnabled(ctx)
    fun setWakeEnabled(enabled: Boolean) {
        GhostPrefs.setWakeWordEnabled(ctx, enabled)
        mainHandler.post { if (!GhostIsland.active()) resumeIdle() }
    }
    fun startSingleCommand() {
        mainHandler.post {
            if (stopped) return@post
            mainHandler.removeCallbacksAndMessages(token)
            teardownRecognizer(); pttHeld = false; oneShot = true
            val mine = generation
            val previous = wake; wake = null
            val capture = {
                mainHandler.post {
                    if (!stopped && generation == mine && oneShot) {
                        if (!canCapture()) failCapture("MIC_UNAVAILABLE", "Open Ghost to enable microphone access")
                        else if (!hasPermission()) failCapture("MIC_PERMISSION", "Enable microphone permission in Ghost")
                        else {
                            mode = MicMode.PUSH_TO_TALK; isRunning = true
                            ContextStore.update { it.copy(micState = MicState.SILENT, transcriptSnippet = null) }
                            startRecognizer(OPEN_SILENCE_MS)
                            val recording = generation
                            mainHandler.postDelayed({ if (oneShot && generation == recording) failCapture("TIMEOUT", "I didn't hear a command. Try again.") }, token, 15000)
                        }
                    }
                }
                Unit
            }
            if (previous == null) capture() else previous.stopThen(capture)
        }
    }
    fun cancelCapture() { mainHandler.post { oneShot = false; enterOffMode() } }
    fun resumeIdle() {
        mainHandler.post {
            if (stopped) return@post
            val mine = generation
            scope.launch {
                val open = GhostPrefs.isMicEnabled(ctx)
                mainHandler.post { if (!stopped && mine == generation && !GhostIsland.active()) resumeIdleNow(open) }
            }
        }
    }
    private fun resumeIdleNow(open: Boolean) {
            enterOffMode()
            if (!canCapture() || ContextStore.state.value.callState in setOf(CallState.RINGING, CallState.IN_CALL)) {
                GhostIsland.update(phase = "OFF", mic = "OFF", audio = 0f); return
            }
            if (open) { enterOpenMode(); return }
            val mine = generation
            if (wakeEnabled() && hasPermission()) {
                mode = MicMode.WAKE_WORD
                wake = com.coxy.ghost.perception.wakeword.KeywordWake(ctx,
                    blockedReason = {
                        captureBlockReason() ?: when {
                            GhostVoice.speaking -> "speech_playback"
                            GhostIsland.active() -> "interaction_active"
                            ContextStore.state.value.callState in setOf(CallState.RINGING, CallState.IN_CALL) -> "call_active"
                            ContextStore.state.value.inPocket == true -> "pocket"
                            else -> null
                        }
                    },
                    onReady = { mainHandler.post {
                        val accepted = !stopped && mine == generation && mode == MicMode.WAKE_WORD
                        Log.i("GhostKeyword", "ready callback accepted=$accepted")
                        if (accepted) {
                            isRunning = true; GhostIsland.update(phase = "ARMED", mic = "WAKE_WORD", audio = 0f)
                        }
                    } },
                    onKeyword = { mainHandler.post {
                        val accepted = !stopped && mine == generation && mode == MicMode.WAKE_WORD
                        Log.i("GhostKeyword", "keyword callback accepted=$accepted")
                        if (accepted) onWake?.invoke()
                    } },
                    onError = { mainHandler.post { if (!stopped && mine == generation && mode == MicMode.WAKE_WORD) {
                        mode = MicMode.OFF; isRunning = false
                        GhostIsland.update(phase = "ERROR", mic = "OFF", code = "WAKE_UNAVAILABLE", detail = "Hey Ghost is unavailable. Tap the island to speak.")
                    } } }).also { it.start() }
            } else GhostIsland.update(phase = "OFF", mic = "OFF", audio = 0f)
    }
    private fun failCapture(code: String, detail: String) {
        oneShot = false; enterOffMode()
        GhostIsland.update(phase = "ERROR", mic = "OFF", code = code, detail = detail, audio = 0f)
        val mine = generation
        mainHandler.postDelayed({ if (!stopped && generation == mine && !GhostIsland.active()) resumeIdle() }, token, 3500)
    }


    /**
     * Restore the persisted latch on service start. Only [MicMode.OPEN] persists — push-to-talk is
     * by definition momentary, and nothing should come back from a reboot already listening.
     */
    fun start() {
        val mine = generation
        scope.launch {
            val open = GhostPrefs.isMicEnabled(ctx)
            mainHandler.post { if (!stopped && mine == generation) { if (open) enterOpenMode() else resumeIdle() } }
        }
    }

    /** Turn the OPEN latch on or off. Persisted by GhostService's ACTION_TOGGLE_MIC handler. */
    fun setEnabled(enabled: Boolean) {
        mainHandler.post {
            if (GhostIsland.state == "THINKING" || GhostIsland.state == "SPEAKING") return@post
            if (enabled) enterOpenMode() else {
                enterOffMode(); GhostIsland.update(phase = "OFF", mic = "OFF", audio = 0f)
                resumeIdle()
            }
        }
    }

    /** True while the mic is latched open. */
    fun isOpen(): Boolean = mode == MicMode.OPEN

    // ---------------------------------------------------------------- push to talk

    /**
     * Begin a push-to-talk capture. Safe to call while [MicMode.OPEN] is latched — the latch is
     * suspended for the duration of the press and restored on release.
     */
    fun startPushToTalk() {
        mainHandler.post {
            if (!canCapture()) return@post
            if (!hasPermission()) {
                Log.i(TAG, "RECORD_AUDIO not granted — cannot push to talk")
                ContextStore.update { it.copy(micAvailable = false) }
                return@post
            }
            if (pttHeld) return@post
            val previousWake = wake; wake = null
            oneShot = false
            GhostIsland.begin("")
            pttHeld = true
            teardownRecognizer()
            mode = MicMode.PUSH_TO_TALK
            isRunning = true
            Log.i(TAG, "push-to-talk: pressed")
            ContextStore.update {
                it.copy(micState = MicState.VOICE, micAvailable = true, transcriptSnippet = null)
            }
            val mine = generation
            val capture = {
                mainHandler.post { if (!stopped && pttHeld && generation == mine) startRecognizer(PTT_SILENCE_MS) }
                Unit
            }
            if (previousWake == null) capture() else previousWake.stopThen(capture)
            // If the release event is ever lost (view detached mid-press, activity backgrounded),
            // this stops the mic rather than leaving it open indefinitely.
            mainHandler.postDelayed({
                if (pttHeld) {
                    Log.w(TAG, "push-to-talk: release never arrived — closing after ${PTT_MAX_MS}ms")
                    stopPushToTalk()
                }
            }, token, PTT_MAX_MS)
        }
    }

    /**
     * End a push-to-talk capture. `stopListening()` tells the recognizer the utterance is over and
     * makes it emit its final result, which arrives on [CommandListener.onResults].
     */
    fun stopPushToTalk() {
        mainHandler.post {
            if (!pttHeld) return@post
            pttHeld = false
            mainHandler.removeCallbacksAndMessages(token)
            Log.i(TAG, "push-to-talk: released")
            ContextStore.update { it.copy(micState = MicState.SILENT) }
            val r = recognizer
            if (r == null) {
                GhostIsland.update(phase = "OFF", mic = "OFF", audio = 0f)
                restoreAfterPtt()
                return@post
            }
            // Do not destroy here — the final result is still to come. onResults/onError restores.
            runCatching { r.stopListening() }
                .onFailure {
                    Log.w(TAG, "stopListening failed on release", it)
                    failCapture("ASR_UNAVAILABLE", "Microphone interrupted. Try again.")
                }
        }
    }

    /** After a press finishes, go back to whatever the latch says. */
    private fun restoreAfterPtt() {
        teardownRecognizer()
        val mine = generation
        scope.launch {
            val open = GhostPrefs.isMicEnabled(ctx)
            mainHandler.post { if (!stopped && generation == mine) { if (open) enterOpenMode() else if (!GhostIsland.active()) resumeIdle() } }
        }
    }

    // ---------------------------------------------------------------- modes

    private fun enterOffMode() {
        wake?.stop(); wake = null
        oneShot = false
        mainHandler.removeCallbacksAndMessages(token)
        teardownRecognizer()
        pttHeld = false
        mode = MicMode.OFF
        isRunning = false
        consecutiveErrors = 0
        // Nothing is listening, so the waveform must settle rather than hold its last reading.
        lastRms = RMS_FLOOR
        ContextStore.update {
            it.copy(
                micState = MicState.OFF,
                micAvailable = true,
                audioLevelDb = -1f,
                transcriptSnippet = null,
            )
        }
        Log.i(TAG, "mic OFF")
    }

    private fun enterOpenMode() {
        if (!canCapture()) return
        wake?.stop(); wake = null
        oneShot = false
        if (!hasPermission()) {
            Log.i(TAG, "RECORD_AUDIO not granted — mic unavailable")
            ContextStore.update { it.copy(micAvailable = false) }
            return
        }
        mainHandler.removeCallbacksAndMessages(token)
        teardownRecognizer()
        mode = MicMode.OPEN
        isRunning = true
        consecutiveErrors = 0
        ContextStore.update {
            it.copy(micState = MicState.SILENT, micAvailable = true, transcriptSnippet = null)
        }
        Log.i(TAG, "mic OPEN (continuous, explicit)")
        startRecognizer(OPEN_SILENCE_MS)
    }

    fun stop() {
        stopped = true
        mainHandler.post { enterOffMode() }
        scope.cancel()
    }

    // ---------------------------------------------------------------- recognizer plumbing

    private fun startRecognizer(silenceMs: Long) {
        if (mode == MicMode.OFF) return
        if (!canCapture()) {
            failCapture("MIC_UNAVAILABLE", "Open Ghost to enable microphone access")
            return
        }
        @Suppress("DEPRECATION")
        val r = SpeechRecognizer.createSpeechRecognizer(ctx, AIAI_RECOGNIZER)
        val mine = ++generation
        r.setRecognitionListener(CommandListener(mine))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceMs / 2
            )
        }

        // Publish the handle only once it is actually listening. Assigning before startListening is
        // what made the old retry path dead code — its own null-check guard was always false.
        val started = runCatching { r.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening failed", it)
                runCatching { r.destroy() }
                if (mode == MicMode.PUSH_TO_TALK) failCapture("ASR_UNAVAILABLE", "Speech recognition could not start") else { GhostIsland.update(phase = "OFF", mic = "OPEN"); scheduleRestart(OPEN_BACKOFF_MS) }
            }
            .isSuccess
        if (started) recognizer = r
    }

    private fun scheduleRestart(delayMs: Long) {
        if (mode != MicMode.OPEN) return
        mainHandler.postDelayed({ if (mode == MicMode.OPEN) startRecognizer(OPEN_SILENCE_MS) }, token, delayMs)
    }

    private fun teardownRecognizer() {
        generation++
        val r = recognizer
        recognizer = null
        runCatching { r?.cancel() }
        runCatching { r?.destroy() }
    }

    private inner class CommandListener(private val mine: Long) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (stopped || mine != generation) return
            if (mode == MicMode.OPEN) GhostIsland.begin("")
            GhostIsland.update(phase = "LISTENING", mic = mode.name)
            GhostIsland.publish()
            if (mode == MicMode.OPEN) ContextStore.update { it.copy(micState = MicState.SILENT) }
        }

        override fun onBeginningOfSpeech() {
            if (stopped || mine != generation) return
            ContextStore.update { it.copy(micState = MicState.VOICE) }
        }

        override fun onRmsChanged(rms: Float) {
            if (stopped || mine != generation) return
            // Not written to ContextState.audioLevelDb. SpeechRecognizer's RMS is its own scale
            // (roughly -2..10, against no documented reference), while that field is specified as
            // dBFS and is rendered as `mic=voice(NdB)` into the [CONTEXT] block the model reads.
            // With the AudioRecord path gone there is no honest dBFS source, so the field stays
            // unset rather than carrying a number in the wrong units.
            //
            // It does drive the Live waveform, which needs movement rather than units. See lastRms.
            lastRms = rms
            GhostIsland.update(audio = ((rms - RMS_FLOOR) / (RMS_CEIL - RMS_FLOOR)).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (stopped || mine != generation) return
            ContextStore.update { it.copy(micState = MicState.SILENT) }
        }

        override fun onError(error: Int) {
            if (stopped || mine != generation) return
            val name = errorName(error)
            if (oneShot || mode == MicMode.PUSH_TO_TALK) { failCapture(name, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "I didn't catch that. Try again." else "Microphone interrupted. Try again."); return }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.w(TAG, "RECORD_AUDIO revoked — stopping the mic")
                ContextStore.update { it.copy(micAvailable = false) }
                enterOffMode()
                return
            }
            GhostIsland.update(phase = "OFF", mic = mode.name, audio = 0f)
            // NO_MATCH and SPEECH_TIMEOUT are the normal outcome of silence, not failures.
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (benign) Log.d(TAG, "recognizer: $name") else Log.w(TAG, "recognizer error: $name")

            if (pttHeld) {
                // Error mid-press: keep the press alive, the user is still holding the button.
                Log.d(TAG, "push-to-talk: $name while held")
                return
            }
            if (mode == MicMode.PUSH_TO_TALK) {
                restoreAfterPtt()
                return
            }
            teardownRecognizer()
            if (benign) {
                consecutiveErrors = 0
                scheduleRestart(OPEN_RESTART_MS)
            } else {
                consecutiveErrors++
                scheduleRestart(
                    if (consecutiveErrors >= MAX_FAST_RESTARTS) OPEN_BACKOFF_MS else OPEN_RESTART_MS
                )
            }
        }

        override fun onResults(results: Bundle?) {
            if (stopped || mine != generation) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            consecutiveErrors = 0
            Log.d(TAG, "recognizer results: \"$text\"")

            if (oneShot) {
                if (text.isBlank()) { failCapture("NO_MATCH", "I didn't catch that. Try again."); return }
                oneShot = false; enterOffMode()
                dispatchVoiceCommand(text)
                return
            }
            if (text.isNotBlank()) {
                pttHeld = false; teardownRecognizer(); dispatchVoiceCommand(text); return
            }

            if (mode == MicMode.PUSH_TO_TALK) { failCapture("NO_MATCH", "I didn't catch that. Try again."); return }
            GhostIsland.update(phase = "OFF", mic = mode.name, audio = 0f)
            if (pttHeld) {
                // A result arrived before release (the recognizer endpointed on its own). Restart so
                // the rest of the press is still captured.
                startRecognizerAgainForPtt()
                return
            }
            if (mode == MicMode.PUSH_TO_TALK) {
                restoreAfterPtt()
                return
            }
            teardownRecognizer()
            if (text.isBlank()) scheduleRestart(OPEN_RESTART_MS)
        }

        override fun onPartialResults(partial: Bundle?) {
            if (stopped || mine != generation) return
            GhostIsland.update(heard = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startRecognizerAgainForPtt() {
        teardownRecognizer()
        mainHandler.post { if (pttHeld) startRecognizer(PTT_SILENCE_MS) }
    }

    /**
     * Publish a spoken utterance as a command for GhostService to run.
     *
     * No wake word is stripped and none is required: in push-to-talk the user is holding the button,
     * and in open mode they have deliberately latched the mic. The wake word only ever existed to
     * decide *whether* speech was meant for GHOST, and that decision is now made by hand.
     */
    private fun dispatchVoiceCommand(cmd: String) {
        GhostIsland.update(phase = "THINKING", heard = cmd, audio = 0f)
        val now = System.currentTimeMillis()
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
        retention?.let { r -> scope.launch { r.putTranscript(truncated, now) } }
        Log.i(TAG, "heard: \"$truncated\" -> dispatched as voice command")
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

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
