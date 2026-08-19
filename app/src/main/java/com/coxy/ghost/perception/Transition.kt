package com.coxy.ghost.perception

/**
 * A change in GHOST's world worth reacting to.
 *
 * Perception has always been able to answer "what is happening now"; nothing has ever noticed
 * *that it changed*. Every derivative was discarded on the next sensor write, so the architecture's
 * promised "context buffer — recent events + current state" only ever had the second half. This is
 * the first half, deliberately kept to a handful of events that correspond to something real
 * happening in a life rather than to every field that differs between two samples.
 */
data class Transition(
    val kind: TransitionKind,
    val atMs: Long,
    /** Plain language, written to be handed straight to the model. No field names, no jargon. */
    val description: String,
)

enum class TransitionKind {
    /** Came back inside the home radius after being outside it. */
    ARRIVED_HOME,

    /** Left the home radius. */
    LEFT_HOME,

    /** Started walking after being still long enough for it to mean something. */
    STARTED_MOVING,

    /** Was in a vehicle and is now still — arrived somewhere. */
    ARRIVED_AFTER_TRAVEL,

    /** Has been still for a long stretch. A threshold crossing, not a state change. */
    LONG_STILLNESS,
}
