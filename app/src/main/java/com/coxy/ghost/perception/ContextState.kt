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
    /**
     * When [activity] last changed, or 0 when it has never been known. Stamped centrally by
     * [ContextStore.update] so no writer has to remember to — see [stampDwell].
     */
    val activitySinceMs: Long = 0L,
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
    // --- Phase 4.5: posture, handling and motion (all event-driven; see PerceptionManager) ---
    /** How the phone is lying. FACE_UP on a desk is a different world from FACE_DOWN in a bag. */
    val posture: Posture = Posture.UNKNOWN,
    /** Vendor pocket-mode sensor. null = unknown/unsupported, not "no". */
    val inPocket: Boolean? = null,
    /** Compass point ("N", "NE", ...) derived from the rotation vector; null until a fix. */
    val heading: String? = null,
    /** Hardware stationary/motion detect — coarser and far cheaper than activity recognition. */
    val motion: MotionState = MotionState.UNKNOWN,
    /** When the phone was last picked up / tilted into the hand. 0 = never this session. */
    val lastPickUpMs: Long = 0L,
    // --- Phase 4.5: nearby devices (BLE) ---
    /** BLE devices seen in the last scan window. -1 = never scanned. */
    val nearbyCount: Int = -1,
    /** Names of *bonded* (already-paired) devices seen nearby — never unknown strangers' names. */
    val nearbyKnown: String? = null,
    // --- Phase 4.5: telephony + radios ---
    val callState: CallState = CallState.UNKNOWN,
    /** Mobile network generation, e.g. "LTE". null when unknown or no SIM. */
    val networkType: String? = null,
    /** Signal strength 0-4, -1 when unknown. */
    val signalLevel: Int = -1,
    val nfcEnabled: Boolean? = null,
)

/** Physical attitude of the device, from the vendor face-up/down sensor + rotation vector. */
enum class Posture { FACE_UP, FACE_DOWN, UPRIGHT, TILTED, UNKNOWN }

/** Hardware motion detect: a one-shot "it started moving" / "it went still" trigger. */
enum class MotionState { STATIONARY, MOVING, UNKNOWN }

/** Telephony call state. IN_CALL and RINGING are both "do not start talking" signals. */
enum class CallState { IDLE, RINGING, IN_CALL, UNKNOWN }

enum class Activity { STILL, ON_FOOT, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, TILTING, UNKNOWN }

/**
 * HOME/AWAY are bucketed against the stored [HomePoint]; UNKNOWN until Coxy sets one, and also
 * whenever the fix is too vague to answer the question honestly. Coordinates never reach this
 * class — the bucket is the only thing that leaves the perception layer.
 */
enum class LocationBucket { HOME, AWAY, UNKNOWN }

/** Lux thresholds: DARK<10, DIM 10-50, NORMAL 50-1000, BRIGHT>1000. */
enum class LightBucket { DARK, DIM, NORMAL, BRIGHT, UNKNOWN }

/** NEAR = covered / in-pocket (proximity sensor reports near). */
enum class Proximity { NEAR, FAR, UNKNOWN }

/** Phase 5 microphone voice-activity state. OFF means the user disabled continuous listening. */
enum class MicState { SILENT, VOICE, UNKNOWN, OFF }

/**
 * One rendering pass over the state: the `[CONTEXT ...]` clauses, and whether any of them actually
 * told the model something.
 *
 * [hasSignal] and [toPromptBlock] are both derived from this, which is the whole point. They used
 * to be two independent lists of conditions, and Phase 4.5 drifted them apart in both directions:
 *
 *  - `hasSignal` counted `callState != UNKNOWN`, `nfcEnabled != null`, `inPocket != null` and
 *    `nearbyCount >= 0`, while the block deliberately prints call state only when it means "do not
 *    speak", NFC only when on, pocket only when true and nearby only above zero. `RadioWatcher` sets
 *    call state to IDLE and NFC to true/false the moment it starts, so from then on an idle phone
 *    claimed signal and then rendered nothing but the four unconditional unknowns — exactly the
 *    waste `hasSignal` was added to prevent.
 *  - In the other direction it dropped real context on the floor. `gms:off`, `just_picked_up`, a
 *    speed, a step rate, a weak signal or a transcript with no timestamp each render a clause that
 *    `hasSignal` did not recognise, so with nothing else sensed the whole block was suppressed and
 *    the model never saw them.
 *
 * Neither half was untested — the omissions are pinned field by field, and so was the signal set.
 * What had no test was the relationship between them. Deriving both from one pass makes that
 * invariant structural rather than remembered: the block is worth sending exactly when it has
 * something to say.
 */
