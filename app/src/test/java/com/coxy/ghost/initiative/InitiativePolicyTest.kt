package com.coxy.ghost.initiative

import com.coxy.ghost.perception.CallState
import com.coxy.ghost.perception.ContextState
import com.coxy.ghost.perception.Transition
import com.coxy.ghost.perception.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole safety surface of speaking first. Every rule that keeps GHOST quiet is checked here,
 * because the cost of getting these wrong is not a bad answer — it is a phone that interrupts.
 */
class InitiativePolicyTest {

    private val now = 1_800_000_000_000L
    private fun min(n: Long) = n * 60_000L

    private val arrived = Transition(TransitionKind.ARRIVED_HOME, now, "he just got home")
    private val stillness = Transition(TransitionKind.LONG_STILLNESS, now, "he has been still for 2 hours")

    private fun decide(
        transitions: List<Transition> = listOf(arrived),
        state: ContextState = ContextState(),
        trait: Int = 70,
        enabled: Boolean = true,
        hour: Int = 14,
        lastSpokeAtMs: Long = 0L,
        spokenToday: Int = 0,
        byKind: Map<TransitionKind, Long> = emptyMap(),
    ) = InitiativePolicy.decide(
        transitions, state, trait, enabled, now, hour, lastSpokeAtMs, spokenToday, byKind,
    )

    private fun heldReason(d: InitiativeDecision): String {
        assertTrue("expected a hold, got $d", d is InitiativeDecision.Hold)
        return (d as InitiativeDecision.Hold).reason
    }

    @Test
    fun aRealEventOnAnOrdinaryAfternoonGetsThrough() {
        val d = decide()
        assertTrue("expected her to speak, got $d", d is InitiativeDecision.Speak)
        assertEquals(TransitionKind.ARRIVED_HOME, (d as InitiativeDecision.Speak).transition.kind)
    }

    @Test
    fun switchedOffMeansNever() {
        assertTrue(heldReason(decide(enabled = false)).contains("switched off"))
    }

    @Test
    fun theTraitIsTheGate() {
        // This is the mechanism the initiative slider has never had. Below the threshold the
        // personality genuinely cannot speak first, however interesting the event.
        assertTrue(heldReason(decide(trait = 0)).contains("below"))
        assertTrue(heldReason(decide(trait = InitiativePolicy.MIN_TRAIT - 1)).contains("below"))
        assertTrue(decide(trait = InitiativePolicy.MIN_TRAIT) is InitiativeDecision.Speak)
    }

    @Test
    fun nothingChangedMeansNothingToSay() {
        assertEquals("nothing changed", heldReason(decide(transitions = emptyList())))
    }

    @Test
    fun sheDoesNotTalkOverACall() {
        assertTrue(heldReason(decide(state = ContextState(callState = CallState.IN_CALL))).contains("call"))
        assertTrue(heldReason(decide(state = ContextState(callState = CallState.RINGING))).contains("call"))
        // Idle is the ordinary case and must not be mistaken for one of the above.
        assertTrue(decide(state = ContextState(callState = CallState.IDLE)) is InitiativeDecision.Speak)
    }

    @Test
    fun quietHoursWrapMidnight() {
        assertTrue(InitiativePolicy.isQuietHour(23))
        assertTrue(InitiativePolicy.isQuietHour(3))
        assertTrue(InitiativePolicy.isQuietHour(7))
        assertTrue(!InitiativePolicy.isQuietHour(8))
        assertTrue(!InitiativePolicy.isQuietHour(21))
        assertTrue(heldReason(decide(hour = 2)).contains("quiet hours"))
    }

    @Test
    fun theDailyCapHolds() {
        assertTrue(heldReason(decide(spokenToday = InitiativePolicy.DAILY_CAP)).contains("cap"))
        assertTrue(decide(spokenToday = InitiativePolicy.DAILY_CAP - 1) is InitiativeDecision.Speak)
    }

    @Test
    fun theCooldownHolds() {
        assertTrue(heldReason(decide(lastSpokeAtMs = now - min(10))).contains("since she last spoke"))
        assertTrue(decide(lastSpokeAtMs = now - InitiativePolicy.COOLDOWN_MS) is InitiativeDecision.Speak)
    }

    @Test
    fun neverHavingSpokenIsNotACooldown() {
        // 0 must read as "never", not as the epoch being recently.
        assertTrue(decide(lastSpokeAtMs = 0L) is InitiativeDecision.Speak)
    }

    @Test
    fun theSameObservationDoesNotRepeatWithinTheWindow() {
        val recent = mapOf(TransitionKind.ARRIVED_HOME to (now - min(30)))
        assertTrue(heldReason(decide(byKind = recent)).contains("recently"))

        // A *different* kind in the same batch is still fair game.
        val d = decide(transitions = listOf(arrived, stillness), byKind = recent)
        assertEquals(TransitionKind.LONG_STILLNESS, (d as InitiativeDecision.Speak).transition.kind)
    }

    @Test
    fun anOldObservationOfTheSameKindIsFairGameAgain() {
        val old = mapOf(TransitionKind.ARRIVED_HOME to (now - InitiativePolicy.REPEAT_WINDOW_MS))
        assertTrue(decide(byKind = old) is InitiativeDecision.Speak)
    }

    @Test
    fun holdsAreOrderedSoTheReasonIsTheUsefulOne() {
        // Several rules fire at once here; the reported reason should be the most fundamental,
        // because it is what gets logged and read back later.
        val d = decide(enabled = false, trait = 0, transitions = emptyList(), hour = 3)
        assertTrue(heldReason(d).contains("switched off"))
    }
}
