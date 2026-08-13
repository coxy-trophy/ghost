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
import com.coxy.ghost.GhostPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Microphone input, driven explicitly by the user — never ambiently.
 *
 * **The always-on "Hey Ghost" spotter was removed on 2026-08-13.** It fired on ordinary room noise
 * roughly once every 30 seconds (measured: GHOST.md v0.11.3), and every false fire transcribed
 * whatever was in the room and ran it through the engine as a command. The damage was not just the
 * audible HAL blip — 31 of 37 stored voice turns were ambient speech and TV dialogue ("Where the
 * princess to Amiga receiving"), which then came back through `[MEMORY]` as things Coxy had
 * supposedly said. The spotter model itself saturates at probability 1.000 on room audio and cannot
 * be fixed by tuning, only by retraining, so leaving it armed had no upside.
 *
 * The wake-word classes are kept ([com.coxy.ghost.perception.wakeword]) — the detector, the trigger,
 * the sample recorder and the scorer are what a retrained model would be validated with. Nothing in
 * this class starts them any more.
 *
 * Three modes, all entered deliberately:
 *  - [MicMode.OFF] — the default. No recognizer, no audio capture, nothing listening.
 *  - [MicMode.PUSH_TO_TALK] — held for the duration of a press. [startPushToTalk] opens the
 *    recognizer, [stopPushToTalk] closes it and dispatches whatever was said. No wake word: if the
 *    user is holding the button, they mean it.
 *  - [MicMode.OPEN] — a latch for open conversation while Live mode is on. Every final utterance is
 *    a command. This is the mode that makes the recognizer restart audibly on this device; that is
 *    expected here and absent everywhere else, which was the Phase 5.2 design intent all along.
 *
 * [SpeechRecognizer] must be created and driven from the main thread — it enforces
 * `checkIsCalledFromMainThread` — so every method here hops to [mainHandler].
 */
class MicListener(
    private val ctx: Context,
    private val retention: RetentionManager? = null,
) {

    enum class MicMode { OFF, PUSH_TO_TALK, OPEN }

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

    /**
     * Restore the persisted latch on service start. Only [MicMode.OPEN] persists — push-to-talk is
     * by definition momentary, and nothing should come back from a reboot already listening.
     */
    fun start() {
        scope.launch {
            val open = GhostPrefs.isMicEnabled(ctx)
            mainHandler.post { if (open) enterOpenMode() else enterOffMode() }
        }
    }

    /** Turn the OPEN latch on or off. Persisted by GhostService's ACTION_TOGGLE_MIC handler. */
    fun setEnabled(enabled: Boolean) {
        mainHandler.post { if (enabled) enterOpenMode() else enterOffMode() }
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
            if (!hasPermission()) {
                Log.i(TAG, "RECORD_AUDIO not granted — cannot push to talk")
                ContextStore.update { it.copy(micAvailable = false) }
                return@post
            }
            if (pttHeld) return@post
            pttHeld = true
            teardownRecognizer()
            mode = MicMode.PUSH_TO_TALK
            isRunning = true
            Log.i(TAG, "push-to-talk: pressed")
            ContextStore.update {
                it.copy(micState = MicState.VOICE, micAvailable = true, transcriptSnippet = null)
            }
            startRecognizer(PTT_SILENCE_MS)
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
                restoreAfterPtt()
                return@post
            }
            // Do not destroy here — the final result is still to come. onResults/onError restores.
            runCatching { r.stopListening() }
                .onFailure {
                    Log.w(TAG, "stopListening failed on release", it)
                    restoreAfterPtt()
                }
        }
    }

    /** After a press finishes, go back to whatever the latch says. */
    private fun restoreAfterPtt() {
        teardownRecognizer()
        scope.launch {
            val open = GhostPrefs.isMicEnabled(ctx)
            mainHandler.post { if (open) enterOpenMode() else enterOffMode() }
        }
    }

    // ---------------------------------------------------------------- modes

    private fun enterOffMode() {
        mainHandler.removeCallbacksAndMessages(token)
        teardownRecognizer()
        pttHeld = false
        mode = MicMode.OFF
        isRunning = false
        consecutiveErrors = 0
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
        mainHandler.post { enterOffMode() }
        scope.cancel()
    }

    // ---------------------------------------------------------------- recognizer plumbing

    private fun startRecognizer(silenceMs: Long) {
        if (mode == MicMode.OFF) return
        @Suppress("DEPRECATION")
        val r = SpeechRecognizer.createSpeechRecognizer(ctx, AIAI_RECOGNIZER)
        r.setRecognitionListener(CommandListener())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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
                scheduleRestart(OPEN_BACKOFF_MS)
            }
            .isSuccess
        if (started) recognizer = r
    }

    private fun scheduleRestart(delayMs: Long) {
        if (mode != MicMode.OPEN) return
        mainHandler.postDelayed({ if (mode == MicMode.OPEN) startRecognizer(OPEN_SILENCE_MS) }, token, delayMs)
    }

    private fun teardownRecognizer() {
        val r = recognizer
        recognizer = null
        runCatching { r?.cancel() }
        runCatching { r?.destroy() }
    }

    private inner class CommandListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (mode == MicMode.OPEN) ContextStore.update { it.copy(micState = MicState.SILENT) }
        }

        override fun onBeginningOfSpeech() {
            ContextStore.update { it.copy(micState = MicState.VOICE) }
        }

        override fun onRmsChanged(rms: Float) {
            // Not written to ContextState.audioLevelDb. SpeechRecognizer's RMS is its own scale
            // (roughly -2..10, against no documented reference), while that field is specified as
            // dBFS and is rendered as `mic=voice(NdB)` into the [CONTEXT] block the model reads.
            // With the AudioRecord path gone there is no honest dBFS source, so the field stays
            // unset rather than carrying a number in the wrong units.
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            ContextStore.update { it.copy(micState = MicState.SILENT) }
        }

        override fun onError(error: Int) {
            val name = errorName(error)
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.w(TAG, "RECORD_AUDIO revoked — stopping the mic")
                ContextStore.update { it.copy(micAvailable = false) }
                enterOffMode()
                return
            }
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
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            consecutiveErrors = 0
            Log.d(TAG, "recognizer results: \"$text\"")

            if (text.isNotBlank()) dispatchVoiceCommand(text)

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
            scheduleRestart(OPEN_RESTART_MS)
        }

        override fun onPartialResults(partial: Bundle?) {}
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
