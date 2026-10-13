package com.coxy.ghost.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudPromptFilterTest {
    private val metadata = "[PERSONALITY calm]\n[CONTEXT home]\n[MEMORY secret]\n[LAN phone]\n"

    @Test
    fun `chat only preserves the entire multiline message`() {
        val message = "Compare these:\n\nfirst option\nsecond option\n"
        assertEquals(message, CloudPromptFilter.apply(metadata + message,
            CloudPromptFilter.Mode.CHAT_ONLY, message))
    }

    @Test
    fun `user block labels are never treated as injected metadata`() {
        val message = "[MEMORY example]\nExplain [CONTEXT example]\n[WEB example]"
        for (mode in CloudPromptFilter.Mode.entries) {
            assertEquals(message, CloudPromptFilter.apply(message, mode, message))
        }
    }

    @Test
    fun `chat only retains chained public lookups and removes private blocks`() {
        val web = "[WEB page contents]\n[WEB search results]\n"
        val message = "Compare these\nwith yesterday"
        assertEquals(web + message, CloudPromptFilter.apply(web + metadata + message,
            CloudPromptFilter.Mode.CHAT_ONLY, message))
    }

    @Test
    fun `selective modes remove only their injected block`() {
        val message = "first\nsecond"
        assertEquals("[MEMORY secret]\n" + message, CloudPromptFilter.apply(
            "[CONTEXT home]\n[MEMORY secret]\n" + message,
            CloudPromptFilter.Mode.NO_PERCEPTION, message))
        assertEquals("[CONTEXT home]\n" + message, CloudPromptFilter.apply(
            "[CONTEXT home]\n[MEMORY secret]\n" + message,
            CloudPromptFilter.Mode.NO_MEMORY, message))
    }

    @Test
    fun `legacy prompts preserve multiline text after leading blocks`() {
        val message = "first\n\nsecond"
        assertEquals(message, CloudPromptFilter.apply(metadata + message,
            CloudPromptFilter.Mode.CHAT_ONLY))
    }

    @Test
    fun `full mode is unchanged`() {
        assertEquals(metadata, CloudPromptFilter.apply(metadata, CloudPromptFilter.Mode.FULL))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched explicit message fails before private context can be sent`() {
        CloudPromptFilter.apply(metadata + "actual", CloudPromptFilter.Mode.CHAT_ONLY, "different")
    }
}
