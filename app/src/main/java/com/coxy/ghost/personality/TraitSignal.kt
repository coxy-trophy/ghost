package com.coxy.ghost.personality

/** A correction Coxy made about *how* GHOST speaks, and which way it should move her. */
data class TraitSignal(
    /** One of [PersonalityProfile.traits]' names. */
    val trait: String,
    /** +1 to move toward the top of the trait's budget, -1 toward the bottom. */
    val direction: Int,
    /** The words that triggered it, for the log and for the acknowledgement. */
    val matched: String,
)

/**
 * Notices when Coxy corrects GHOST's manner — "too sarcastic", "be more direct", "keep it short".
 *
 * Phase 6.4. The applying half has existed since 6.3 ([PersonalityTranslator.nudge], drift-budget
 * clamped and tested); what was missing was the part that notices he said it. This is that part,
 * and it is deliberately narrow.
 *
 * **A false positive silently rewrites her personality**, which is worse than missing a correction:
 * a missed one gets repeated by a person who can see it did not land, while a phantom one drifts
 * her somewhere neither of them chose and leaves no trace in the conversation. So two guards run
 * over everything below.
 *
 *  - **Only short messages.** Real corrections are terse — "less sarcasm", "be more direct". A
 *    sentence long enough to be about the world usually is: "the drive was too long and we got
 *    stuck behind a lorry" contains "too long" and is not feedback about her verbosity.
 *  - **Only phrasings aimed at her.** Every pattern is an imperative or an evaluation of manner,
 *    not a bare adjective. "That's funny" is not a request to be funnier.
 */
object TraitSignalDetector {

    /** Longer than this and it is a sentence about the world, not a note about her manner. */
    const val MAX_WORDS = 12

    fun detect(text: String): TraitSignal? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val words = clean.split(Regex("\\s+")).size
        if (words > MAX_WORDS) return null

        for ((pattern, trait, direction) in PATTERNS) {
            val m = pattern.find(clean) ?: continue
            return TraitSignal(trait, direction, m.value.trim())
        }
        return null
    }

    /**
     * Ordered, first match wins. Specific phrasings come before general ones so "get to the point"
     * lands on bluntness rather than being caught by a shorter verbosity rule.
     */
    private val PATTERNS: List<Triple<Regex, String, Int>> = listOf(
        // --- sarcasm ---
        rule("""\b(?:too|overly)\s+sarcastic\b""", "sarcasm", -1),
        rule("""\b(?:less|not so|no more)\s+sarcas(?:m|tic)\b""", "sarcasm", -1),
        rule("""\b(?:stop|quit|drop|cut)\s+(?:it\s+with\s+)?(?:the\s+|being\s+)?(?:so\s+)?sarcas(?:m|tic)\b""", "sarcasm", -1),
        rule("""\bno\s+sarcasm\b""", "sarcasm", -1),
        rule("""\b(?:be|get)\s+more\s+sarcastic\b""", "sarcasm", +1),

        // --- humour ---
        rule("""\b(?:stop|quit)\s+(?:joking|the jokes|with the jokes)\b""", "humor", -1),
        rule("""\b(?:too many|fewer|less)\s+jokes\b""", "humor", -1),
        rule("""\bbe\s+serious\b""", "humor", -1),
        rule("""\b(?:be|get)\s+funnier\b""", "humor", +1),
        rule("""\bmore\s+(?:jokes|humou?r)\b""", "humor", +1),
        rule("""\blighten\s+up\b""", "humor", +1),

        // --- bluntness / directness ---
        rule("""\b(?:be|get)\s+(?:more\s+)?(?:direct|blunt|straight)\b""", "bluntness", +1),
        rule("""\bget\s+to\s+the\s+point\b""", "bluntness", +1),
        rule("""\bstop\s+(?:hedging|waffling|beating around)\b""", "bluntness", +1),
        rule("""\b(?:don'?t|do not)\s+sugarcoat\b""", "bluntness", +1),
        rule("""\bsay\s+it\s+straight\b""", "bluntness", +1),
        rule("""\b(?:too|overly)\s+(?:blunt|harsh)\b""", "bluntness", -1),
        rule("""\b(?:be|get)\s+(?:gentler|softer|less blunt)\b""", "bluntness", -1),

        // --- warmth ---
        rule("""\b(?:be|get)\s+(?:warmer|kinder|nicer)\b""", "warmth", +1),
        rule("""\byou'?re\s+(?:being\s+)?(?:cold|distant)\b""", "warmth", +1),
        rule("""\b(?:too|overly)\s+(?:sentimental|mushy|sappy)\b""", "warmth", -1),

        // --- formality ---
        rule("""\b(?:too|overly)\s+formal\b""", "formality", -1),
        rule("""\b(?:less|not so)\s+formal\b""", "formality", -1),
        rule("""\b(?:be|talk|speak)\s+(?:more\s+)?casual(?:ly)?\b""", "formality", -1),
        rule("""\bstop\s+being\s+so\s+formal\b""", "formality", -1),
        rule("""\b(?:be|get)\s+more\s+formal\b""", "formality", +1),

        // --- verbosity ---
        rule("""\b(?:too)\s+(?:long|wordy|verbose)\b""", "verbosity", -1),
        rule("""\b(?:keep it|be|make it)\s+(?:short|shorter|brief|briefer)\b""", "verbosity", -1),
        rule("""\b(?:shorter|fewer words|less rambling)\b""", "verbosity", -1),
        rule("""\bstop\s+rambling\b""", "verbosity", -1),
        rule("""\b(?:more|greater)\s+detail\b""", "verbosity", +1),
        rule("""\b(?:explain|say)\s+more\b""", "verbosity", +1),
        rule("""\b(?:go\s+deeper|elaborate)\b""", "verbosity", +1),

        // --- curiosity ---
        rule("""\b(?:stop|quit)\s+asking\s+(?:me\s+)?(?:so many\s+)?questions\b""", "curiosity", -1),
        rule("""\b(?:too many|fewer)\s+questions\b""", "curiosity", -1),
        rule("""\bask\s+me\s+more\b""", "curiosity", +1),
        rule("""\b(?:be|get)\s+more\s+curious\b""", "curiosity", +1),

        // --- initiative ---
        rule("""\bstop\s+(?:interrupting|butting in|chiming in)\b""", "initiative", -1),
        rule("""\b(?:stop|quit)\s+suggesting\b""", "initiative", -1),
        rule("""\b(?:be|get)\s+more\s+proactive\b""", "initiative", +1),
        rule("""\bspeak\s+up\s+more\b""", "initiative", +1),
    )

    private fun rule(pattern: String, trait: String, direction: Int) =
        Triple(Regex(pattern, RegexOption.IGNORE_CASE), trait, direction)
}