private class ContextClauses {
    val parts = mutableListOf<String>()

    /** True once a clause has been added that narrows anything down. */
    var carriesInfo = false
        private set

    /**
     * Add a clause. [informative] is false only for the four fields that render unconditionally —
     * activity, location, light, proximity — while holding their `unknown` value: they keep the
     * block's shape stable but tell the model nothing it did not already assume.
     */
    fun add(clause: String, informative: Boolean = true) {
        parts += clause
        if (informative) carriesInfo = true
    }
}

/**
 * Stamp [ContextState.activitySinceMs] when the activity bucket changes.
 *
 * Called by [ContextStore.update] on every write rather than by each writer, because there are
 * several — the GMS activity-recognition broadcast, the speed-based backstop in
 * [PerceptionManager.handleLocation], and the reset in `stop()` — and a dwell clock that one of them
 * forgets to stamp is worse than no dwell clock at all: it would report a duration that silently
 * belongs to a previous state.
 *
 * `update`'s transform can re-run under CAS contention. Re-running this is harmless — reading the
 * clock is idempotent for the purpose, and the second read differs by microseconds.
 */
internal fun ContextState.stampDwell(previous: ContextState): ContextState = when {
    activity == previous.activity -> this
    activity == Activity.UNKNOWN -> copy(activitySinceMs = 0L)
    else -> copy(activitySinceMs = System.currentTimeMillis())
}

/**
 * How long the current activity has held, as a compact label, or null when that is not worth
 * saying.
 *
 * Below [DWELL_MIN_MS] there is nothing to report — every state has just started at some point, and
 * "0m" is noise on a block that rides every prompt. The unit stays coarse for the same reason:
 * "40m" and "2h15m" answer "have I been sitting here too long", which is the question this exists
 * for, and seconds would only cost tokens.
 */
private fun dwellLabel(sinceMs: Long, now: Long): String? {
    if (sinceMs <= 0L) return null
    val heldMs = now - sinceMs
    if (heldMs < DWELL_MIN_MS) return null
    val minutes = heldMs / 60_000L
    if (minutes < 60L) return "${minutes}m"
    val hours = minutes / 60L
    val remainder = minutes % 60L
    return if (remainder == 0L) "${hours}h" else "${hours}h${remainder}m"
}

/** Below a minute, a dwell time is not information. */
private const val DWELL_MIN_MS = 60_000L

/**
 * `still`, `still(100%)`, `still(42m)` or `still(100%,42m)` — the qualifiers are whichever of
 * confidence and dwell actually have something to say. Shared by the prompt block and the status
 * line so the two cannot describe the same activity differently.
 */
private fun ContextState.activityLabel(now: Long): String {
    // An unknown activity takes no qualifiers. `unknown(85%)` is nonsense on its face, and it would
    // also break the hasSignal invariant: the clause would look informative to anything reading the
    // rendered string while renderClauses correctly marks it as carrying nothing.
    if (activity == Activity.UNKNOWN) return Activity.UNKNOWN.name.lowercase()
    val qualifiers = mutableListOf<String>()
    if (activityConfidence > 0) qualifiers += "$activityConfidence%"
    dwellLabel(activitySinceMs, now)?.let { qualifiers += it }
    return activity.name.lowercase() +
        if (qualifiers.isEmpty()) "" else "(${qualifiers.joinToString(",")})"
}

/**
 * Render every clause once, in the order the model reads them.
 *
 * The governing rule for everything below the first four: silent unless it carries information. The
 * block rides on EVERY prompt into a 1.5B model with an 8k window, so an idle phone must cost no
 * more tokens than it did before a sense was added. `ContextStateTest` pins that rule field by field.
 */
