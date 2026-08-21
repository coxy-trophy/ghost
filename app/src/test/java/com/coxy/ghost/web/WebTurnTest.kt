package com.coxy.ghost.web

import com.coxy.ghost.action.ActionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTurnTest {

    private fun search(reply: String) = WebTurn.requestIn(reply) as? WebTurn.Request.Search
    private fun fetch(reply: String) = WebTurn.requestIn(reply) as? WebTurn.Request.Fetch

    // --- what counts as a web request ------------------------------------------------------------

    @Test
    fun `a search request is recognised in a reply`() {
        val reply = """Let me check.
            {"action":"web_search","query":"kotlin 2.2 release date"}"""
        assertEquals("kotlin 2.2 release date", search(reply)?.query)
    }

    @Test
    fun `a fetch request is recognised in a reply`() {
        val reply = """Reading that.
            {"action":"web_fetch","url":"https://kotlinlang.org/docs/whatsnew.html"}"""
        assertEquals("https://kotlinlang.org/docs/whatsnew.html", fetch(reply)?.url)
    }

    @Test
    fun `an ordinary reply asks for nothing`() {
        assertNull(WebTurn.requestIn("It's about four in the afternoon."))
        assertNull(WebTurn.requestIn("""Turning it on. {"action":"toggle_torch","state":"on"}"""))
    }

    @Test
    fun `only the first request is honoured`() {
        // Each costs a round trip and a full generation; three would eat the turn.
        val reply = """
            {"action":"web_search","query":"first"}
            {"action":"web_search","query":"second"}
        """.trimIndent()
        assertEquals("first", search(reply)?.query)
    }

    @Test
    fun `a blank query or url is not a request`() {
        assertNull(WebTurn.requestIn("""{"action":"web_search","query":"   "}"""))
        assertNull(WebTurn.requestIn("""{"action":"web_fetch","url":"  "}"""))
    }

    @Test
    fun `both web actions require their parameter`() {
        assertTrue(ActionCatalog.validate("web_search", mapOf()).isNotEmpty())
        assertTrue(ActionCatalog.validate("web_fetch", mapOf()).isNotEmpty())
        assertNull(WebTurn.requestIn("""{"action":"web_fetch"}"""))
    }

    // --- the loop's shape -------------------------------------------------------------------------

    @Test
    fun `two steps is the cap, which is exactly search then read`() {
        assertEquals(2, WebTurn.MAX_STEPS)
    }

    @Test
    fun `blocks stack in front so the user's message stays the last line`() {
        // CloudPromptFilter's CHAT_ONLY mode reconstructs the turn as "the last non-blank line".
        val base = "[CONTEXT activity=still]\nwhat does that page say?"
        val afterSearch = WebTurn.withBlock(base, "[WEB results for \"x\" — 1. y]")
        val afterFetch = WebTurn.withBlock(afterSearch, "[WEB page \"y\" (example.com) — text]")
        assertEquals("what does that page say?", afterFetch.lines().last())
        assertTrue("newest step first", afterFetch.startsWith("[WEB page"))
        assertTrue(afterFetch.contains("[WEB results"))
    }

    @Test
    fun `both web actions are in the catalog and offered to the model`() {
        assertTrue(ActionCatalog.contains("web_search"))
        assertTrue(ActionCatalog.contains("web_fetch"))
        val prompt = ActionCatalog.promptList()
        assertTrue(prompt.contains("web_search(query)"))
        assertTrue(prompt.contains("web_fetch(url)"))
    }

    // --- the fetch boundary -----------------------------------------------------------------------

    @Test
    fun `only http and https addresses are fetchable`() {
        assertTrue(WebClient.isFetchable("https://example.com/a"))
        assertTrue(WebClient.isFetchable("http://example.com"))
        assertTrue(WebClient.isFetchable("HTTPS://Example.com/Path"))
    }

    @Test
    fun `schemes that have no business being fetched are rejected`() {
        // The model produces this string, sometimes off a page it just read.
        for (bad in listOf(
            "file:///data/data/com.coxy.ghost/shared_prefs/ghost_cloud_key.xml",
            "javascript:alert(1)",
            "data:text/html,<script>x</script>",
            "content://com.android.contacts/data",
            "ftp://example.com/x",
            "//example.com",
            "example.com",
            ""
        )) {
            assertTrue("'$bad' must not be fetchable", !WebClient.isFetchable(bad))
        }
    }

    @Test
    fun `an address with no real host is rejected`() {
        assertTrue(!WebClient.isFetchable("https://"))
        assertTrue(!WebClient.isFetchable("https://localhost"))
        assertTrue(!WebClient.isFetchable("https://not a host/x"))
    }
}
