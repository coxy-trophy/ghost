package com.coxy.ghost.action

/**
 * Turns what Coxy called an app into the package that actually is it.
 *
 * This exists because `launch_app` used to take a package name and nothing else, which quietly made
 * the model responsible for a fact it cannot know. On this device "Meet" is
 * `com.google.android.apps.tachyon`, "Gmail" is `com.google.android.gm`, the launcher is
 * `ginlemon.flowerfree`, and two installed packages are pure obfuscation — no 1.5B model recalls
 * those, and the failure mode was a confident guess that resolved to nothing.
 *
 * So the model's job shrinks to repeating the name it was given, and the mapping happens here,
 * deterministically and testably, against the apps that are genuinely installed.
 *
 * Matching is tiered rather than scored. A tier is tried against every app before the next one is
 * considered, so a weaker kind of match can never outrank a stronger one — an exact label beats a
 * substring even when the substring hit looks longer or more specific.
 */
object AppMatcher {

    /** One launchable app: what it is called, and what it actually is. */
    data class AppEntry(val pkg: String, val label: String)

    sealed interface Resolution {
        /** Exactly one app matched at the strongest tier that matched anything. */
        data class Match(val entry: AppEntry) : Resolution

        /**
         * Several apps matched equally well. Deliberately *not* resolved by picking one: "google"
         * genuinely does not identify an app on a phone with nine of them, and launching a guess is
         * worse than asking, because Coxy sees the wrong app open and not the reason why.
         */
        data class Ambiguous(val candidates: List<AppEntry>) : Resolution

        /** Nothing matched. [near] carries anything remotely close, for the reply. */
        data class None(val query: String, val near: List<AppEntry>) : Resolution
    }

    /**
     * Words that carry no identity. The model is told to send the name as spoken, and people say
     * "the spotify app" — stripping these turns that into "spotify" rather than a failed lookup.
     */
    private val NOISE = setOf("app", "the", "my", "open", "launch", "start", "please")

    /** Everything that is not a letter or digit. Spacing and punctuation are not identity. */
    private val NON_ALNUM = Regex("[^a-z0-9]")

    /** Below this, a substring match means almost nothing — "on" would hit half the phone. */
    private const val MIN_CONTAINS = 4

    /** Below this, a prefix match is similarly worthless. */
    private const val MIN_PREFIX = 3

    internal fun normalize(raw: String): String =
        raw.lowercase().replace(NON_ALNUM, "")

    /** Drop noise words, then normalize. Empty if the query was only noise. */
    internal fun normalizeQuery(raw: String): String {
        val kept = raw.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in NOISE }
        // "app" alone is noise; "App Store" is not — if stripping left nothing, keep the original.
        return if (kept.isEmpty()) normalize(raw) else kept.joinToString("")
    }

    /**
     * Resolve [query] — a package name, an app label, or something close to one — against [apps].
     *
     * Tiers, strongest first:
     *  1. the exact package
     *  2. the exact label
     *  3. label prefix, either direction ("whats" finds WhatsApp; "youtube music" finds YT Music)
     *  4. label substring
     *  5. the last segment of the package ("dialer" finds the app labelled Phone)
     *
     * The last package segment is deliberately *last*, not first as it was originally written.
     * Segments are often generic: `com.spotify.music` ends in "music", so ranking it above labels
     * made "music" resolve silently to Spotify while YouTube Music sat right there in the list.
     * A segment is a fallback for when nothing was named recognisably, not a name in its own right.
     */
    fun resolve(query: String, apps: List<AppEntry>): Resolution {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return Resolution.None(query, emptyList())

        // Tier 1 is checked against the raw query too: a package name normalizes to the same string
        // as its own dots removed, but an exact literal match should not depend on that.
        val exactPkg = apps.filter { it.pkg.equals(query.trim(), ignoreCase = true) || normalize(it.pkg) == q }
        if (exactPkg.isNotEmpty()) return one(exactPkg)

        val exactLabel = apps.filter { normalize(it.label) == q }
        if (exactLabel.isNotEmpty()) return one(exactLabel)

        if (q.length >= MIN_PREFIX) {
            val prefix = apps.filter {
                val l = normalize(it.label)
                l.startsWith(q) || q.startsWith(l)
            }
            if (prefix.isNotEmpty()) return one(prefix)
        }

        if (q.length >= MIN_CONTAINS) {
            val contains = apps.filter { normalize(it.label).contains(q) }
            if (contains.isNotEmpty()) return one(contains)
        }

        val lastSegment = apps.filter { normalize(it.pkg.substringAfterLast('.')) == q }
        if (lastSegment.isNotEmpty()) return one(lastSegment)

        return Resolution.None(query, near(q, apps))
    }

    /**
     * One candidate is an answer; several at the same tier is a question.
     *
     * The exception is a tier that matched several apps where one of them is *also* what the query
     * would have been shortened to — sorting by label length puts the closest first so the reply
     * lists the likeliest option first rather than alphabetically.
     */
    private fun one(hits: List<AppEntry>): Resolution =
        if (hits.size == 1) Resolution.Match(hits.first())
        else Resolution.Ambiguous(hits.sortedBy { it.label.length })

    /**
     * Anything sharing a decent run of characters with the query, so a miss can say "did you mean"
     * instead of just "no". Cheap on purpose — this runs only when everything else already failed.
     */
    private fun near(q: String, apps: List<AppEntry>): List<AppEntry> {
        val stem = q.take(4)
        if (stem.length < 3) return emptyList()
        return apps.filter { normalize(it.label).contains(stem) || normalize(it.pkg).contains(stem) }
            .sortedBy { it.label.length }
            .take(3)
    }
}