private fun ContextState.renderClauses(): ContextClauses {
    val c = ContextClauses()
    val now = System.currentTimeMillis()

    c.add("activity=${activityLabel(now)}", activity != Activity.UNKNOWN)
    c.add("location=${locationBucket.name.lowercase()}", locationBucket != LocationBucket.UNKNOWN)
    if (speedKmh > 0f) c.add("speed=${"%.1f".format(speedKmh)}km/h")
    c.add(
        "light=${lightBucket.name.lowercase()}" + if (lightLux >= 0f) "(${lightLux.toInt()}lx)" else "",
        lightBucket != LightBucket.UNKNOWN,
    )
    c.add("proximity=${proximity.name.lowercase()}", proximity != Proximity.UNKNOWN)
    if (stepRatePerMin > 0) c.add("steps=${stepRatePerMin}/min")

    // Mic: voice/silent + level, or a single "GHOST cannot hear right now".
    // `mic:off` now also covers an unavailable mic. It used to render only for the user-disabled
    // case, while `micAvailable = false` counted as signal and printed nothing — so "the mic is
    // broken" reached hasSignal() and never reached the model. This mirrors `camera:off` below and
    // reuses vocabulary the system prompt already describes. Unavailable wins over a stale VOICE.
    if (!micAvailable || micState == MicState.OFF) {
        c.add("mic:off")
    } else if (micState != MicState.UNKNOWN) {
        c.add("mic=${micState.name.lowercase()}" + if (audioLevelDb > -100f) "(${audioLevelDb.toInt()}dB)" else "")
    }
    transcriptSnippet?.takeIf { it.isNotBlank() }?.let {
        c.add("heard=\"${truncateWords(it, 15)}\"")
    }
    // Camera: metadata only — the model cannot see the frame, just knows one was captured.
    if (lastFrameMs > 0L) {
        val br = if (sceneBrightness >= 0f) sceneBrightness.toInt() else -1
        c.add("camera=captured(${lastFrameFacing ?: "unknown"},br $br)")
    } else if (!cameraAvailable) {
        c.add("camera:off")
    }
    if (!gmsAvailable) c.add("gms:off")

    // --- Phase 4.5 additions ---
    if (posture != Posture.UNKNOWN) c.add("posture=${posture.name.lowercase()}")
    if (inPocket == true) c.add("pocket=yes")
    heading?.let { c.add("heading=$it") }
    if (motion != MotionState.UNKNOWN) c.add("motion=${motion.name.lowercase()}")
    // Only surface a pick-up while it is still "just happened" — a stale timestamp is noise.
    if (lastPickUpMs > 0L && now - lastPickUpMs < PICK_UP_RECENT_MS) {
        c.add("just_picked_up")
    }
    if (nearbyCount > 0) {
        c.add("nearby=$nearbyCount")
        nearbyKnown?.takeIf { it.isNotBlank() }?.let { c.add("known=\"${truncateWords(it, 8)}\"") }
    }
    // Call state matters because it means "do not start talking"; IDLE is the boring default.
    if (callState == CallState.RINGING || callState == CallState.IN_CALL) {
        c.add("call=${callState.name.lowercase()}")
    }
    // Network only when it is bad enough to matter (cloud calls will struggle) or during a call.
    if (signalLevel in 0..1 || (signalLevel >= 0 && callState == CallState.IN_CALL)) {
        c.add("signal=$signalLevel/4" + (networkType?.let { "($it)" } ?: ""))
    }
    if (nfcEnabled == true) c.add("nfc:on")

    return c
}

/**
 * True when the `[CONTEXT ...]` block would tell the model something.
 *
 * [toPromptBlock] always returns a populated block — activity, location, light and proximity are
 * unconditional — so without this guard an idle device prepends four "unknown" fields to every
 * single prompt. Callers use it to decide whether to send the block at all; see
 * [GhostPrompts.buildUserPrompt].
 */
fun ContextState.hasSignal(): Boolean = renderClauses().carriesInfo

/**
 * The one-line ASCII context block prepended to each user message before [sendUserPrompt].
 * Kept compact to minimise token cost on a 1.5B model with an 8k context budget — transcripts are
 * hard-truncated to ~15 words, and the camera is reported as metadata only (the model cannot see).
 */
fun ContextState.toPromptBlock(): String = "[CONTEXT ${renderClauses().parts.joinToString(" ")}]"

/** A pick-up older than this is no longer "just now" and is dropped from the context block. */
private const val PICK_UP_RECENT_MS = 90_000L

/** Human-readable line for the perception status TextView in MainActivity. */
fun ContextState.toStatusLine(): String {
    if (timestampMs == 0L && lastTranscriptMs == 0L && lastFrameMs == 0L) return "Perception: idle"
    val s = StringBuilder("Perception: ")
    s.append(activityLabel(System.currentTimeMillis()))
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