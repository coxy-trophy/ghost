package com.coxy.ghost.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * The light markdown a model writes, rendered rather than shown.
 *
 * Models emphasise with asterisks whether or not anything asked them to, and both screens were
 * printing them literally — a reply reading `There's **Margaret Hager**, a chiropractor` with the
 * stars visible. Deleting them would work and would also throw away the emphasis, which is real:
 * she bolds the name because the name is the answer.
 *
 * Deliberately four rules and no more. This is not a markdown renderer and should not become one —
 * headings, lists and links do not appear in something written to be said out loud, and a parser
 * that handled them would be a large amount of code standing between GHOST and her own words.
 *
 * [parse] is pure and [inline] is the thin Android half. Split because the whole of the interesting
 * behaviour is index arithmetic, and `SpannableStringBuilder` is stubbed to return null in JVM
 * tests — so with the two fused, none of this could be tested anywhere but on a device.
 */
object Markup {

    enum class Style { BOLD, ITALIC, CODE }

    /** Where a style applies in [Parsed.text], after the markers have been taken out. */
    data class Mark(val style: Style, val start: Int, val end: Int)

    data class Parsed(val text: String, val marks: List<Mark>)

    /**
     * All four rules in one alternation, tried in this order.
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
    private val HEADING = Regex("(?m)^#{1,6}\\s*")

    /**
     * Strip the markers and record where the emphasis was.
     *
     * Builds the result forwards rather than editing in place, so every index recorded is an index
     * into the string being returned.
     */
    fun parse(raw: String): Parsed {
        val source = raw.replace(HEADING, "")
        val out = StringBuilder()
        val marks = mutableListOf<Mark>()
        var last = 0

        for (m in INLINE.findAll(source)) {
            val groups = m.groups as? MatchNamedGroupCollection ?: continue
            val (style, inner) = when {
                groups["b"] != null -> Style.BOLD to groups["b"]!!.value
                groups["c"] != null -> Style.CODE to groups["c"]!!.value
                groups["i"] != null -> Style.ITALIC to groups["i"]!!.value
                else -> continue
            }
            out.append(source, last, m.range.first)
            val start = out.length
            out.append(inner)
            marks += Mark(style, start, out.length)
            last = m.range.last + 1
        }
        out.append(source, last, source.length)
        return Parsed(out.toString(), marks)
    }

    /** [parse], as something a TextView can show. */
    fun inline(raw: String): CharSequence {
        val parsed = parse(raw)
        val out = SpannableString(parsed.text)
        for (mark in parsed.marks) {
            if (mark.start < 0 || mark.end > out.length || mark.start >= mark.end) continue
            val span: Any = when (mark.style) {
                Style.BOLD -> StyleSpan(Typeface.BOLD)
                Style.ITALIC -> StyleSpan(Typeface.ITALIC)
                Style.CODE -> TypefaceSpan("monospace")
            }
            out.setSpan(span, mark.start, mark.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
