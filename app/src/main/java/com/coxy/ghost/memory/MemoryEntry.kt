package com.coxy.ghost.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of GHOST's persistent memory (Phase 6.1). A conversation turn — user or assistant —
 * together with its precomputed sentence embedding (so recall is a cheap cosine-similarity scan,
 * not a re-embed) and an importance score used for bounded retention.
 *
 * The embedding is the L2-normalised 384-dim output of the on-device MiniLM TFLite embedder, stored
 * as a byte blob (FloatArray → ByteBuffer). [MemoryStore] decodes it back for the cosine scan.
 *
 * Retention (the Phase 6 gate): a hard cap on rows; when exceeded, [MemoryStore.prune] evicts the
 * lowest-[importance] non-pinned rows. `importance = recencyScore + 0.15 * recallCount` (pinned rows
 * get +1e9 so they're never evicted). It's denormalised as a column so eviction is a single SQL
 * `ORDER BY importance ASC LIMIT n` — no full-table scan in Kotlin at prune time.
 *
 * `pinned` is forward-compat for Phase 6.2+ (user-curated facts that must survive eviction); it
 * defaults false and isn't user-exposed in 6.1.
 */
@Entity(tableName = "memory")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,            // "user" | "assistant"
    val content: String,
    val tsMs: Long,
    val embedding: ByteArray,
    val importance: Double = 0.0,
    val recallCount: Int = 0,
    val pinned: Boolean = false,
    val source: String = "typed" // "typed" | "voice" | "fact"
) {
    // ByteArray identity would make data-class equals/hashCode useless; Room doesn't need them.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}