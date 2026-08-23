package com.coxy.ghost.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Markup.parse] only.
 *
 * The Android half cannot be tested here — every span class is stubbed to return null in JVM tests,
 * which is the reason the parsing was split out of the rendering in the first place. What is left is
 * the part that actually broke: index arithmetic over a string being rebuilt as it is scanned.
 */
class MarkupTest {

    /** Every mark must point inside the text it was recorded against. */
    private fun assertMarksInBounds(raw: String) {
        val parsed = Markup.parse(raw)
        for (m in parsed.marks) {
            assertTrue(
                "mark $m out of bounds for ${parsed.text.length} chars: ${parsed.text}",
                m.start in 0..parsed.text.length &&
                    m.end in 0..parsed.text.length &&
                    m.start <= m.end
            )
        }
    }

    @Test
    fun `markers are removed from the text`() {
        val parsed = Markup.parse("There's **Margaret Hager**, a *chiropractor*.")
        assertEquals("There's Margaret Hager, a chiropractor.", parsed.text)
    }

    @Test
    fun `bold wins over italic on a double asterisk`() {
        val marks = Markup.parse("**bold**").marks
        assertEquals(listOf(Markup.Style.BOLD), marks.map { it.style })
    }

    @Test
    fun `headings lose their hashes and keep their level`() {
        val parsed = Markup.parse("## Second law")
        assertEquals("Second law", parsed.text)
        val h = parsed.marks.single { it.style == Markup.Style.HEADING }
        assertEquals(2, h.level)
        assertEquals(0, h.start)
        assertEquals(parsed.text.length, h.end)
    }

    @Test
    fun `bullets become a dot and are marked`() {
        val parsed = Markup.parse("- Harder push\n- Heavier object")
        assertEquals("•  Harder push\n•  Heavier object", parsed.text)
        assertEquals(2, parsed.marks.count { it.style == Markup.Style.BULLET })
    }

    @Test
    fun `numbered items keep their number`() {
        val parsed = Markup.parse("1. Harder push\n2. Heavier object")
        assertEquals("1. Harder push\n2. Heavier object", parsed.text)
        assertEquals(2, parsed.marks.count { it.style == Markup.Style.ORDERED })
    }

    @Test
    fun `a numbered item still takes inline emphasis`() {
        val parsed = Markup.parse("1. Harder push = **faster** speed-up.")
        assertEquals("1. Harder push = faster speed-up.", parsed.text)
        assertTrue(parsed.marks.any { it.style == Markup.Style.BOLD })
        assertMarksInBounds("1. Harder push = **faster** speed-up.")
    }

    @Test
    fun `fenced code keeps its own characters`() {
        val raw = "Try:\n```kotlin\nval x = a ** b\n```\ndone"
        val parsed = Markup.parse(raw)
        // The fences are gone, the language tag with them, and nothing inside was emphasised.
        assertFalse(parsed.text.contains("```"))
        assertFalse(parsed.text.contains("kotlin"))
        assertTrue(parsed.text.contains("val x = a ** b"))
        assertTrue(parsed.marks.any { it.style == Markup.Style.PRE })
        assertFalse(parsed.marks.any { it.style == Markup.Style.BOLD })
    }

    @Test
    fun `quotes lose their marker`() {
        val parsed = Markup.parse("> she said this")
        assertEquals("she said this", parsed.text)
        assertTrue(parsed.marks.any { it.style == Markup.Style.QUOTE })
    }

    @Test
    fun `a horizontal rule leaves no dashes behind`() {
        val parsed = Markup.parse("above\n---\nbelow")
        assertFalse(parsed.text.contains("---"))
        assertTrue(parsed.text.contains("above"))
        assertTrue(parsed.text.contains("below"))
    }

    /**
     * The regression this file exists for.
     *
     * Rendering used to run one pass per style over a string it was editing, so removing a pair of
     * asterisks shifted every mark already recorded to its right and the last pass indexed past the
     * end of the string the first had produced — `StringIndexOutOfBounds: begin 24, end 29, length
     * 21`, live, in a reply.
     */
    @Test
    fun `every mark stays inside its own text`() {
        listOf(
            "**a** and *b* and `c`",
            "## A heading with **bold** in it",
            "- a *bullet* with `code`\n- and **another**",
            "1. one **two** three\n2. `four`",
            "> a **quoted** line",
            "```\nraw ** stuff\n```",
            "mixed **bold\nacross a line break** end",
            "***", "**", "*", "`", "#", "- ", "1. ",
            ""
        ).forEach(::assertMarksInBounds)
    }
}
