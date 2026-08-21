package com.coxy.ghost.net

/**
 * Turns what Coxy called a device into the device on the network.
 *
 * The same job [com.coxy.ghost.action.AppMatcher] does for apps, and the same reason: he says "the
 * TV", the protocol needs `192.168.100.24`, and the model in between cannot be trusted to hold that
 * mapping. It is more important here than for apps, though — an app launched by mistake is a
 * wrong window, while a device controlled by mistake is somebody else's television.
 *
 * So ambiguity is never resolved by picking. This network carries three things that answer to
 * "TV"; guessing among them would be wrong two times in three, and silently.
 */
object DeviceMatcher {

    sealed interface Resolution {
        data class Match(val device: LanDevice) : Resolution
        data class Ambiguous(val candidates: List<LanDevice>) : Resolution
        data class None(val query: String, val near: List<LanDevice>) : Resolution
    }

    /** Words that name no device in particular. */
    private val NOISE = setOf("the", "my", "a", "on", "to", "device", "please")

    private val NON_ALNUM = Regex("[^a-z0-9]")

    /** Below this a substring match means almost nothing. */
    private const val MIN_CONTAINS = 3

    /**
     * Everyday words for a kind of device, so "telly" and "TV" both reach a thing whose mDNS name
     * is `Smart-TV-b2ad128c479835a5e8bd00857b54fc5b` — which is what these are really called, and
     * which nobody is going to say out loud.
     */
    private val KIND_WORDS = mapOf(
        "tv" to listOf("tv", "television", "casts", "androidtv", "airplay"),
        "television" to listOf("tv", "television", "casts"),
        "telly" to listOf("tv", "television", "casts"),
        "screen" to listOf("tv", "television", "casts"),
        "printer" to listOf("printer"),
        "router" to listOf("router", "gateway"),
        "speaker" to listOf("speaker", "airplay", "spotify", "sonos"),
        "pc" to listOf("computer", "media player", "workstation"),
        "computer" to listOf("computer", "media player", "workstation")
    )

    internal fun normalize(raw: String): String = raw.lowercase().replace(NON_ALNUM, "")

    internal fun normalizeQuery(raw: String): String {
        val kept = raw.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in NOISE }
        return if (kept.isEmpty()) normalize(raw) else kept.joinToString("")
    }

    /**
     * Resolve [query] against [devices].
     *
     * Tiers, strongest first: the exact address; the exact advertised name; a name prefix or
     * substring; then an everyday word for the kind of thing it is. The kind tier is last on
     * purpose — "TV" matching three televisions should be a question, and it only gets to be one if
     * a device actually called "TV" has already had its chance to win outright.
     */
    fun resolve(query: String, devices: List<LanDevice>): Resolution {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return Resolution.None(query, emptyList())

        val byIp = devices.filter { it.ip == query.trim() || normalize(it.ip) == q }
        if (byIp.isNotEmpty()) return one(byIp)

        // His name for it first, and as its own tier: if he has called something "office TV",
        // that must beat another device whose advertised name happens to contain "office".
        val exactNickname = devices.filter { it.nickname != null && normalize(it.nickname) == q }
        if (exactNickname.isNotEmpty()) return one(exactNickname)

        val partialNickname = devices.filter {
            val n = it.nickname?.let(::normalize) ?: return@filter false
            q.length >= MIN_CONTAINS && (n.startsWith(q) || n.contains(q))
        }
        if (partialNickname.isNotEmpty()) return one(partialNickname)

        val exactName = devices.filter { it.name != null && normalize(it.name) == q }
        if (exactName.isNotEmpty()) return one(exactName)

        if (q.length >= MIN_CONTAINS) {
            val partial = devices.filter {
                val n = it.name?.let(::normalize) ?: return@filter false
                n.startsWith(q) || q.startsWith(n) || n.contains(q)
            }
            if (partial.isNotEmpty()) return one(partial)
        }

        val words = KIND_WORDS[q] ?: KIND_WORDS.entries.firstOrNull { q.contains(it.key) }?.value
        if (words != null) {
            val byKind = devices.filter { d ->
                val hay = "${d.kind.orEmpty()} ${d.name.orEmpty()} ${d.services.joinToString(" ")}"
                    .lowercase()
                words.any { hay.contains(it) }
            }
            if (byKind.isNotEmpty()) return one(byKind)
        }

        return Resolution.None(query, devices.filter { it.isIdentified }.take(3))
    }

    /** One candidate is an answer; several is a question. See the note on this object. */
    private fun one(hits: List<LanDevice>): Resolution =
        if (hits.size == 1) Resolution.Match(hits.first())
        else Resolution.Ambiguous(hits.sortedBy { it.ip })

    /**
     * How to name a device back to Coxy when asking which one he meant.
     *
     * His name for it wins outright and the advertised one is dropped, not appended: the whole
     * point of naming the office TV is never to read
     * `Smart-TV-4df02b09600928db702d41131980b458` again.
     */
    fun label(device: LanDevice): String = when {
        device.nickname != null -> "${device.nickname} at ${device.ip}"
        device.name != null && device.kind != null -> "${device.name} (${device.kind}) at ${device.ip}"
        device.name != null -> "${device.name} at ${device.ip}"
        device.kind != null -> "${device.kind} at ${device.ip}"
        else -> device.ip
    }
}
