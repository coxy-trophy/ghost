package com.coxy.ghost.perception

/**
 * GHOST's bucketed view of the world: raw sensor / GMS / mic / camera readings reduced to a small
 * set of meaningful states. This is what feeds the model as context — never raw floats or bytes.
 *
 * See GHOST.md §5: the perception layer buckets raw data into states ("walking", "at home",
 * "dark room"), and the context buffer feeds *state* into the model. Phase 5 adds mic (continuous,
 * VAD-gated on-device STT → a short `heard=` transcript) and camera (on-demand capture-as-metadata
 * → brightness/facing, since the 1.5B text model cannot ingest pixels). Phase 6 makes the mic
 * wake-word-triggered: only utterances containing "Hey Ghost"/"Ghost" are kept, the wake word is
 * stripped, and the remainder becomes a [voiceCommand] GHOST acts on (Siri-style). Non-wake speech
 * is dropped at the device — `heard=` now only ever holds a wake-directed command.
 */
data class ContextState(
    val activity: Activity = Activity.UNKNOWN,
    val activityConfidence: Int = 0,
    val locationBucket: LocationBucket = LocationBucket.UNKNOWN,
    val speedKmh: Float = 0f,
    val lightBucket: LightBucket = LightBucket.UNKNOWN,
    val lightLux: Float = -1f,
    val proximity: Proximity = Proximity.UNKNOWN,
    val stepRatePerMin: Int = 0,
    val gmsAvailable: Boolean = true,
    // --- Phase 5: microphone (continuous) ---
    val micState: MicState = MicState.UNKNOWN,
    val audioLevelDb: Float = -1f,           // short-term RMS in dBFS-ish (negative)
    val transcriptSnippet: String? = null,   // latest utterance text (already truncated to ~15 words)
    val lastTranscriptMs: Long = 0L,
    val micAvailable: Boolean = true,
    // --- Phase 5: camera (on-demand) ---
    val sceneBrightness: Float = -1f,        // 0..255 luma mean of the last captured frame
    val lastFrameMs: Long = 0L,
    val lastFrameFacing: String? = null,     // "back" / "front"
    val cameraAvailable: Boolean = true,
    val timestampMs: Long = 0L,
    // --- Phase 6: wake-word voice commands ("Hey Ghost, ...") ---
    // The stripped command after the wake word (null until one is heard). MicListener writes it;
    // GhostService observes voiceCommandSeq and runs inference on it.
    val voiceCommand: String? = null,
    // Monotonic counter bumped on each new voice command. Observers detect "a new command arrived"
    // by comparing to the last seq they handled (the command text alone can repeat).
    val voiceCommandSeq: Long = 0L,
    // The streamed reply to the current voice command (null when none in flight). MainActivity
    // renders this into the chat as it grows.
    val voiceResponse: String? = null,
)

enum class Activity { STILL, ON_FOOT, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, TILTING, UNKNOWN }

/** HOME/AWAY require a persisted home point (deferred past Phase 4); UNKNOWN until then. */
enum class LocationBucket { HOME, AWAY, UNKNOWN }

/** Lux thresholds: DARK<10, DIM 10-50, NORMAL 50-1000, BRIGHT>1000. */
enum class LightBucket { DARK, DIM, NORMAL, BRIGHT, UNKNOWN }

/** NEAR = covered / in-pocket (proximity sensor reports near). */
enum class Proximity { NEAR, FAR, UNKNOWN }

/** Phase 5 microphone voice-activity state. OFF means the user disabled continuous listening. */
enum class MicState { SILENT, VOICE, UNKNOWN, OFF }

/**
 * The one-line ASCII context block prepended to each user message before [sendUserPrompt].
 * Kept compact to minimise token cost on a 1.5B model with an 8k context budget — transcripts are
 * hard-truncated to ~15 words, and the camera is reported as metadata only (the model cannot see).
 */
fun ContextState.toPromptBlock(): String {
    val parts = mutableListOf<String>()
    parts += "activity=${activity.name.lowercase()}" + if (activityConfidence > 0) "($activityConfidence%)" else ""
    parts += "location=${locationBucket.name.lowercase()}"
    if (speedKmh > 0f) parts += "speed=${"%.1f".format(speedKmh)}km/h"
    parts += "light=${lightBucket.name.lowercase()}" + if (lightLux >= 0f) "(${lightLux.toInt()}lx)" else ""
    parts += "proximity=${proximity.name.lowercase()}"
    if (stepRatePerMin > 0) parts += "steps=${stepRatePerMin}/min"
    // Mic: voice/silent/off + level, and the latest heard utterance (truncated).
    if (micState != MicState.UNKNOWN && micState != MicState.OFF) {
        parts += "mic=${micState.name.lowercase()}" + if (audioLevelDb > -100f) "(${audioLevelDb.toInt()}dB)" else ""
    }
    if (micState == MicState.OFF) parts += "mic:off"
    transcriptSnippet?.takeIf { it.isNotBlank() }?.let {
        parts += "heard=\"${truncateWords(it, 15)}\""
    }
    // Camera: metadata only — the model cannot see the frame, just knows one was captured.
    if (lastFrameMs > 0L) {
        val br = if (sceneBrightness >= 0f) sceneBrightness.toInt() else -1
        parts += "camera=captured(${lastFrameFacing ?: "unknown"},br $br)"
    } else if (!cameraAvailable) {
        parts += "camera:off"
    }
    if (!gmsAvailable) parts += "gms:off"
    return "[CONTEXT ${parts.joinToString(" ")}]"
}

/** Human-readable line for the perception status TextView in MainActivity. */
fun ContextState.toStatusLine(): String {
    if (timestampMs == 0L && lastTranscriptMs == 0L && lastFrameMs == 0L) return "Perception: idle"
    val s = StringBuilder("Perception: ")
    s.append(activity.name.lowercase())
    if (activityConfidence > 0) s.append("($activityConfidence%)")
    s.append(' ').append(locationBucket.name.lowercase())
    if (speedKmh > 0f) s.append(" ").append("%.1f".format(speedKmh)).append("km/h")
    s.append(' ').append(lightBucket.name.lowercase())
    if (lightLux >= 0f) s.append("(${lightLux.toInt()}lx)")
    s.append(' ').append(proximity.name.lowercase())
    if (stepRatePerMin > 0) s.append(' ').append(stepRatePerMin).append("spm")
    when (micState) {
        MicState.OFF -> s.append(" mic:off")
        MicState.UNKNOWN -> {} // nothing
        else -> {
            s.append(' ').append("mic:").append(micState.name.lowercase())
            if (audioLevelDb > -100f) s.append("(").append(audioLevelDb.toInt()).append("dB)")
        }
    }
    transcriptSnippet?.takeIf { it.isNotBlank() }?.let {
        s.append(" heard=\"").append(truncateWords(it, 12)).append("\"")
    }
    if (lastFrameMs > 0L) {
        s.append(" cam:").append(lastFrameFacing ?: "unknown")
        if (sceneBrightness >= 0f) s.append("(br ").append(sceneBrightness.toInt()).append(")")
    }
    if (!gmsAvailable) s.append(" gms:off")
    return s.toString()
}

/** Hard word-truncate so a long transcript can't blow the context budget. */
private fun truncateWords(text: String, maxWords: Int): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.size <= maxWords) text.trim() else words.take(maxWords).joinToString(" ") + "…"
}