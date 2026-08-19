package com.coxy.ghost.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.4's applying half: a correction moves her, and keeps moving her less each time.
 */
class TraitNudgerTest {

    private fun profile(sarcasm: Int = 50, min: Int = 0, max: Int = 100) =
        PersonalityTranslator.baseline().copy(
            sarcasm = sarcasm, sarcasmMin = min, sarcasmMax = max,
        )

    private fun sarcasmOf(p: PersonalityProfile) = p.traits().first { it.first == "sarcasm" }.second

    private val lessSarcastic = TraitSignal("sarcasm", -1, "less sarcastic")
    private val moreSarcastic = TraitSignal("sarcasm", +1, "more sarcastic")

    @Test
    fun oneCorrectionClosesAQuarterOfTheDistance() {
        // 50 with a floor of 0: a quarter of the way down is 12.5, rounded.
        val moved = TraitNudger.apply(profile(sarcasm = 50), lessSarcastic)
        assertNotNull(moved)
        assertEquals(38, sarcasmOf(moved!!))
    }

    @Test
    fun sayingItAgainMovesHerLessEachTime() {
        // The point of a weighted move: the first correction lands hard, the fifth barely registers,
        // because there is hardly any distance left to close. Saying a thing once should change her;
        // saying it five times should not erase her.
        var p = profile(sarcasm = 80)
        val steps = mutableListOf<Int>()
        repeat(5) {
            val before = sarcasmOf(p)
            p = TraitNudger.apply(p, lessSarcastic) ?: p
            steps += before - sarcasmOf(p)
        }
        assertTrue("steps should shrink, got $steps", steps.first() > steps.last())
        assertTrue("should still be inside the budget", sarcasmOf(p) > 0)
    }

    @Test
    fun theDriftBudgetIsTheFloor() {
        // A trait pinned at the bottom of its budget cannot be pushed below it, however often the
        // correction is repeated. That is what the budget is for.
        var p = profile(sarcasm = 30, min = 30, max = 90)
        repeat(10) { p = TraitNudger.apply(p, lessSarcastic) ?: p }
        assertEquals(30, sarcasmOf(p))
    }

    @Test
    fun alreadyAtTheEdgeReportsNoChange() {
        val p = profile(sarcasm = 30, min = 30, max = 90)
        assertNull("no room to move should report null", TraitNudger.apply(p, lessSarcastic))
    }

    @Test
    fun itMovesBothWays() {
        val up = TraitNudger.apply(profile(sarcasm = 50), moreSarcastic)!!
        assertTrue("should have risen", sarcasmOf(up) > 50)
        val down = TraitNudger.apply(profile(sarcasm = 50), lessSarcastic)!!
        assertTrue("should have fallen", sarcasmOf(down) < 50)
    }

    @Test
    fun aCorrectionAlwaysMovesAtLeastAPoint() {
        // Distance of 3 would round to 0.75 -> 1, not to nothing. A correction that is understood
        // and produces no visible change reads as being ignored.
        val moved = TraitNudger.apply(profile(sarcasm = 33, min = 30, max = 90), lessSarcastic)
        assertNotNull(moved)
        assertTrue("should have moved at least one point", sarcasmOf(moved!!) < 33)
    }

    @Test
    fun theAcknowledgementNamesTheDirectionInPlainWords() {
        assertTrue(TraitNudger.acknowledgement(lessSarcastic, 50, 38).contains("less sarcastic"))
        assertTrue(TraitNudger.acknowledgement(moreSarcastic, 50, 62).contains("more sarcastic"))
        assertTrue(TraitNudger.acknowledgement(TraitSignal("bluntness", 1, "be more direct"), 40, 55)
            .contains("more direct"))
    }
}
