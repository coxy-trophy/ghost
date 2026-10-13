package com.coxy.ghost.perception

/**
 * Where a summon came from. Carried through [com.coxy.ghost.GhostService]'s `ACTION_SUMMON` as a
 * string extra so a missed or spurious summon can be traced to the channel that raised it — the
 * wake word, the assist gesture, the Quick Settings tile, the notification action, the volume-key
 * gesture, or a shell `am` call during testing.
 */
enum class SummonSource(val label: String) {
    WAKE("wake"),
    ASSIST("assist"),
    TILE("tile"),
    NOTIFICATION("notif"),
    KEY("key"),
    SHELL("shell");

    companion object {
        /** Parse an `EXTRA_SOURCE` string; unknown or missing values fall back to [SHELL]. */
        fun fromString(s: String?): SummonSource =
            entries.firstOrNull { it.label.equals(s, ignoreCase = true) } ?: SHELL
    }
}

/**
 * What the service should do when a summon arrives.
 *
 *  - [SHOW]   — the overlay is not up; raise it and open the mic.
 *  - [REOPEN] — the overlay is already up; leave it and just re-open the mic for another command.
 *  - [IGNORE] — a duplicate within the debounce window; do nothing.
 */
enum class SummonDecision { SHOW, REOPEN, IGNORE }

/**
 * The pure decision behind "summon GHOST from anywhere", with no Android types so the JVM suite can
 * pin it — the same split the project already makes for [com.coxy.ghost.perception.wakeword.WakeTrigger].
 *
 * Six channels can raise a summon and two of them routinely fire together — the volume-key gesture
 * and the wake word can both land in the same instant, and the assist gesture can double-deliver —
 * so a raw "show the overlay" call per trigger would stack windows or thrash the mic. This collapses
 * a burst into one action and decides show-vs-reopen against whether the overlay is already up.
 *
 * Not thread-safe: the service drives it from the main thread only.
 */
class SummonController(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    /** True while the overlay is on screen. Set via [onShown]/[onDismissed]. */
    var showing: Boolean = false
        private set

    /** The source of the summon currently on screen, for telemetry. Null when nothing is showing. */
    var currentSource: SummonSource? = null
        private set

    // Null until the first accepted summon. Nullable rather than a sentinel Long so the first call
    // can never be mistaken for "within the cooldown window" (subtracting a Long.MIN_VALUE sentinel
    // overflows and does exactly that).
    private var lastActionMs: Long? = null

    /**
     * Decide what to do with a summon from [source] at [nowMs].
     *
     * A summon within [cooldownMs] of the last accepted one is [SummonDecision.IGNORE] — that is the
     * duplicate-trigger case. Otherwise it is [SummonDecision.REOPEN] when the overlay is already up
     * and [SummonDecision.SHOW] when it is not. [SHOW] flips [showing] true immediately so a second
     * trigger in the same burst cannot also decide to show.
     */
    fun onSummon(source: SummonSource, nowMs: Long): SummonDecision {
        lastActionMs?.let { if (nowMs - it < cooldownMs) return SummonDecision.IGNORE }
        lastActionMs = nowMs
        return if (showing) {
            SummonDecision.REOPEN
        } else {
            showing = true
            currentSource = source
            SummonDecision.SHOW
        }
    }

    /** Record that the overlay is now on screen (idempotent with the [SHOW] path). */
    fun onShown(source: SummonSource) {
        showing = true
        currentSource = source
    }

    /** Record that the overlay has been dismissed. Arms the cooldown so an echo trigger is ignored. */
    fun onDismissed(nowMs: Long) {
        showing = false
        currentSource = null
        lastActionMs = nowMs
    }

    companion object {
        /**
         * Collapse triggers landing within this window into one action. Long enough to swallow a
         * key-gesture and a wake word arriving together, short enough that a deliberate re-summon a
         * moment after dismissing still lands.
         */
        const val DEFAULT_COOLDOWN_MS = 600L
    }
}
