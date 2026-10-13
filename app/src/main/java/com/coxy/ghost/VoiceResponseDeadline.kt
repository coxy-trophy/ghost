package com.coxy.ghost

/** Wall-clock UI deadline, independent of a potentially non-cooperative native inference call. */
internal class VoiceResponseDeadline(
    private val startedAt: Long,
    private val firstResponseMs: Long = 30_000,
    private val overallMs: Long = 90_000,
) {
    @Volatile private var responded = false
    @Volatile private var finished = false
    fun response() { responded = true }
    fun finish() { finished = true }
    fun expired(now: Long): Boolean = !finished &&
        (now - startedAt >= overallMs || (!responded && now - startedAt >= firstResponseMs))
}
