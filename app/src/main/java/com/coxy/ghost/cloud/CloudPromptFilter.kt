package com.coxy.ghost.cloud

/**
 * Privacy filter applied to prompts before they leave the device (Phase 8).
 *
 * The cloud model is the "brain", but the user can choose how much perceived context it is allowed
 * to see. Each mode removes a specific class of injected context from the prompt.
 */
object CloudPromptFilter {

    enum class Mode {
        /** Send the same prompt the local model receives: chat + memory + perception context. */
        FULL,
        /** Remove the live [CONTEXT] block (activity, location, mic, camera metadata). */
        NO_PERCEPTION,
        /** Remove the [MEMORY] recall block. */
        NO_MEMORY,
        /** Send only the current user message, with no history, memory, or perception. */
        CHAT_ONLY
    }

    /**
     * Interactive callers supply the original message separately so user-written block labels and
     * newlines cannot be mistaken for injected metadata. Lookups only prepend to that message.
     * Legacy/synthetic callers use the leading block convention instead.
     */
    fun apply(prompt: String, mode: Mode, userText: String? = null): String {
        if (mode == Mode.FULL) return prompt
        val metadata: List<String>
        val message: String
        if (userText != null) {
            require(prompt.endsWith(userText)) { "Prompt must end with the original user message" }
            metadata = prompt.dropLast(userText.length).trimEnd().lines()
            message = userText
        } else {
            val lines = prompt.lines()
            val boundary = lines.indexOfFirst { it.isNotBlank() && blockKind(it) == null }
                .let { if (it < 0) lines.size else it }
            metadata = lines.take(boundary)
            message = lines.drop(boundary).joinToString("\n")
        }
        val kept = metadata.filter { line ->
            when (mode) {
                Mode.FULL -> true
                Mode.NO_PERCEPTION -> blockKind(line) != "CONTEXT"
                Mode.NO_MEMORY -> blockKind(line) != "MEMORY"
                // Public lookup results remain available; LAN discovery is private context.
                Mode.CHAT_ONLY -> blockKind(line) == "WEB"
            }
        }.filter { it.isNotBlank() }
        return (kept + message).joinToString("\n")
    }

    private val BLOCK = Regex("^\\s*\\[(CONTEXT|MEMORY|PERSONALITY|WEB|LAN)\\b")
    private fun blockKind(line: String): String? = BLOCK.find(line)?.groupValues?.get(1)

    fun fromString(value: String): Mode {
        return runCatching { Mode.valueOf(value.uppercase()) }.getOrDefault(Mode.FULL)
    }
}
