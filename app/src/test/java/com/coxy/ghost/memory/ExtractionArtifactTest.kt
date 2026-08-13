package com.coxy.ghost.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the filter that keeps fact-extraction output out of the conversation.
 *
 * This is the defect behind "the AI keeps calling me ghost" (2026-08-13). `FactExtractor` shares the
 * engine's single conversation context, so its `[FACT_EXTRACTION]` meta-turns became part of the
 * model's history and it began answering ordinary messages with `FACT: GHOST did X`. Those replies
 * were stored as GHOST's conversational turns and recalled into `[MEMORY]` as `you: FACT: GHOST
 * can't hear the user clearly` — dozens of examples of GHOST naming itself in the third person,
 * until the role structure collapsed and "Ghost" landed on Coxy.
 *
 * The strings below are taken verbatim from the live memory DB on RMX3760.
 */
class ExtractionArtifactTest {

    @Test
    fun `real extraction output from the device is caught`() {
        val fromDevice = listOf(
            "FACT: GHOST can't hear the user clearly.",
            "FACT: GHOST deleted a large man then left.",
            "FACT: GHOST skipped the user's request.",
            "FACT: Coxy greets GHOST with a \"Hi\".",
            "FACT: GHOST was reminded to time the next time.",
            "FACT: Coxy heard someone say \"Oh my God she killed innocent people\".",
        )
        for (s in fromDevice) {
            assertTrue("should be filtered: $s", MemoryStore.looksLikeExtractionOutput(s))
        }
    }

    @Test
    fun `case and leading whitespace do not evade the filter`() {
        assertTrue(MemoryStore.looksLikeExtractionOutput("fact: something"))
        assertTrue(MemoryStore.looksLikeExtractionOutput("  FACT: indented"))
        assertTrue(MemoryStore.looksLikeExtractionOutput("[FACT_EXTRACTION]\nExtract durable facts"))
    }

    @Test
    fun `the bare NONE sentinel is filtered`() {
        // FactExtractor asks the model to answer "NONE" when an exchange carries no durable fact.
        assertTrue(MemoryStore.looksLikeExtractionOutput("NONE"))
        assertTrue(MemoryStore.looksLikeExtractionOutput("none"))
    }

    @Test
    fun `ordinary replies are not filtered`() {
        val realReplies = listOf(
            "I don't know.",
            "I'm not sure what you're asking.",
            "You are standing still.",
            "Paris is the capital of France.",
            "I'm working on something.",
            // Must not trip on a normal sentence that merely begins with the word.
            "None of that sounds like a good idea to me, honestly.",
            "Facts are facts, but that one's wrong.",
            "In fact, you told me the opposite yesterday.",
        )
        for (s in realReplies) {
            assertFalse("should NOT be filtered: $s", MemoryStore.looksLikeExtractionOutput(s))
        }
    }

    @Test
    fun `stripGhostPrefix still handles the legacy prefix`() {
        assertTrue(MemoryStore.stripGhostPrefix("Ghost: hello") == "hello")
        assertTrue(MemoryStore.stripGhostPrefix("ghost:hello") == "hello")
        assertTrue(MemoryStore.stripGhostPrefix("hello") == "hello")
    }
}
