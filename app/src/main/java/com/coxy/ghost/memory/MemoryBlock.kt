package com.coxy.ghost.memory

/**
 * Renders recalled [MemoryEntry]s into the compact `[MEMORY ...]` block prepended to a
 * `sendUserPrompt` (the only per-query input channel — `setSystemPrompt` is once-only).
 *
 * Kept hard-capped to respect the 8192-token n_ctx budget (there's no Kotlin token counter, so the
 * cap is textual): at most [MAX_ENTRIES] turns, each word-truncated to [MAX_WORDS_PER_ENTRY]. That's
 * ≤ ~300 tokens — well within budget alongside the `[CONTEXT]` block, the system prompt, and the
 * turn. The native `shift_context()` is the safety net if a very long user message still overflows.
 *
 * Returns "" for an empty list so the no-memory case leaves the prompt unchanged from Phase 5.1.
 */
fun List<MemoryEntry>.toMemoryBlock(): String {
    if (isEmpty()) return ""
    val now = System.currentTimeMillis()
    val parts = take(MAX_ENTRIES).joinToString(" | ") { e ->
        val role = if (e.role == "user") "user" else "you"
        "${role}: \"${truncateWords(e.content, MAX_WORDS_PER_ENTRY)}\" (${ageLabel(now, e.tsMs)})"
    }
    return "[MEMORY $parts]"
}

private fun truncateWords(text: String, maxWords: Int): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.size <= maxWords) text.trim() else words.take(maxWords).joinToString(" ") + "…"
}

private fun ageLabel(nowMs: Long, tsMs: Long): String {
    val mins = (nowMs - tsMs) / 60_000L
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
}

private const val MAX_ENTRIES = 5
private const val MAX_WORDS_PER_ENTRY = 30