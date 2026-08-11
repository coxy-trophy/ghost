package com.coxy.ghost.personality

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6.3 instrumented gate: verify trait-to-prompt translation, drift-budget clamping,
 * nudge math, JSON round-trip, and hard-floor inclusion.
 */
@RunWith(AndroidJUnit4::class)
class PersonalityTranslatorTest {

    @Test
    fun baseline_isBalancedAndHasIdOne() {
        val b = PersonalityTranslator.baseline()
        assertEquals(1L, b.id)
        assertEquals(50, b.humor)
        assertEquals(50, b.sarcasm)
        assertEquals(50, b.initiative)
        assertEquals(0, b.humorMin)
        assertEquals(100, b.humorMax)
    }

    @Test
    fun profileToPromptBlock_reflectsHighAndLowTraits() {
        val p = PersonalityProfile(
            humor = 80, sarcasm = 10, bluntness = 80, warmth = 20,
            formality = 80, verbosity = 20, curiosity = 80, initiative = 20
        )
        val block = PersonalityTranslator.profileToPromptBlock(p)
        assertTrue("expected high-humor instruction", block.contains("joke often"))
        assertTrue("expected low-sarcasm instruction", block.contains("earnest"))
        assertTrue("expected high-bluntness instruction", block.contains("direct and plain-spoken"))
        assertTrue("expected low-warmth instruction", block.contains("neutral and matter-of-fact"))
        assertTrue("expected high-formality instruction", block.contains("formal language"))
        assertTrue("expected low-verbosity instruction", block.contains("short and clipped"))
        assertTrue("expected high-curiosity instruction", block.contains("ask follow-up"))
        assertTrue("expected low-initiative instruction", block.contains("wait to be asked"))
    }

    @Test
    fun clamp_respectsDriftBudget() {
        val p = PersonalityProfile(
            humor = 95, humorMin = 0, humorMax = 60,
            sarcasm = 5, sarcasmMin = 40, sarcasmMax = 100
        )
        val c = PersonalityTranslator.clamp(p)
        assertEquals(60, c.humor)
        assertEquals(40, c.sarcasm)
    }

    @Test
    fun nudge_movesWithinBudget() {
        val p = PersonalityProfile(bluntness = 30, bluntnessMin = 20, bluntnessMax = 50)
        val up = PersonalityTranslator.nudge(p, "bluntness", 30)
        assertEquals(50, up.bluntness)
        val down = PersonalityTranslator.nudge(up, "bluntness", -100)
        assertEquals(20, down.bluntness)
    }

    @Test
    fun jsonRoundTripPreservesValues() {
        val p = PersonalityProfile(
            id = 1L, name = "test",
            humor = 12, sarcasm = 88, bluntness = 34, warmth = 67,
            formality = 45, verbosity = 23, curiosity = 91, initiative = 7,
            humorMin = 10, humorMax = 20,
            initiativeMin = 0, initiativeMax = 10,
            updatedMs = 1234L
        )
        val json = PersonalityTranslator.toJson(p)
        val restored = PersonalityTranslator.fromJson(json)
        assertEquals(p.humor, restored.humor)
        assertEquals(p.sarcasm, restored.sarcasm)
        assertEquals(p.bluntness, restored.bluntness)
        assertEquals(p.warmth, restored.warmth)
        assertEquals(p.formality, restored.formality)
        assertEquals(p.verbosity, restored.verbosity)
        assertEquals(p.curiosity, restored.curiosity)
        assertEquals(p.initiative, restored.initiative)
        assertEquals(p.humorMin, restored.humorMin)
        assertEquals(p.humorMax, restored.humorMax)
        assertEquals(p.initiativeMin, restored.initiativeMin)
        assertEquals(p.initiativeMax, restored.initiativeMax)
        assertEquals(p.updatedMs, restored.updatedMs)
    }

    @Test
    fun hardFloors_mentionsTruthAndUncertainty() {
        val floors = PersonalityTranslator.hardFloors()
        assertTrue(floors.contains("truth"))
        assertTrue(floors.contains("uncertainty"))
        assertTrue(floors.contains("fabricate"))
    }
}
