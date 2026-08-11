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

    fun apply(prompt: String, mode: Mode): String {
        return when (mode) {
            Mode.FULL -> prompt
            Mode.NO_PERCEPTION -> prompt.replace(Regex("\\[CONTEXT\\b[^\\n]*"), "").trim()
            Mode.NO_MEMORY -> prompt.replace(Regex("\\[MEMORY\\b[^\\n]*"), "").trim()
            Mode.CHAT_ONLY -> {
                // Strip both blocks and drop everything before the last user line (the rest is
                // prior context/memory that the local model would normally see in one string).
                val noBlocks = prompt
                    .replace(Regex("\\[CONTEXT\\b[^\\n]*"), "")
                    .replace(Regex("\\[MEMORY\\b[^\\n]*"), "")
                    .replace(Regex("\\[PERSONALITY\\b[^\\n]*"), "")
                    .trim()
                // Take only the final line as the current user message.
                noBlocks.lineSequence().lastOrNull { it.isNotBlank() } ?: noBlocks
            }
        }
    }

    fun fromString(value: String): Mode {
        return runCatching { Mode.valueOf(value.uppercase()) }.getOrDefault(Mode.FULL)
    }
}
