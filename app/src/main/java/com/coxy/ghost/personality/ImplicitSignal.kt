package com.coxy.ghost.personality

/**
 * Reads how Coxy *reacted*, rather than what he asked for.
 *
 * Phase 6.5 / personality doc §4a: "which responses get engaged with vs. cut short, corrected, or
 * ignored → smaller, gradual nudges". Explicit corrections say what to change; these infer it, so
 * they are held to a much higher bar and moved a fraction of the distance.
 *
 * Only two signals are implemented, and deliberately so. Both leave hard evidence in the
 * conversation that can be pointed at afterwards:
 *
 *  - **He cut a long answer short.** A one-word acknowledgement after a wall of text is the
 *    clearest "that was more than I wanted" a person gives without saying it.
 *  - **He had to ask again.** A near-repeat of his own previous message means the last answer did
 *    not land, and the useful correction for that is plainness, not volume.
 *
 * Things that look like signals and are not, left out on purpose: a long reply from him is
 * engagement with the *topic*, not approval of her manner; silence has no timestamp to measure
 * against in a chat that is answered hours later; and "ignored" cannot be distinguished from "put
 * the phone down". Inferring personality changes from those would be guessing with her identity.
 */
object ImplicitSignalDetector {

    /** A reply longer than this, met with a dismissal, is the verbosity signal. */
    const val LONG_REPLY_WORDS = 40

    /** How alike two of his messages must be before the second counts as asking again. */
    const val REPEAT_SIMILARITY = 0.6

    /** Acknowledgements that close a topic rather than continue it. */
    private val DISMISSALS = setOf(
        "ok", "okay", "k", "kk", "fine", "sure", "right", "cool", "alright",
        "whatever", "nvm", "nevermind", "yeah", "yep", "mm", "mhm", "noted",
    )

    /** Words too common to say anything about whether two messages are the same question. */
    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "do", "does", "did", "to", "of", "in", "on",
        "for", "and", "or", "but", "i", "you", "me", "my", "your", "it", "that", "this", "what",
        "how", "why", "can", "could", "would", "should", "please", "just", "so", "be", "am",
    )

    /**
     * @param previousUserText his message before this one, or null when this is the first.
     * @param previousAssistantText her reply to it, or null.
     * @param currentUserText what he just sent.
     */
    fun detect(
        previousUserText: String?,
        previousAssistantText: String?,
        currentUserText: String,
    ): TraitSignal? {
        val current = currentUserText.trim()
        if (current.isEmpty()) return null

        // 1. A long answer met with a shrug.
        val currentWords = words(current)
        if (currentWords.size <= 2 && currentWords.all { it in DISMISSALS }) {
            val replyLength = previousAssistantText?.let { words(it).size } ?: 0
            if (replyLength >= LONG_REPLY_WORDS) {
                return TraitSignal("verbosity", -1, "cut short after a ${replyLength}-word reply")
            }
            return null
        }

        // 2. He asked the same thing again.
        if (previousUserText != null) {
            val a = contentWords(previousUserText)
            val b = contentWords(current)
            if (a.size >= 3 && b.size >= 3 && jaccard(a, b) >= REPEAT_SIMILARITY) {
                return TraitSignal("bluntness", +1, "asked again in nearly the same words")
            }
        }

        return null
    }

    private fun words(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotBlank() }

    private fun contentWords(text: String): Set<String> =
        words(text).filter { it.length > 1 && it !in STOP_WORDS }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val union = a.size + b.size - a.count { it in b }
        if (union == 0) return 0.0
        return a.count { it in b }.toDouble() / union
    }
}
