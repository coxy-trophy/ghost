package com.coxy.ghost.perception

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
 * **Which voice is chosen matters more than any setting on it.** `setLanguage(locale)` — what this
 * did first — takes whatever the engine offers for that language, which on Android is the small
 * embedded voice: intelligible, flat, and unmistakably a machine. Google's engine also ships
 * network voices of far higher quality for the same language, and the difference between them is
 * the whole difference between "robotic" and "someone talking". So the voices are enumerated and
 * ranked, and the best one that can actually be used right now is picked per utterance.
 *
 * Both a network and a local voice are held. Network when there is a network, local when there is
 * not — decided at the moment of speaking, because a voice that needs a connection goes silently
 * mute without one, and a GHOST that stops talking when the Wi-Fi drops would be a worse fault than
 * a flat voice.
 *
 * The other half of sounding human is not the engine at all: it is that written text and spoken
 * text are different. See [shape].
 */
object GhostVoice {

    private const val TAG = "GhostVoice"

    /** How fast a word's beat fades. Long enough to read as a pulse, short enough to separate words. */
    private const val PULSE_DECAY_MS = 220f

    /**
     * Just under natural pace, and just under natural pitch.
     *
     * Both are small on purpose. Pushing rate or pitch far from 1.0 is how a voice starts sounding
     * processed, and the flatness this is fixing was never about speed — it was about which voice
     * was speaking.
     */
    private const val RATE = 0.96f
    private const val PITCH = 0.97f

    /** A beat between sentences. Speech engines run them together; people do not. */
    private const val SENTENCE_GAP_MS = 140L

    /**
     * Google TTS variant codes that are female voices, best first.
     *
     * `rjs` leads because on this device it is one of the newer voices rather than the older
     * concatenative set. Nothing in the API says any of this; see [chooseVoices].
     */
    private val FEMININE = listOf("-rjs-", "-gba-", "-gbg-", "-gbd-", "-sfg-", "-tpf-", "-iol-")

    /**
     * How strongly this looks like the voice GHOST should have. Higher is better, -1 is not on the
     * list at all.
     *
     * An ordered score rather than a boolean, which is what the first version used: every female
     * voice then tied at "yes", and with quality tied at 400 across all thirty the winner came down
     * to iteration order. It picked a reasonable one by luck, and would have picked a different one
     * on the next device.
     */
    private fun feminineScore(voice: Voice): Int {
        val i = FEMININE.indexOfFirst { voice.name.contains(it) }
        return if (i < 0) -1 else FEMININE.size - i
    }

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var appContext: Context? = null

    @Volatile private var networkVoice: Voice? = null
    @Volatile private var localVoice: Voice? = null

    /** Everything installed for this language, best first. Read by the picker. */
    @Volatile var available: List<Voice> = emptyList()
        private set

    /** True while an utterance is in flight. Drives the orb's SPEAKING state. */
    @Volatile var speaking: Boolean = false
        private set

    @Volatile private var lastWordAt = 0L

    /** Whether GHOST speaks at all. On by default — Live mode asked for a voice. */
    @Volatile var enabled: Boolean = true

    /** What was picked, for the System screen and for working out why she sounds like she does. */
    @Volatile var voiceName: String? = null
        private set

    /** Coxy's chosen voice, if he has picked one. Beats every heuristic below. */
    @Volatile var preferredVoice: String? = null

    /** True when Piper is loaded and speaking, rather than Android's engine. */
    val usingPiper: Boolean get() = piperWanted && PiperVoice.ready

    @Volatile var piperWanted: Boolean = true

