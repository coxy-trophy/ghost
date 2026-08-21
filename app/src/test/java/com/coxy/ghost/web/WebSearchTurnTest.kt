package com.coxy.ghost.web

import com.coxy.ghost.action.ActionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTurnTest {

    @Test
    fun `a search request is recognised in a reply`() {
        val reply = """Let me check.
            {"action":"web_search","query":"kotlin 2.2 release date"}"""
        assertEquals("kotlin 2.2 release date", WebSearchTurn.queryIn(reply))
    }

    @Test
    fun `an ordinary reply asks for no search`() {
        assertNull(WebSearchTurn.queryIn("It's about four in the afternoon."))
        assertNull(WebSearchTurn.queryIn("""Turning it on. {"action":"toggle_torch","state":"on"}"""))
    }

    @Test
    fun `only the first search is honoured`() {
        // Each costs a round trip and a full second inference; three would eat the turn.
        val reply = """
            {"action":"web_search","query":"first"}
            {"action":"web_search","query":"second"}
        """.trimIndent()
        assertEquals("first", WebSearchTurn.queryIn(reply))
    }

    @Test
    fun `a blank query is not a search`() {
        assertNull(WebSearchTurn.queryIn("""{"action":"web_search","query":"   "}"""))
    }

    @Test
    fun `a search with no query is rejected by the catalog before it is a search`() {
        val errors = ActionCatalog.validate("web_search", mapOf())
        assertTrue("query must be required", errors.isNotEmpty())
        assertNull(WebSearchTurn.queryIn("""{"action":"web_search"}"""))
    }

    @Test
    fun `the block goes in front so the user's message stays the last line`() {
        // CloudPromptFilter's CHAT_ONLY mode reconstructs the turn as "the last non-blank line".
        val base = "[CONTEXT activity=still]\nwhat's the weather?"
        val prompt = WebSearchTurn.secondPassPrompt(base, "[WEB results for \"weather\" — 1. x]")
        assertTrue(prompt.startsWith("[WEB"))
        assertEquals("what's the weather?", prompt.lines().last())
    }

    @Test
    fun `web_search is in the catalog as a tier 1 action the model is told about`() {
        assertTrue(ActionCatalog.contains("web_search"))
        assertTrue(ActionCatalog.promptList().contains("web_search(query)"))
    }
}
