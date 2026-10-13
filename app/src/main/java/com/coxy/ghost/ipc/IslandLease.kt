package com.coxy.ghost.ipc

/** Monotonic, short-lived advertisement from an authenticated and enabled Nocturne surface. */
class IslandLease(private val durationMs: Long = 15000L) {
    private var advertisedAt: Long? = null
    fun clear() { advertisedAt = null }
    fun advertise(enabled: Boolean, now: Long) { advertisedAt = if (enabled) now else null }
    fun available(now: Long): Boolean = advertisedAt?.let { now >= it && now - it < durationMs } ?: false
}
