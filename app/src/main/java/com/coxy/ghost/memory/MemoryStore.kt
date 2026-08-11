package com.coxy.ghost.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * The Phase 6.1 memory facade: persist each conversation turn with its embedding, retrieve the
 * top-K most semantically similar turns for a query, and keep the store bounded.
 *
 * Two dispatchers, neither of which is the engine's single-threaded `llamaDispatcher`:
 *  - [embedDispatcher] (single-threaded) for TFLite inference — the [org.tensorflow.lite.Interpreter]
 *    is not thread-safe, and serialising embeds also keeps them off the CPU during inference
 *    (recall runs before `sendUserPrompt`, remember runs after the reply, so they never overlap
 *    inference anyway).
 *  - Room's own executor for the suspend DAO calls (main-safe).
 *
 * Recall is a linear cosine-similarity scan over every row. The store is capped to
 * [MAX_TURNS] (~1000), so that's ~1000 dot-products of a 384-dim normalised vector — sub-millisecond
 * to low-millisecond. No ANN index needed at this scale.
 */
class MemoryStore(context: Context) {
    private val dao = GhostDatabase.get(context).dao()
    private val embedDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Persist a turn: strip the deprecated "Ghost:" prefix, embed, + insert. */
    suspend fun remember(role: String, content: String, source: String) {
        val cleaned = stripGhostPrefix(content)
        if (cleaned.isBlank()) return
        val emb = withContext(embedDispatcher) { Embedder.embed(cleaned) }
        if (emb == null) {
            Log.w(TAG, "remember: embed unavailable, storing without embedding (unrecallable)")
        }
        val now = System.currentTimeMillis()
        val importance = recencyScore(now, now) // 1.0 at write; recallCount contributes as it grows
        dao.insert(
            MemoryEntry(
                role = role,
                content = cleaned,
                tsMs = now,
                embedding = emb?.toBytes() ?: ByteArray(0),
                importance = importance,
                source = source
            )
        )
    }

    /**
     * Top-K turns most semantically similar to [query] (cosine similarity = dot product of the
     * L2-normalised embeddings). Bumps the winners' recall counts so frequently-recalled memories
     * score higher for retention. Returns empty if the embedder isn't ready or the store is empty.
     */
    suspend fun recall(query: String, topK: Int = DEFAULT_TOP_K): List<MemoryEntry> {
        val q = withContext(embedDispatcher) { Embedder.embed(query) } ?: return emptyList()
        val all = dao.all()
        if (all.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Float, MemoryEntry>>(all.size)
        for (e in all) {
            val eb = e.embedding
            if (eb.isEmpty()) continue
            val s = dot(q, eb.toFloats())
            if (s > MIN_SIM) scored += s to e
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.first }
        val winners = scored.take(topK)
        runCatching { dao.bumpRecall(winners.map { it.second.id }) }
        Log.i(TAG, "recall query=\"${query.take(40)}\" hits=${scored.size} top=${winners.firstOrNull()?.second?.content?.take(30)}")
        return winners.map { it.second }
    }

    /**
     * Importance-weighted retention (the Phase 6 gate). When the row count exceeds [MAX_TURNS],
     * recompute every evictable row's importance (recency × recall-frequency) so eviction uses
     * fresh scores, then delete the lowest-importance non-pinned rows down to the cap. Pinned rows
     * get +1e9 and are never evicted.
     */
    suspend fun prune() {
        val count = dao.count()
        if (count <= MAX_TURNS) return
        val now = System.currentTimeMillis()
        // Recompute importance for evictable rows so the eviction order reflects current recency +
        // recall frequency (a frequently-recalled old memory must outrank a fresh chitchat one).
        for (s in dao.evictableSnapshots()) {
            val imp = recencyScore(now, s.tsMs) + 0.15 * s.recallCount
            dao.setImportance(s.id, imp)
        }
        val toEvict = count - MAX_TURNS
        dao.evictLowest(toEvict)
        Log.i(TAG, "prune: evicted $toEvict rows (was $count, cap $MAX_TURNS)")
    }

    /** Newest-first turns for the chat list on app open (UX continuity). */
    suspend fun recent(n: Int = 20): List<MemoryEntry> = dao.recent(n).asReversed()

    suspend fun count(): Int = dao.count()

    /** One-time cleanup: strip leading "Ghost:" from every row (case-insensitive, trailing space optional). */
    suspend fun stripGhostPrefixesFromDb(): Int {
        val rows = dao.all()
        var changed = 0
        for (e in rows) {
            val cleaned = stripGhostPrefix(e.content)
            if (cleaned != e.content) {
                dao.delete(e.id)
                val emb = withContext(embedDispatcher) { Embedder.embed(cleaned) }
                dao.insert(
                    e.copy(
                        id = 0,
                        content = cleaned,
                        embedding = emb?.toBytes() ?: ByteArray(0)
                    )
                )
                changed++
            }
        }
        Log.i(TAG, "stripGhostPrefixesFromDb: cleaned $changed/${rows.size} rows")
        return changed
    }

    private fun recencyScore(nowMs: Long, tsMs: Long): Double {
        val ageDays = max(0.0, (nowMs - tsMs) / 86_400_000.0)
        return 1.0 / (1.0 + ageDays)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var sum = 0f
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    private fun FloatArray.toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(this)
        return bb.array()
    }

    private fun ByteArray.toFloats(): FloatArray {
        val out = FloatArray(size / 4)
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    companion object {
        private const val TAG = "MemoryStore"
        private const val MAX_TURNS = 1000
        private const val DEFAULT_TOP_K = 5
        private const val MIN_SIM = 0.20f // below this, not similar enough to inject

        /** Strip a leading "Ghost:" (case-insensitive, optional trailing space) from assistant text. */
        @JvmStatic
        fun stripGhostPrefix(text: String): String {
            val marker = "Ghost:"
            val trimmed = text.trimStart()
            return if (trimmed.regionMatches(0, marker, 0, marker.length, ignoreCase = true)) {
                trimmed.drop(marker.length).trimStart()
            } else {
                text
            }
        }
    }
}