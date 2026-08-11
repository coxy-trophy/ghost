package com.coxy.ghost.action

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts structured actions from a model reply (Phase 7).
 *
 * The parser is deliberately tolerant: it looks for JSON objects or arrays anywhere in the text
 * (inside markdown code fences or inline) and validates them against the [ActionCatalog]. Valid
 * actions are returned; invalid or unknown ones are returned as errors so GHOST can report them.
 */
object ActionParser {

    data class ParsedActions(
        val actions: List<Action>,
        val errors: List<String>,
        /** The original text with JSON action blocks removed, suitable for the user-visible reply. */
        val cleanedReply: String
    )

    /**
     * Parse a model reply, returning any actions found plus a version of the text with those JSON
     * blocks stripped.
     */
    fun extract(reply: String): ParsedActions {
        val actions = mutableListOf<Action>()
        val errors = mutableListOf<String>()
        val rangesToRemove = mutableListOf<IntRange>()

        val codeFenceBlocks = extractCodeFenceBlocks(reply)
        for (range in codeFenceBlocks) {
            val block = reply.substring(range)
            val json = block.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            parseJsonContainer(json, actions, errors)
            rangesToRemove += range
        }

        // Also scan for inline JSON objects/arrays that are not inside code fences.
        val inlineRegex = Regex("\\{[^{}]*\\}|\\[[^\\[\\]]*\\]")
        inlineRegex.findAll(reply).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            // Skip if it overlaps a code fence we already handled.
            if (codeFenceBlocks.none { start <= it.last && end >= it.first }) {
                parseJsonContainer(match.value, actions, errors)
                rangesToRemove += match.range
            }
        }

        val cleaned = removeRanges(reply, rangesToRemove).trim()
        return ParsedActions(actions, errors, cleaned)
    }

    private fun extractCodeFenceBlocks(text: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        val regex = Regex("```(json)?[\\s\\S]*?```")
        regex.findAll(text).forEach { result += it.range }
        return result
    }

    private fun parseJsonContainer(json: String, actions: MutableList<Action>, errors: MutableList<String>) {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return
        when (trimmed.first()) {
            '{' -> parseSingleAction(trimmed, actions, errors)
            '[' -> {
                runCatching {
                    val array = JSONArray(trimmed)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i)
                        if (obj != null) parseSingleAction(obj, actions, errors)
                        else errors += "array entry $i is not an object"
                    }
                }.onFailure { errors += "invalid action array: ${it.message}" }
            }
            else -> errors += "action block is neither object nor array"
        }
    }

    private fun parseSingleAction(json: String, actions: MutableList<Action>, errors: MutableList<String>) {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            errors += "invalid action JSON: ${it.message}"
            return
        }
        parseSingleAction(obj, actions, errors)
    }

    private fun parseSingleAction(obj: JSONObject, actions: MutableList<Action>, errors: MutableList<String>) {
        val id = obj.optString("action", "").trim().lowercase()
        if (id.isBlank()) {
            errors += "action JSON missing 'action' field"
            return
        }
        val entry = ActionCatalog.lookup(id)
        if (entry == null) {
            errors += "unknown action '$id'"
            return
        }

        val params = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "action") continue
            params[key] = obj.optString(key, "")
        }

        val missing = ActionCatalog.validate(id, params)
        if (missing.isNotEmpty()) {
            errors += "action '$id': ${missing.joinToString("; ")}"
            return
        }

        actions += Action(id = id, tier = entry.tier, params = params)
    }

    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sorted = ranges.sortedBy { it.first }
        val sb = StringBuilder()
        var last = 0
        for (range in sorted) {
            if (range.first > last) sb.append(text.substring(last, range.first))
            last = maxOf(last, range.last + 1)
        }
        if (last < text.length) sb.append(text.substring(last))
        return sb.toString()
    }
}
