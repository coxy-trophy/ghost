package com.coxy.ghost.execution

import java.util.UUID

/** Consumes persisted approval before executing: process death cannot replay the action. */
internal class ApprovalLedger(private val store: Store) {
    data class Entry(val payload: String, val expiresAt: Long, val boot: Int)
    interface Store {
        fun entries(): Map<String, Entry>
        fun put(id: String, entry: Entry): Boolean
        fun remove(id: String): Boolean
    }
    @Synchronized fun create(payload: String, now: Long, boot: Int): String? {
        if (payload.isBlank() || payload.length > 16_384 || boot < 0) return null
        val entries = store.entries()
        for ((id, entry) in entries) if (entry.boot != boot || now >= entry.expiresAt) {
            if (!store.remove(id)) return null
        }
        if (store.entries().size >= 16) return null
        val id = UUID.randomUUID().toString()
        return if (store.put(id, Entry(payload, now + 300_000L, boot))) id else null
    }
    @Synchronized fun consume(id: String, now: Long, boot: Int): String? {
        if (id.length != 36) return null
        val entry = store.entries()[id] ?: return null
        if (!store.remove(id)) return null
        return entry.payload.takeIf { boot >= 0 && entry.boot == boot && now < entry.expiresAt }
    }
}
