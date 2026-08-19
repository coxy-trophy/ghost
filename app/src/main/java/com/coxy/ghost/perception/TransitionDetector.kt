package com.coxy.ghost.perception

/**
 * Turns two samples of [ContextState] into the list of [Transition]s between them.
 *
 * Pure, so the judgement about what counts as an event is checkable without a device. That matters
 * more here than in most of this codebase: an over-eager rule does not produce a wrong log line, it
 * produces a phone that talks to you about nothing.
 *
 * Two guards run through all of it:
 *
 *  - **Nothing fires out of `UNKNOWN`.** A phone that has just booted goes `UNKNOWN → HOME` on its
 *    first GPS fix, and "welcome home" on every reboot is exactly the kind of thing that makes an
 *    ambient assistant feel broken. A transition requires knowing both where you were and where you
 *    are.
 *  - **Duration qualifies the change.** Standing up after thirty seconds is not an event. Standing
 *    up after two hours might be.
 */
object TransitionDetector {

    /** Still for this long is worth remarking on by itself. */
    const val LONG_STILLNESS_MS = 90 * 60_000L

    /** Stillness shorter than this does not make the following movement interesting. */
    const val MEANINGFUL_STILLNESS_MS = 30 * 60_000L

    private val MOVING = setOf(Activity.WALKING, Activity.ON_FOOT, Activity.RUNNING)

    /**
     * Detect what changed between [previous] and [next].
     *
     * [lastEvaluatedMs] is when [previous] was last looked at, and exists for [LONG_STILLNESS],
     * which is a threshold being crossed rather than a value changing — without knowing when the
     * last check happened there is no way to fire it once instead of on every sample thereafter.
     */
    fun detect(
        previous: ContextState,
        next: ContextState,
        now: Long,
        lastEvaluatedMs: Long,
    ): List<Transition> {
        val out = mutableListOf<Transition>()

        // --- place ---
        if (previous.locationBucket == LocationBucket.AWAY && next.locationBucket == LocationBucket.HOME) {
            out += Transition(TransitionKind.ARRIVED_HOME, now, "he just got home")
        }
        if (previous.locationBucket == LocationBucket.HOME && next.locationBucket == LocationBucket.AWAY) {
            out += Transition(TransitionKind.LEFT_HOME, now, "he just left home")
        }

        // --- movement ---
        if (previous.activity == Activity.STILL && next.activity in MOVING) {
            val heldMs = stillnessBefore(previous, next)
            if (heldMs >= MEANINGFUL_STILLNESS_MS) {
                out += Transition(
                    TransitionKind.STARTED_MOVING,
                    now,
                    "he has started moving after sitting still for ${minutesPhrase(heldMs)}",
                )
            }
        }
        if (previous.activity == Activity.IN_VEHICLE && next.activity == Activity.STILL) {
            out += Transition(TransitionKind.ARRIVED_AFTER_TRAVEL, now, "he has arrived somewhere after travelling")
        }

        // --- a threshold, not a change ---
        // Only meaningful while the same stretch of stillness is running: if activitySinceMs moved,
        // this is a different stretch and the crossing belongs to it, not to this comparison.
        if (next.activity == Activity.STILL &&
            next.activitySinceMs > 0L &&
            previous.activitySinceMs == next.activitySinceMs
        ) {
            val heldNow = now - next.activitySinceMs
            val heldAtLastCheck = lastEvaluatedMs - next.activitySinceMs
            if (heldAtLastCheck < LONG_STILLNESS_MS && heldNow >= LONG_STILLNESS_MS) {
                out += Transition(
                    TransitionKind.LONG_STILLNESS,
                    now,
                    "he has been still for ${minutesPhrase(heldNow)}",
                )
            }
        }

        return out
    }

    /**
     * How long the stillness that just ended had run.
     *
     * Read off [previous], because [next] has already restarted the clock for the new activity —
     * `activitySinceMs` is stamped on change, so by the time movement is visible the old duration
     * only survives in the older sample.
     */
    private fun stillnessBefore(previous: ContextState, next: ContextState): Long {
        if (previous.activitySinceMs <= 0L) return 0L
        val endedAt = if (next.activitySinceMs > previous.activitySinceMs) {
            next.activitySinceMs
        } else {
            return 0L
        }
        return endedAt - previous.activitySinceMs
    }

    /** "40 minutes", "2 hours", "2 hours and 15 minutes" — spoken language, not `2h15m`. */
    internal fun minutesPhrase(ms: Long): String {
        val minutes = ms / 60_000L
        if (minutes < 60L) return "$minutes minutes"
        val hours = minutes / 60L
        val rest = minutes % 60L
        val hourWord = if (hours == 1L) "an hour" else "$hours hours"
        return if (rest == 0L) hourWord else "$hourWord and $rest minutes"
    }
}
