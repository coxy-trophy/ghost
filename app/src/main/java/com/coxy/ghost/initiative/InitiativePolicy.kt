package com.coxy.ghost.initiative

import com.coxy.ghost.perception.CallState
import com.coxy.ghost.perception.ContextState
import com.coxy.ghost.perception.Transition
import com.coxy.ghost.perception.TransitionKind

/** What the policy concluded, and why — the reason is logged, so "she said nothing" is answerable. */
sealed interface InitiativeDecision {
    data class Speak(val transition: Transition) : InitiativeDecision
    data class Hold(val reason: String) : InitiativeDecision
}

/**
 * Whether a noticed change is worth interrupting someone over.
 *
 * This is the whole safety surface of speaking first, so it is a pure function of its inputs and
 * every rule is checked in the fast test tier. The bias throughout is toward silence: a missed
 * remark costs nothing, and a phone that comments on its own accord at the wrong moment is the
 * failure this project has already met once, when an always-on spotter woke on room noise every
 * thirty seconds.
 *
 * Note the trait gate. `initiative` has been one of the eight personality sliders since Phase 6.3 —
 * drift-budgeted, snapshot-versioned, translated into "You proactively suggest next steps" in the
 * system prompt — and it has never had a mechanism to act on, because every inference in the app
 * begins with something Coxy typed or said. The slider could only ever be expressed *inside a reply
 * to him*, which is the one moment initiative is not needed. This is what it now controls.
 */
object InitiativePolicy {

    /** Below this the slider means "don't", and no transition gets through. */
    const val MIN_TRAIT = 50

    /** The floor between two unprompted remarks, whatever happens in between. */
    const val COOLDOWN_MS = 45 * 60_000L

    /** The same kind of observation does not bear repeating inside this window. */
    const val REPEAT_WINDOW_MS = 3 * 60 * 60_000L

    /** A hard ceiling per day, independent of the cooldown. */
    const val DAILY_CAP = 6

    /** Local-clock hours in which she stays quiet no matter what changed. */
    const val QUIET_FROM_HOUR = 22
    const val QUIET_UNTIL_HOUR = 8

    fun decide(
        transitions: List<Transition>,
        state: ContextState,
        initiativeTrait: Int,
        enabled: Boolean,
        now: Long,
        localHour: Int,
        lastSpokeAtMs: Long,
        spokenToday: Int,
        lastSpokeByKind: Map<TransitionKind, Long>,
    ): InitiativeDecision {
        if (!enabled) return InitiativeDecision.Hold("speaking first is switched off")

        if (initiativeTrait < MIN_TRAIT) {
            return InitiativeDecision.Hold("initiative trait is $initiativeTrait, below $MIN_TRAIT")
        }

        if (transitions.isEmpty()) return InitiativeDecision.Hold("nothing changed")

        // The clearest "do not start talking" signal the device has, and it is already in the
        // context block for exactly this reason.
        if (state.callState == CallState.IN_CALL || state.callState == CallState.RINGING) {
            return InitiativeDecision.Hold("he is on a call")
        }

        if (isQuietHour(localHour)) {
            return InitiativeDecision.Hold("quiet hours ($QUIET_FROM_HOUR:00–$QUIET_UNTIL_HOUR:00)")
        }

        if (spokenToday >= DAILY_CAP) {
            return InitiativeDecision.Hold("already spoke $spokenToday times today (cap $DAILY_CAP)")
        }

        // lastSpokeAtMs == 0 means she never has; that must not read as "infinitely long ago" only
        // by accident, so it is handled by the subtraction being large rather than special-cased.
        if (lastSpokeAtMs > 0L && now - lastSpokeAtMs < COOLDOWN_MS) {
            val waited = (now - lastSpokeAtMs) / 60_000L
            return InitiativeDecision.Hold("only ${waited}m since she last spoke unprompted")
        }

        val fresh = transitions.firstOrNull { t ->
            val last = lastSpokeByKind[t.kind] ?: 0L
            last == 0L || now - last >= REPEAT_WINDOW_MS
        } ?: return InitiativeDecision.Hold("already remarked on all of this recently")

        return InitiativeDecision.Speak(fresh)
    }

    /** Wraps midnight, so it cannot be written as a single comparison. */
    internal fun isQuietHour(hour: Int): Boolean =
        if (QUIET_FROM_HOUR > QUIET_UNTIL_HOUR) {
            hour >= QUIET_FROM_HOUR || hour < QUIET_UNTIL_HOUR
        } else {
            hour in QUIET_FROM_HOUR until QUIET_UNTIL_HOUR
        }
}
