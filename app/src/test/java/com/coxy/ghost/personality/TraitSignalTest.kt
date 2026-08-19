package com.coxy.ghost.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.4's noticing half.
 *
 * The false-positive cases carry more weight than the true positives here. A missed correction gets
 * repeated by a person who can see it did not land; a phantom one drifts her somewhere neither of
 * them chose, silently, with nothing in the conversation to explain it later.
 */
class TraitSignalTest {

    private fun detect(t: String) = TraitSignalDetector.detect(t)

    @Test
    fun theCorrectionsSomeoneActuallySaysAreCaught() {
        val cases = mapOf(
            "you're being too sarcastic" to ("sarcasm" to -1),
            "less sarcasm please" to ("sarcasm" to -1),
            "drop the sarcasm" to ("sarcasm" to -1),
            "be more direct" to ("bluntness" to +1),
            "get to the point" to ("bluntness" to +1),
            "don't sugarcoat it" to ("bluntness" to +1),
            "that was too long" to ("verbosity" to -1),
            "keep it short" to ("verbosity" to -1),
            "go deeper" to ("verbosity" to +1),
            "stop asking me so many questions" to ("curiosity" to -1),
            "you're too formal" to ("formality" to -1),
            "be warmer" to ("warmth" to +1),
            "stop joking" to ("humor" to -1),
            "be funnier" to ("humor" to +1),
            "stop interrupting" to ("initiative" to -1),
        )
        for ((text, expected) in cases) {
            val s = detect(text)
            assertNotNull("no signal for \"$text\"", s)
            assertEquals("wrong trait for \"$text\"", expected.first, s!!.trait)
            assertEquals("wrong direction for \"$text\"", expected.second, s.direction)
        }
    }

    @Test
    fun ordinaryConversationIsNotFeedback() {
        for (text in listOf(
            "what's the weather like",
            "that's funny",
            "I had a long day",
            "remind me to call the bank",
            "she was being sarcastic about the whole thing",
            "how long is the drive",
            "tell me about formal logic",
            "questions like that are hard",
        )) {
            assertNull("false positive on \"$text\"", detect(text))
        }
    }

    @Test
    fun aSentenceAboutTheWorldIsNotACorrection() {
        // "too long" inside a real sentence. The length guard is what separates the two, because
        // the words alone cannot.
        val sentence = "the drive was too long and we got stuck behind a lorry for ages on the way back"
        assertTrue("guard only works if this is over the limit",
            sentence.split(" ").size > TraitSignalDetector.MAX_WORDS)
        assertNull(detect(sentence))

        // The same complaint, said the way people actually say it, is caught.
        assertNotNull(detect("too long"))
    }

    @Test
    fun emptyAndBlankAreIgnored() {
        assertNull(detect(""))
        assertNull(detect("   "))
    }

    @Test
    fun theMatchedPhraseIsReportedForTheLog() {
        val s = detect("please be more direct with me")
        assertNotNull(s)
        assertTrue(s!!.matched, s.matched.contains("more direct", ignoreCase = true))
    }
}
