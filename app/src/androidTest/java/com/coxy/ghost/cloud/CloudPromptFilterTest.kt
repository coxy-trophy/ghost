package com.coxy.ghost.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 privacy-filter tests.
 *
 * Verifies that each [CloudPromptFilter.Mode] removes the right injected context blocks from the
 * prompt before it leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class CloudPromptFilterTest {

    private val fullPrompt = """
        [PERSONALITY default: balanced voice.]
        [CONTEXT activity=still(99%) light=dark proximity=far mic=voice(4dB) heard="turn on the torch"]
        [MEMORY | user: "my favorite food is jollof rice" (2d) | assistant: "Got it." (2d)]
        turn on the torch
    """.trimIndent()

    @Test
    fun full_mode_leaves_prompt_unchanged() {
        val result = CloudPromptFilter.apply(fullPrompt, CloudPromptFilter.Mode.FULL)
        assertEquals(fullPrompt, result)
    }

    @Test
    fun no_perception_strips_context_block() {
        val result = CloudPromptFilter.apply(fullPrompt, CloudPromptFilter.Mode.NO_PERCEPTION)
        assertFalse("CONTEXT should be removed", "[CONTEXT" in result)
        assertTrue("MEMORY should remain", "[MEMORY" in result)
        assertTrue("user message should remain", "turn on the torch" in result)
    }

    @Test
    fun no_memory_strips_memory_block() {
        val result = CloudPromptFilter.apply(fullPrompt, CloudPromptFilter.Mode.NO_MEMORY)
        assertFalse("MEMORY should be removed", "[MEMORY" in result)
        assertTrue("CONTEXT should remain", "[CONTEXT" in result)
        assertTrue("user message should remain", "turn on the torch" in result)
    }

    @Test
    fun chat_only_keeps_only_last_user_line() {
        val result = CloudPromptFilter.apply(fullPrompt, CloudPromptFilter.Mode.CHAT_ONLY)
        assertFalse("CONTEXT should be removed", "[CONTEXT" in result)
        assertFalse("MEMORY should be removed", "[MEMORY" in result)
        assertFalse("PERSONALITY should be removed", "[PERSONALITY" in result)
        assertEquals("turn on the torch", result)
    }

    @Test
    fun fromString_defaults_to_full_for_unknown_values() {
        assertEquals(CloudPromptFilter.Mode.FULL, CloudPromptFilter.fromString("unknown"))
        assertEquals(CloudPromptFilter.Mode.NO_PERCEPTION, CloudPromptFilter.fromString("no_perception"))
        assertEquals(CloudPromptFilter.Mode.CHAT_ONLY, CloudPromptFilter.fromString("CHAT_ONLY"))
    }
}
