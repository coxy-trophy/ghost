package com.coxy.ghost.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher behind [MemoryStore.purgeSeparationClaims]. It drives a delete, so the false-positive
 * cases matter more than the true positives — the cost of over-matching is a real memory destroyed.
 *
 * The positive strings are taken verbatim from the device, the same way `ExtractionArtifactTest`
 * takes its from the v0.11.4 incident.
 */
class SeparationClaimTest {

    @Test
    fun theThingsSheActuallySaidAreMatched() {
        for (line in listOf(
            "We are at home, and it is dark. I am far away from you.",
            "I am far away from you because I am at home.",
            "If you are at home, then you are far away from me.",
            "We are at home, and I am far away from you.",
        )) {
            assertTrue(line, MemoryStore.claimsSeparation(line))
        }
    }

    @Test
    fun bothDirectionsAreMatched() {
        assertTrue(MemoryStore.claimsSeparation("I am far from you right now"))
        assertTrue(MemoryStore.claimsSeparation("you are far from me"))
        assertTrue(MemoryStore.claimsSeparation("I'm away from you at the moment"))
    }

    @Test
    fun idiomsThatMeanTheOppositeAreNotMatched() {
        // These read as separation and are not. "Apart from you" means *besides* you, and "not with
        // you" is how anyone says they did not follow the point. Neither was ever observed among
        // the rows this cleans up, and this matcher deletes — so they are deliberately excluded.
        assertFalse(MemoryStore.claimsSeparation("Apart from you, nobody knows about it."))
        assertFalse(MemoryStore.claimsSeparation("I'm not with you on that one."))
        assertFalse(MemoryStore.claimsSeparation("Apart from me, who else was there?"))
    }

    @Test
    fun ordinaryTurnsSurvive() {
        // Everything here is a memory worth keeping, and none of it claims separation.
        for (line in listOf(
            "We are at home, and it is dark.",
            "You are standing still.",
            "I am at home, and I am not doing anything specific.",
            "You've been sitting for forty minutes.",
            "The shop is far away from the station.",
            "I don't remember that.",
            "Your keys are not with your bag.",
        )) {
            assertFalse(line, MemoryStore.claimsSeparation(line))
        }
    }

    @Test
    fun matchingIsCaseInsensitiveAndSpacingTolerant() {
        assertTrue(MemoryStore.claimsSeparation("I AM FAR AWAY FROM YOU"))
        assertTrue(MemoryStore.claimsSeparation("far   away   from  me"))
    }

    @Test
    fun aBareLocationStatementIsNotASeparationClaim() {
        // "I am at home" is true and useful — the phone is at home. Only the claim of being
        // somewhere he is not should ever be deleted.
        assertFalse(MemoryStore.claimsSeparation("I am at home."))
        assertFalse(MemoryStore.claimsSeparation("We are at home and the light is dark."))
    }
}
