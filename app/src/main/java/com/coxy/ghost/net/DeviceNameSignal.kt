package com.coxy.ghost.net

/**
 * Notices Coxy naming a device, so that naming does not depend on the model choosing to act.
 *
 * This is the same shape of problem as Phase 6.4's
 * [com.coxy.ghost.personality.TraitSignalDetector], and it is here for the same reason. Twice in a
 * row — once on the local 1.5B and once on the cloud model — "the TV at 192.168.100.24 is the IT
 * office TV" produced a warm "Got it, I've locked that in" and no action block at all. Nothing was
 * stored, and the only way to find out was to ask for that device by name later and watch it fail.
 * Telling him a thing is saved when it is not is exactly what the bedrock rule forbids, and a
 * stronger instruction had already been tried.
 *
 * So the applying half stops waiting to be called. Detection is deliberately narrow, because a
 * false positive renames a device he did not mean:
 *
 * - the sentence must carry an **address** — a full IPv4 or a `.24` shorthand. "Call the TV the
 *   office TV" is not enough to act on when three things answer to "TV".
 * - the name must not be a state word. "192.168.100.24 is on" is an observation, not a name.
 * - the name must be short. A clause is a sentence about a device, not a name for one.
 */
object DeviceNameSignal {

    /** A naming Coxy stated: which device, and what he calls it. */
    data class Naming(val target: String, val name: String)

    private val FULL_IP = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")

    /** The way people actually refer to a host on a network they know: "the one at .24". */
    private val SHORT_OCTET = Regex("""(?:^|\s)\.(\d{1,3})\b""")

    /**
     * Words that answer "what is it doing", not "what is it called". Without these, "the TV at .24
     * is off" stores a device named "off".
     */
    private val NOT_NAMES = setOf(
        "on", "off", "up", "down", "playing", "paused", "stopped", "muted", "unmuted",
        "working", "broken", "fine", "dead", "asleep", "awake", "online", "offline",
        "there", "here", "mine", "yours", "his", "hers", "it", "that", "this", "gone",
        "connected", "disconnected", "busy", "free", "ready", "loud", "quiet", "silent"
    )

    private val LEADING_ARTICLES = Regex("""^(?:the|my|our|a|an|called|named|just)\s+""")

    /** Long enough to be a clause rather than a name. */
    private const val MAX_NAME_CHARS = 40

    /**
     * The naming this message states, or null.
     *
     * Handles both orders, because both are natural: "the TV at .24 is the office TV" and "the
     * office TV is the one at .24".
     */
    fun detect(text: String?): Naming? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 200) return null
        // A question is asking about a device, never naming one.
        if (raw.endsWith("?")) return null

        val target = FULL_IP.find(raw)?.groupValues?.get(1)
            ?: SHORT_OCTET.find(raw)?.groupValues?.get(1)?.let { ".$it" }
            ?: return null

        // "<something with the address> is <name>" — the common order.
        val afterIs = Regex("""\bis\s+(.{1,60}?)\s*[.!]?$""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)
        // "call/name <something> <name>" — the imperative order.
        val afterCall = Regex("""\b(?:call|name)\s+(?:it|that|this|the\s+\S+|\S+)\s+(.{1,60}?)\s*[.!]?$""",
            RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)

        val candidate = (afterCall ?: afterIs)?.let(::cleanName) ?: return null
        if (candidate.isEmpty() || candidate.length > MAX_NAME_CHARS) return null
        // The name must not just be the address restated.
        if (candidate.contains(target) || FULL_IP.containsMatchIn(candidate)) return null
        if (candidate.lowercase() in NOT_NAMES) return null
        // A name of one very short token that is also a state word is caught above; a name that is
        // *only* punctuation or digits is not a name either.
        if (candidate.none { it.isLetter() }) return null

        return Naming(target = target, name = candidate)
    }

    private fun cleanName(raw: String): String =
        raw.trim()
            .removeSuffix(",")
            .replace(LEADING_ARTICLES, "")
            .trim()
            .trim('"', '\'', '.', ',')
            .trim()
}
