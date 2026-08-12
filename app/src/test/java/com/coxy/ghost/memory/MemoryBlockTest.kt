package com.coxy.ghost.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `toMemoryBlock` renders what GHOST claims to remember, and it had no test at any tier. Its caps
 * are the only thing keeping recalled memory inside the 8192-token context budget — there is no
 * token counter, so the limits are textual and worth pinning.
 */
class MemoryBlockTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun entry(role: String, content: String, ageMs: Long = 0L) = MemoryEntry(
        role = role,
        content = content,
        tsMs = System.currentTimeMillis() - ageMs,
        embedding = ByteArray(0)
    )

    @Test
    fun emptyList_producesNoBlockAtAll() {
        // Must be "" not "[MEMORY ]" — the no-memory case has to leave the prompt untouched.
        assertEquals("", emptyList<MemoryEntry>().toMemoryBlock())
    }

    @Test
    fun rolesAreRenderedFromGhostsPointOfView() {
        val block = listOf(
            entry("user", "where are my keys"),
            entry("assistant", "on the counter")
        ).toMemoryBlock()

        assertTrue(block.startsWith("[MEMORY "))
        assertTrue(block.endsWith("]"))
        assertTrue("the user is 'user'", block.contains("user: \"where are my keys\""))
        assertTrue("the assistant is 'you', not 'assistant'", block.contains("you: \"on the counter\""))
        assertTrue("entries are pipe-separated", block.contains(" | "))
    }

    @Test
    fun atMostFiveEntriesSurvive() {
        val block = (1..12).map { entry("user", "memory number $it") }.toMemoryBlock()
        assertEquals("exactly 5 entries", 5, block.split(" | ").size)
        assertTrue(block.contains("memory number 5"))
        assertFalse("the sixth is dropped", block.contains("memory number 6"))
    }

    @Test
    fun longEntriesAreTruncatedToThirtyWords() {
        val long = (1..60).joinToString(" ") { "w$it" }
        val block = listOf(entry("user", long)).toMemoryBlock()
        assertTrue("30th word kept", block.contains("w30"))
        assertFalse("31st word dropped", block.contains("w31"))
        assertTrue("truncation is marked", block.contains("…"))
    }

    @Test
    fun shortEntriesAreNotMarkedAsTruncated() {
        val block = listOf(entry("user", "short and sweet")).toMemoryBlock()
        assertFalse(block.contains("…"))
    }

    @Test
    fun ageLabelsUseTheCoarsestUsefulUnit() {
        assertTrue(listOf(entry("user", "a", 0L)).toMemoryBlock().contains("(now)"))
        assertTrue(listOf(entry("user", "a", 5 * minute)).toMemoryBlock().contains("(5m)"))
        assertTrue(listOf(entry("user", "a", 3 * hour)).toMemoryBlock().contains("(3h)"))
        assertTrue(listOf(entry("user", "a", 2 * day)).toMemoryBlock().contains("(2d)"))
    }
}
