package com.coxy.ghost.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.5's inference half. These signals are guesses about what Coxy meant, so the cases that
 * must *not* fire matter more than the ones that must — a wrong inference drifts her silently, with
 * nothing said out loud that could be argued with.
 */
class ImplicitSignalTest {

    private val longReply = List(60) { "word" }.joinToString(" ")
    private val shortReply = "Sure."

    private fun detect(prevUser: String?, prevAssistant: String?, now: String) =
        ImplicitSignalDetector.detect(prevUser, prevAssistant, now)

    @Test
    fun aShrugAfterAWallOfTextMeansFewerWords() {
        val s = detect("explain the offside rule", longReply, "ok")
        assertNotNull("expected a verbosity signal", s)
        assertEquals("verbosity", s!!.trait)
        assertEquals(-1, s.direction)
        assertTrue(s.matched, s.matched.contains("60-word"))
    }

    @Test
    fun aShrugAfterAShortReplyMeansNothing() {
        // "ok" after two words is just conversation closing normally. Reading a personality change
        // into it would drift her on the strength of ordinary politeness.
        assertNull(detect("is the kettle on", shortReply, "ok"))
    }

    @Test
    fun realEngagementIsNotADismissal() {
        for (reply in listOf(
            "ok but what about the second half",
            "okay, so how do I actually do that",
            "yeah that makes sense, go on",
        )) {
            assertNull("false dismissal on \"$reply\"", detect("q", longReply, reply))
        }
    }

    @Test
    fun askingTheSameThingAgainAsksForPlainness() {
        val s = detect(
            "how do I stage the model file on the phone",
            "You could consider a number of approaches here.",
            "how do I stage the model file on the phone again",
        )
        assertNotNull("expected a bluntness signal", s)
        assertEquals("bluntness", s!!.trait)
        assertEquals(+1, s.direction)
    }

    @Test
    fun aDifferentQuestionIsNotARepeat() {
        assertNull(
            detect(
                "how do I stage the model file",
                "Like this.",
                "what time does the shop close",
            )
        )
    }

    @Test
    fun sharedFillerAloneDoesNotCountAsARepeat() {
        // Two unrelated questions share "what", "is", "the" — stop words are dropped so they do not
        // manufacture a similarity that isn't there.
        assertNull(detect("what is the weather", "Rain.", "what is the time"))
    }

    @Test
    fun theFirstMessageOfAConversationIsSafe() {
        assertNull(detect(null, null, "hello"))
        assertNull(detect(null, null, "ok"))
    }

    @Test
    fun blankInputIsIgnored() {
        assertNull(detect("something", longReply, "   "))
    }

    @Test
    fun inferredCorrectionsMoveHerFarLessThanSpokenOnes() {
        // One shrug should be almost invisible; the difference between the two weights is the whole
        // reason an inference is allowed to touch her personality at all.
        val p = PersonalityTranslator.baseline()
        val signal = TraitSignal("verbosity", -1, "cut short")

        val explicit = TraitNudger.apply(p, signal, TraitNudger.WEIGHT)!!
        val implicit = TraitNudger.apply(p, signal, TraitNudger.IMPLICIT_WEIGHT)!!

        fun verbosity(x: PersonalityProfile) = x.traits().first { it.first == "verbosity" }.second
        val explicitMove = 50 - verbosity(explicit)
        val implicitMove = 50 - verbosity(implicit)
        assertTrue("implicit should move less: $implicitMove vs $explicitMove", implicitMove < explicitMove)
        assertTrue("implicit should still move at all", implicitMove > 0)
    }
}
