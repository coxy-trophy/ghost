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
        // Score against the id+embedding projection, not whole entities: this runs on every single
        // prompt, and loading 1000 rows' content strings to throw all but five away is waste that
        // grows with the store.
        val rows = dao.embeddings()
        if (rows.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Float, Long>>(rows.size)
        for (r in rows) {
            val s = dot(q, r.embedding.toFloats())
            if (s > MIN_SIM) scored += s to r.id
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.first }
        val winnerIds = scored.take(topK).map { it.second }
        // byIds returns rows in whatever order SQLite hands back — restore similarity order.
        val byId = dao.byIds(winnerIds).associateBy { it.id }
        val winners = winnerIds.mapNotNull { byId[it] }
        runCatching { dao.bumpRecall(winnerIds) }
        Log.i(TAG, "recall query=\"${query.take(40)}\" hits=${scored.size} top=${winners.firstOrNull()?.content?.take(30)}")
        return winners
    }

    /**
     * Importance-weighted retention (the Phase 6 gate). When the row count exceeds [MAX_TURNS],
     * recompute every evictable row's importance (recency + recall-frequency) so eviction uses
     * fresh scores, then delete the lowest-importance rows down to the cap. Pinned rows are
     * excluded from both steps by `WHERE pinned = 0`, so they are never evicted.
     *
     * Called after every turn and again every 60s from the service, so the common path must stay
     * cheap: it is a single COUNT until the store is actually over cap.
     */
    suspend fun prune() {
        val count = dao.count()
        if (count <= MAX_TURNS) return
        // One UPDATE for the whole table (a frequently-recalled old memory must outrank a fresh
        // chitchat one), rather than a Kotlin loop issuing up to 1000 un-batched UPDATEs.
        dao.refreshImportance(System.currentTimeMillis(), RECALL_WEIGHT)
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

        /** Weight on recallCount in the retention score. Mirrored in [MemoryDao.refreshImportance]. */
        private const val RECALL_WEIGHT = 0.15

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