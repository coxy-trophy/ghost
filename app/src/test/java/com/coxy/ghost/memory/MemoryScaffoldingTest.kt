package com.coxy.ghost.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage boundary that keeps GHOST's own prompt out of her transcript.
 *
 * Retrieved-memory and sensor blocks are prepended to the prompt, so a small model can hand them
 * straight back. Stored, they become turns, and the next `[MEMORY]` block quotes them as examples
 * of what she says.
 */
class MemoryScaffoldingTest {

    @Test
    fun `a parroted memory block is not a turn`() {
        assertTrue(
            MemoryStore.looksLikePromptScaffolding(
                """[MEMORY user: "Pair with the IT office TV" (2d) | you: "" (2d)]"""
            )
        )
    }

    @Test
    fun `a parroted context block is not a turn`() {
        assertTrue(MemoryStore.looksLikePromptScaffolding("[CONTEXT activity=still light=dark]"))
    }

    @Test
    fun `leading whitespace does not hide it`() {
        assertTrue(MemoryStore.looksLikePromptScaffolding("\n  [MEMORY user: \"x\"]"))
    }

    @Test
    fun `the storage guard rejects it too`() {
        assertTrue(MemoryStore.looksLikeExtractionOutput("[MEMORY user: \"x\"]"))
    }

    /** A reply that talks about the block is an answer, not the block. */
    @Test
    fun `mentioning a block is still a real reply`() {
        assertFalse(
            MemoryStore.looksLikePromptScaffolding(
                "I keep what I remember in a [MEMORY ...] block before each of your messages."
            )
        )
        assertFalse(MemoryStore.looksLikePromptScaffolding("Two tips for sleeping better:"))
    }
}
