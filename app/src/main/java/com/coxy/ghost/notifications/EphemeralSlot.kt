package com.coxy.ghost.notifications

/** One pending handoff, never persisted; wrong, consumed or expired tokens reveal nothing. */
internal class EphemeralSlot<T> {
    private var token: String? = null
    private var expires = 0L
    private var value: T? = null
    @Synchronized fun put(id: String, data: T, expiresAt: Long) { token = id; value = data; expires = expiresAt }
    @Synchronized fun take(id: String?, now: Long): T? {
        if (now >= expires) { token = null; value = null; return null }
        if (id == null || id != token) return null
        val result = value
        token = null; value = null
        return result
    }
    @Synchronized fun discard(id: String) { if (token == id) { token = null; value = null } }
}