    fun init(context: Context) {
        if (tts != null) return
        appContext = context.applicationContext
        // Loading the neural model takes seconds, so it happens off the main thread and the
        // platform engine below is initialised regardless — she can speak the moment she has
        // something to say, in whichever voice is ready by then.
        Thread({ PiperVoice.load(context.applicationContext) }, "ghost-piper-load").apply {
            isDaemon = true
            start()
        }
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "no speech engine available; GHOST stays silent")
                return@TextToSpeech
            }
            tts?.apply {
                setSpeechRate(RATE)
                setPitch(PITCH)
                chooseVoices(this)
                setOnUtteranceProgressListener(progress)
            }
        }
    }

    /**
     * Rank every installed voice and keep the best network one and the best local one.
     *
     * Ranking, in order: quality first, then whether it is a network voice — Google's are the good
     * ones and are marked as requiring a connection — then an exact country match, so en-GB beats
     * en-US for a device set to the UK.
     *
     * Every candidate is logged. A voice sounding wrong is the kind of complaint that cannot be
     * debugged from the code alone, and the list of what was actually available on the device at
     * the time is the only thing that answers it.
     */
    private fun chooseVoices(engine: TextToSpeech) {
        val want = Locale.getDefault()
        val all = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
        val usable = all.filter {
            it.locale.language == want.language &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        Log.i(TAG, "voices for ${want.language}: " + usable.joinToString {
            "${it.name}(q=${it.quality}${if (it.isNetworkConnectionRequired) ",net" else ""})"
        }.ifEmpty { "none" })

        // Quality cannot be the primary key here, whatever the API suggests: every voice this
        // device offers reports 400, so ranking by it picks whichever happened to be first.
        //
        // Gender has no field in the API at all, so it comes from Google's variant codes. GHOST is
        // written as a girl — "not a mode, not a setting" — and the first version of this picked
        // en-gb-x-gbb, which is the male voice, so she was answering in someone else's. The list
        // below is a heuristic on a naming convention, which is exactly why the picker exists:
        // this decides the default, and Coxy decides the voice.
        val rank = compareBy<Voice>(
            { feminineScore(it) },
            { it.quality },
            { if (it.locale.country == want.country) 1 else 0 }
        )
        available = usable.sortedWith(rank.reversed())

        // A chosen voice wins outright, and is held in both slots so it is used online or off.
        val chosen = preferredVoice?.let { name -> usable.firstOrNull { it.name == name } }
        if (chosen != null) {
            networkVoice = chosen
            localVoice = chosen
        } else {
            networkVoice = usable.filter { it.isNetworkConnectionRequired }.maxWithOrNull(rank)
            localVoice = usable.filterNot { it.isNetworkConnectionRequired }.maxWithOrNull(rank)
        }

        // Fall back to the engine's own default rather than going mute: a device with an unusual
        // voice set should still speak, just not as well.
        if (networkVoice == null && localVoice == null) {
            engine.language = want
            voiceName = engine.voice?.name
        }
        Log.i(TAG, "picked network=${networkVoice?.name} local=${localVoice?.name}")
    }

    /**
     * The best voice usable right now.
     *
     * Decided per utterance, not once at startup: the good voices need a connection, and whether
     * there is one changes while the app is running.
     */
    private fun voiceForNow(): Voice? {
        val online = runCatching {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)
        return if (online) networkVoice ?: localVoice else localVoice ?: networkVoice
    }

    @Volatile private var utterance = 0L
    @Volatile private var stateCallback: ((Boolean) -> Unit)? = null
    private fun current(id: String?) = id?.startsWith("ghost-$utterance-") == true
    @Synchronized private fun playback(stamp: Long, active: Boolean) {
        if (stamp != utterance) return
        speaking = active
        if (active) lastWordAt = System.currentTimeMillis()
        val callback = stateCallback
        if (!active) stateCallback = null
        callback?.invoke(active)
    }
    @Synchronized private fun playbackId(id: String?, active: Boolean) {
        if (current(id)) playback(utterance, active)
    }
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { playbackId(utteranceId, true) }
        override fun onDone(utteranceId: String?) {
            if (utteranceId?.endsWith("-last") == true) playbackId(utteranceId, false)
        }
        @Deprecated("Required by base class")
        override fun onError(utteranceId: String?) { playbackId(utteranceId, false) }
        override fun onError(utteranceId: String?, errorCode: Int) { playbackId(utteranceId, false) }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (current(utteranceId)) lastWordAt = System.currentTimeMillis()
        }
    }

    /** Completion follows actual playback, with cancelled utterances unable to finish a new one. */
    @Synchronized fun speak(text: String, onState: ((Boolean) -> Unit)? = null) {
        stop()
        val stamp = utterance
        stateCallback = onState
        val spoken = shape(strip(text))
        if (!enabled || spoken.isBlank()) { playback(stamp, false); return }
        if (piperWanted && PiperVoice.ready) {
            Thread({
                if (stamp != utterance) return@Thread
                val ok = PiperVoice.speak(spoken,
                    onProgress = { if (stamp == utterance) playback(stamp, true) },
                    onDone = {}, isCurrent = { stamp == utterance })
                if (stamp == utterance) {
                    if (ok) playback(stamp, false) else platformSpeak(spoken, stamp)
                }
            }, "ghost-piper-speak").apply { isDaemon = true }.start()
        } else platformSpeak(spoken, stamp)
    }

    private fun platformSpeak(spoken: String, stamp: Long) {
        if (stamp != utterance) return
        val engine = tts
        if (!ready || engine == null) { playback(stamp, false); return }
        val parts = sentences(spoken)
        if (parts.isEmpty()) { playback(stamp, false); return }
        voiceForNow()?.let { engine.voice = it; voiceName = it.name }
        parts.forEachIndexed { i, sentence ->
            if (stamp != utterance) return
            val last = i == parts.lastIndex
            val id = "ghost-$stamp-$i" + if (last) "-last" else ""
            val result = engine.speak(sentence,
                if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (result == TextToSpeech.ERROR) { playback(stamp, false); return }
            if (!last) engine.playSilentUtterance(SENTENCE_GAP_MS, TextToSpeech.QUEUE_ADD, "ghost-$stamp-gap-$i")
        }
    }

    /** Re-run selection after Coxy picks a voice, without restarting the engine. */
    fun applyPreferred(name: String?) {
        preferredVoice = name
        // May be called before the engine has finished starting, in which case chooseVoices runs
        // again on ready and picks this up — so the order the two arrive in does not matter.
        if (ready) tts?.let { chooseVoices(it) }
    }

    /** Say one line in [voice], to hear it. Used by the picker and nothing else. */
    fun preview(voice: Voice, line: String) {
        stop()
        val engine = tts ?: return
        engine.voice = voice
        engine.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle(), "ghost-$utterance-preview-last")
    }

    /** Same, in the neural voice. Off the main thread: generation is not instant. */
    fun previewPiper(line: String) {
        if (!PiperVoice.ready) return
        stop()
        val stamp = utterance
        Thread({
            PiperVoice.speak(line, onProgress = { playback(stamp, true) }, onDone = { playback(stamp, false) }, isCurrent = { stamp == utterance })
        }, "ghost-piper-preview").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        val stoppedCallback = stateCallback
        utterance++
        stateCallback = null
        runCatching { tts?.stop() }
        runCatching { PiperVoice.stop() }
        speaking = false
        stoppedCallback?.invoke(false)
    }

    fun shutdown() {
        stop()
        runCatching { PiperVoice.release() }
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

    /**
     * Written punctuation into spoken punctuation.
     *
     * This is the half that has nothing to do with the engine. GHOST writes with em dashes and
     * parentheses because she is written to read well, and a speech engine has no pause for either
     * — it runs straight through, which is what makes a good voice still sound like a document
     * being processed. Commas it does understand, so the marks that mean "breath" become the mark
     * that produces one.
     *
     * Emoji go entirely: an engine either names them aloud or stops dead on them.
     */
    internal fun shape(text: String): String = text
        .replace(Regex("[\\u2014\\u2013]"), ",")      // em and en dash
        .replace(Regex("\\s+-\\s+"), ", ")            // a spaced hyphen used as a dash
        .replace("…", ".")
        .replace(Regex("[()\\[\\]]"), ",")            // asides get a beat, not a bracket
        .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2190-\\u2BFF\\uFE0F]"), " ")
        .replace(Regex(",\\s*,+"), ",")               // collapse what the above may have doubled
        .replace(Regex("\\s+([,.!?])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd(',')

    /** Split for pacing. Abbreviations are rare enough in her voice not to warrant more than this. */
    internal fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
