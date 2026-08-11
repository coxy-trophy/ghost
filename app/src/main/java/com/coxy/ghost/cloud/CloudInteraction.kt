package com.coxy.ghost.cloud

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 8 distillation log: one row per cloud inference call.
 *
 * The prompt stored here is the privacy-filtered version actually sent to Ollama Cloud, so it
 * doubles as an audit trail and as training data for a future LoRA distillation cycle. The local
 * model path/fallback reply are *not* stored here (they live in the normal memory table as a turn).
 */
@Entity(tableName = "cloud_interaction")
data class CloudInteraction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock millis when the request was initiated. */
    val requestedMs: Long,
    /** The cloud model that produced the response, or "local" if every cloud attempt failed. */
    val modelUsed: String,
    /** Privacy-filtered prompt that was sent (system + user concatenated for logging). */
    val prompt: String,
    /** Cloud response text, truncated to a sane cap at insert time. */
    val response: String,
    /** True if every cloud model failed and the local 1.5B had to answer. */
    val wasFallback: Boolean,
    /** Round-trip latency in milliseconds, or -1 if unavailable. */
    val latencyMs: Long,
    /** Which privacy filter was applied (full / no_perception / no_memory / chat_only). */
    val privacyMode: String
)
