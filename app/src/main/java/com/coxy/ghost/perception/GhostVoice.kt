package com.coxy.ghost.perception

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.math.exp

/**
 * GHOST out loud.
 *
 * Live mode was mute: she heard, thought and answered in text on a screen you were holding at arm's
 * length while talking to it. Speaking is the half that makes it a conversation rather than a
 * dictation box.
 *
 * The interesting part is not the synthesis, it is [pulse]. `onRangeStart` fires as each word
 * begins, which is a real signal about a real thing happening — so the orb beats on her actual
 * words rather than on a sine wave pretending to be speech. Same principle as the mic meter: if it
 * moves, something is genuinely happening, and when nothing is happening it is still.
 *
 * Stopped, never queued. If she is interrupted the old sentence dies immediately — a reply to a
 * question two turns ago finishing over the top of a new one is worse than silence.
 */
object GhostVoice {

    private const val TAG = "GhostVoice"

    /** How fast a word's beat fades. Long enough to read as a pulse, short enough to separate words. */
    private const val PULSE_DECAY_MS = 220f

    /** Slightly under natural pace: she is being listened to across a room, not read to. */
    private const val RATE = 0.98f
    private const val PITCH = 1.0f

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    /** True while an utterance is in flight. Drives the orb's SPEAKING state. */
    @Volatile var speaking: Boolean = false
        private set

    @Volatile private var lastWordAt = 0L

    /** Whether GHOST speaks at all. Off by default is wrong here — Live mode asked for a voice. */
    @Volatile var enabled: Boolean = true

    fun init(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "no speech engine available; GHOST stays silent")
                return@TextToSpeech
            }
            tts?.apply {
                language = Locale.getDefault().takeIf {
                    setLanguage(it) != TextToSpeech.LANG_MISSING_DATA &&
                        setLanguage(it) != TextToSpeech.LANG_NOT_SUPPORTED
                } ?: Locale.UK
                setSpeechRate(RATE)
                setPitch(PITCH)
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                        lastWordAt = System.currentTimeMillis()
                    }

                    override fun onDone(utteranceId: String?) {
                        speaking = false
                    }

                    @Deprecated("Required by the base class", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                        speaking = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "speech failed: $errorCode")
                        speaking = false
                    }

                    /**
                     * One call per word. This is the orb's heartbeat — see the note on this object.
                     */
                    override fun onRangeStart(
                        utteranceId: String?, start: Int, end: Int, frame: Int
                    ) {
                        lastWordAt = System.currentTimeMillis()
                    }
                })
            }
            Log.i(TAG, "speech ready")
        }
    }

    /**
     * Say [text], cutting off whatever was being said.
     *
     * Bracketed blocks are stripped first. `[ACTION ok launch_app: opened WhatsApp]` is
     * instrumentation for the screen, and reading it aloud would be GHOST narrating her own
     * plumbing — the same reason it is kept out of her memory.
     */
    fun speak(text: String) {
        if (!enabled || !ready) return
        val spoken = strip(text)
        if (spoken.isBlank()) return
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "ghost-${System.nanoTime()}")
    }

    fun stop() {
        runCatching { tts?.stop() }
        speaking = false
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        speaking = false
    }

    /**
     * A decaying beat since the last word began, 0..1.
     *
     * Read once per frame by the orb, alongside the mic level, so one "how much is happening"
     * number covers both halves of a conversation.
     */
    fun pulse(): Float {
        if (!speaking) return 0f
        val dt = (System.currentTimeMillis() - lastWordAt).toFloat()
        return exp(-dt / PULSE_DECAY_MS).coerceIn(0f, 1f)
    }

    /** Bracketed instrumentation, and the markdown emphasis a speech engine reads as characters. */
    private val NOT_SPOKEN = Regex("\\[[A-Z_]+\\b[^\\]]*\\]|\\((?:Saved|skipped)[^)]*\\)")

    internal fun strip(text: String): String =
        text.replace(NOT_SPOKEN, " ")
            .replace(Regex("[*_`#]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
