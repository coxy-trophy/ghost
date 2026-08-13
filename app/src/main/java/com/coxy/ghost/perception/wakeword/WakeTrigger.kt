package com.coxy.ghost.perception.wakeword

/**
 * Decides when a stream of per-frame wake-word probabilities counts as an actual trigger.
 *
 * The detector runs on a 1-second window advanced 50 ms at a time, so consecutive frames overlap by
 * 95% — a real "Hey Ghost" holds the window for ~1 s and therefore scores high across *many* frames
 * in a row, while a transient (a door, a consonant burst, a clipped sample) spikes one frame and is
 * gone by the next. The original code fired on the first frame over threshold, which threw that
 * distinction away: one spurious frame in the ~1,200 evaluated per minute was enough to stop the
 * AudioRecord stream and hand the mic to SpeechRecognizer. That handover cycles the audio HAL, which
 * is the exact blip Phase 5.2 replaced the always-on recognizer to avoid.
 *
 * Requiring [requiredFrames] consecutive frames costs nothing (the inference already ran) and turns
 * a single-frame false positive into a non-event. It also delays a true trigger by
 * `(requiredFrames - 1) * 50 ms`, which is why the default is small.
 *
 * Pure and synchronous — no Android types — so the JVM suite can pin the behaviour. Not thread-safe:
 * [MicListener] drives it from the mic thread only.
 */
class WakeTrigger(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val requiredFrames: Int = DEFAULT_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    companion object {
        const val DEFAULT_THRESHOLD = 0.80f

        /**
         * Kept at 3 deliberately, and **not** because 3 is quiet — it is not.
         *
         * Measured on RMX3760, 2026-08-13, 180 s per setting against ambient room audio
         * (~-30 dBFS RMS, no wake word spoken), threshold 0.80:
         *
         * ```
         *   frames=3   6 false triggers   ~1 per 30s
         *   frames=4   2                  ~1 per 90s
         *   frames=5   5                  ~1 per 36s
         *   frames=6   2                  ~1 per 90s
         *   frames=7   0                  none in 180s
         *   frames=10  0                  none in 180s
         *   frames=20  0                  none in 180s
         * ```
         *
         * (The 4/5/6 ordering is not monotone because the room is not a controlled environment;
         * read these as an order of magnitude, not a rate.)
         *
         * Cross that against the geometry: the window is 1 s advancing 50 ms, so a phrase lasting
         * D seconds is fully inside the window for only `(1 - D)/0.05 + 1` frames — about 11 at
         * 0.5 s, 7 at 0.7 s, 4 at 0.8 s. A naturally spoken "Hey Ghost" therefore cannot produce
         * many more than ~7 consecutive frames, which is precisely where the false triggers stop.
         * **There is no setting that is both quiet and reliably wakeable**, which is the real
         * verdict on this model: it must be retrained, not tuned.
         *
         * Given that, 3 is the most *diagnostic* value — the most permissive one measured. If the
         * wake word fails to fire here, on a real voice, the model is conclusively the problem
         * rather than the debounce. For a quiet mic before retraining lands, prefer
         * `./tools/kws_gate.sh tune 0.80 7`, or turn the mic off.
         */
        const val DEFAULT_FRAMES = 3
        const val DEFAULT_COOLDOWN_MS = 2000L

        /** A frame this close to firing is worth logging — it is what threshold tuning reads. */
        const val NEAR_MISS_FRACTION = 0.6f
    }

    /** Highest probability seen since the last [resetPeak]. Diagnostic only. */
    var peakProb: Float = 0f
        private set

    /** Consecutive frames currently at or above [threshold]. Diagnostic only. */
    var streak: Int = 0
        private set

    private var lastFireMs: Long = 0L

    /**
     * Feed one frame's probability.
     *
     * @return true exactly once per detected wake word — on the frame that completes the streak.
     */
    fun offer(prob: Float, nowMs: Long): Boolean {
        if (prob > peakProb) peakProb = prob

        if (prob < threshold) {
            streak = 0
            return false
        }

        streak++
        if (streak < requiredFrames) return false

        // Streak is satisfied. Hold the streak at the requirement rather than letting it run away,
        // so the tail of a long utterance cannot re-fire the instant the cooldown expires.
        streak = requiredFrames
        if (nowMs - lastFireMs < cooldownMs) return false

        lastFireMs = nowMs
        streak = 0
        return true
    }

    /** True when the last frame was close enough to threshold to be worth logging while tuning. */
    fun isNearMiss(prob: Float): Boolean = prob >= threshold * NEAR_MISS_FRACTION && prob < threshold

    /** Clear the streak without touching the cooldown. Called when the audio stream is interrupted. */
    fun resetStreak() {
        streak = 0
    }

    /** Clear the running peak. Called after the peak has been logged. */
    fun resetPeak() {
        peakProb = 0f
    }

    /** Arm the cooldown as if a trigger had just fired, e.g. after a command capture finishes. */
    fun armCooldown(nowMs: Long) {
        lastFireMs = nowMs
        streak = 0
    }
}
