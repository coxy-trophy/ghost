package com.coxy.ghost.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * The markdown a model writes, rendered rather than shown.
 *
 * Models format whether or not anything asked them to, and the screen was printing the markers
 * literally: `**Margaret Hager**` with the stars visible, `1.` and `-` sitting at the left margin as
 * ordinary text, fenced code arriving as three backticks and a language name. Deleting the markers
 * would work and would also throw away the structure, which is real — she bolds the name because the
 * name is the answer, and she numbers the steps because they are in order.
 *
 * Two passes, in this order, and the order is the whole design:
 *
 *  1. **Blocks**, line by line. Headings, bullets, numbered items, quotes and fenced code are all
 *     decided by what a line starts with, so they can only be found before the line is disturbed.
 *  2. **Inline**, within each line's remaining text. One forward scan over a single alternation, so
 *     the marks it records are indices into the string being returned.
 *
 * The second pass is skipped inside fenced code. Backticks and asterisks in a code block are code,
 * and a renderer that emphasises them is corrupting the one kind of text where the characters are
 * the content.
 *
 * Still deliberately not a markdown renderer. There are no tables, no links, no images and no
 * nesting: none of them appear in something written to be read aloud, and each would be a large
 * amount of code standing between GHOST and her own words.
 *
 * [parse] is pure and [render] is the thin Android half. Split because the whole of the interesting
 * behaviour is index arithmetic, and the span classes are stubbed to return null in JVM tests — so
 * with the two fused, none of this could be tested anywhere but on a device.
 */
object Markup {

    enum class Style {
        /** Inline emphasis. */
        BOLD, ITALIC, CODE,

        /** A heading. [Mark.level] is 1..6. */
        HEADING,

        /** A list item. [Mark.level] is the nesting depth, currently always 0. */
        BULLET,

        /** A numbered item. The number is left in the text; this only carries the hanging indent. */
        ORDERED,

        /** A quoted line. */
        QUOTE,

        /** A line inside a fenced block. */
        PRE,
    }

    /** Where a style applies in [Parsed.text], after the markers have been taken out. */
    data class Mark(val style: Style, val start: Int, val end: Int, val level: Int = 0)

    data class Parsed(val text: String, val marks: List<Mark>)

    /**
     * All three inline rules in one alternation, tried in this order.
     *
     * One scan, not one pass per style. Three passes over a mutating string cannot work: removing a
     * pair of asterisks shifts every mark already recorded to its right, so marks from the first
     * pass point past the end of the string the third pass produced. The test that found it asked
     * only that every mark stay inside its own text.
     *
     * Bold leads because `**` has to be consumed before `*` gets a chance at it.
     */
    private val INLINE = Regex(
        "\\*\\*(?<b>.+?)\\*\\*" +
            "|`(?<c>[^`]+)`" +
            "|(?<![*\\w])[*_](?!\\s)(?<i>.+?)(?<!\\s)[*_](?![*\\w])",
        RegexOption.DOT_MATCHES_ALL
    )

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\s{0,3}(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val FENCE = Regex("^\\s*```.*$")
    private val RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")

    /** The bullet a `-` becomes. Written into the text so selecting a line copies something sane. */
    private const val DOT = "•  "

    /**
     * Strip the markers and record where the structure was.
     *
     * Builds the result forwards rather than editing in place, so every index recorded is an index
     * into the string being returned.
     */
    fun parse(raw: String): Parsed {
        val out = StringBuilder()
        val marks = mutableListOf<Mark>()
        var fenced = false

        for ((i, line) in raw.split("\n").withIndex()) {
            if (i > 0) out.append('\n')

            if (FENCE.matches(line)) {
                // The fence itself is never shown. Its only job is to switch the mode.
                fenced = !fenced
                continue
            }
            if (fenced) {
                val start = out.length
                out.append(line)
                marks += Mark(Style.PRE, start, out.length)
                continue
            }

            // A rule is the one block that is not text. Rendered as a blank line rather than as a
            // row of dashes, because a horizontal line drawn in hyphens is the marker showing.
            if (RULE.matches(line)) continue

            HEADING.matchEntire(line)?.let { m ->
                appendInline(out, marks, m.groupValues[2]) { s, e ->
                    marks += Mark(Style.HEADING, s, e, m.groupValues[1].length)
                }
                return@let
            } ?: ORDERED.matchEntire(line)?.let { m ->
                val start = out.length
                out.append(m.groupValues[1]).append(". ")
                appendInline(out, marks, m.groupValues[2]) { _, e ->
                    marks += Mark(Style.ORDERED, start, e)
                }
                return@let
            } ?: BULLET.matchEntire(line)?.let { m ->
                val start = out.length
                out.append(DOT)
                appendInline(out, marks, m.groupValues[1]) { _, e ->
                    marks += Mark(Style.BULLET, start, e)
                }
                return@let
            } ?: QUOTE.matchEntire(line)?.let { m ->
                appendInline(out, marks, m.groupValues[1]) { s, e ->
                    marks += Mark(Style.QUOTE, s, e)
                }
                return@let
            } ?: appendInline(out, marks, line) { _, _ -> }
        }
        return Parsed(out.toString(), marks)
    }

    /**
     * Append [content] with its inline markers removed, recording emphasis as it goes.
     *
     * [whole] is handed the span of everything appended, so a caller can mark the finished line as a
     * heading or a list item without having to work out where it landed.
     */
    private inline fun appendInline(
        out: StringBuilder,
        marks: MutableList<Mark>,
        content: String,
        whole: (Int, Int) -> Unit
    ) {
        val lineStart = out.length
        var last = 0
        for (m in INLINE.findAll(content)) {
            val groups = m.groups as? MatchNamedGroupCollection ?: continue
            val (style, inner) = when {
                groups["b"] != null -> Style.BOLD to groups["b"]!!.value
                groups["c"] != null -> Style.CODE to groups["c"]!!.value
                groups["i"] != null -> Style.ITALIC to groups["i"]!!.value
                else -> continue
            }
            out.append(content, last, m.range.first)
            val start = out.length
            out.append(inner)
            marks += Mark(style, start, out.length)
            last = m.range.last + 1
        }
        out.append(content, last, content.length)
        whole(lineStart, out.length)
    }

    /**
     * [parse], as something a TextView can show.
     *
     * @param accent the colour quoted lines are dimmed to, so the caller's palette decides rather
     *   than this file hardcoding one that would be wrong in the other mode.
     */
    fun render(raw: String, quoteColor: Int): CharSequence {
        val parsed = parse(raw)
        val out = SpannableStringBuilder(parsed.text)
        for (mark in parsed.marks) {
            if (mark.start < 0 || mark.end > out.length || mark.start >= mark.end) continue
            val spans: List<Any> = when (mark.style) {
                Style.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                Style.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                Style.CODE -> listOf(TypefaceSpan("monospace"), RelativeSizeSpan(0.92f))
                // Two sizes, not six. A reply is not a document, and an h4 that renders a hair
                // smaller than an h3 is a distinction nobody reading a chat message will make.
                Style.HEADING -> listOf(
                    StyleSpan(Typeface.BOLD),
                    RelativeSizeSpan(if (mark.level <= 2) 1.22f else 1.08f)
                )
                // The hanging indent is what makes a wrapped list item line up under its own text
                // rather than under its bullet.
                Style.BULLET -> listOf(LeadingMarginSpan.Standard(0, HANGING))
                Style.ORDERED -> listOf(LeadingMarginSpan.Standard(0, HANGING))
                Style.QUOTE -> listOf(
                    LeadingMarginSpan.Standard(HANGING),
                    android.text.style.ForegroundColorSpan(quoteColor),
                    StyleSpan(Typeface.ITALIC)
                )
                Style.PRE -> listOf(TypefaceSpan("monospace"), RelativeSizeSpan(0.9f))
            }
            for (span in spans) {
                out.setSpan(span, mark.start, mark.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /** Hanging indent for list items and quotes, in px. */
    private const val HANGING = 34

    /** Kept for callers that only need emphasis and have no colour to offer. */
    fun inline(raw: String): CharSequence = render(raw, 0x80808080.toInt())
}
