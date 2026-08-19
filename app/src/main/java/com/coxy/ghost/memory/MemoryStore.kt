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

        // Never persist fact-extraction output as a conversational turn. See
        // [looksLikeExtractionOutput] — this is the defect that made GHOST start calling Coxy
        // "Ghost". Facts still reach the store through the source="fact" path below.
        if (source != "fact" && looksLikeExtractionOutput(cleaned)) {
            Log.w(TAG, "remember: dropping extraction-shaped $role turn (source=$source): ${cleaned.take(60)}")
            return
        }
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

        // Take a wider candidate set than needed so the quota below has something to promote in
        // place of what it drops. Still a projection of a handful of rows, not the whole table.
        val candidateIds = scored.take(topK * CANDIDATE_FACTOR).map { it.second }
        // byIds returns rows in whatever order SQLite hands back — restore similarity order.
        val byId = dao.byIds(candidateIds).associateBy { it.id }
        val candidates = candidateIds.mapNotNull { byId[it] }

        // Cap how much of her own past *phrasing* comes back.
        //
        // The block renders recalled turns verbatim as `you: "..."`, so a reply that quotes her own
        // previous answers is being shown a template, and a small-enough gap between two similar
        // questions makes copying it the likeliest continuation. Observed 2026-08-19: two answers 28
        // minutes apart, the second reproducing the first nearly word for word.
        //
        // Extracted facts are deliberately exempt even though they are stored with role=assistant.
        // They are information rather than voice — "he takes his coffee black" is what memory is
        // *for* — and filtering on role alone would starve exactly the entries worth recalling.
        var spoken = 0
        val winners = candidates.filter { e ->
            val isHerVoice = e.role == "assistant" && e.source != "fact"
            if (!isHerVoice) true else (spoken++ < MAX_SPOKEN_RECALLS)
        }.take(topK)
        val winnerIds = winners.map { it.id }
        if (winnerIds.isEmpty()) return emptyList()
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

    /**
     * One-time cleanup: delete conversational turns that are actually fact-extraction output.
     *
     * Rows with `source="fact"` are the legitimate extracted facts and are left alone; only turns
     * that were stored as things GHOST *said* are removed. Without this, the rows already in the DB
     * keep being recalled into `[MEMORY]` and keep re-teaching the behaviour, so fixing
     * [remember] alone would not stop it.
     */
    suspend fun purgeExtractionArtifacts(): Int {
        val rows = dao.all()
        var removed = 0
        for (e in rows) {
            if (e.source != "fact" && looksLikeExtractionOutput(e.content)) {
                dao.delete(e.id)
                removed++
            }
        }
        Log.i(TAG, "purgeExtractionArtifacts: removed $removed/${rows.size} rows")
        return removed
    }

    /**
     * Delete assistant turns in which GHOST claims to be somewhere other than where Coxy is.
     *
     * `location=` went live on 2026-08-18 and the first thing it produced was "We are at home, and
     * it is dark. **I am far away from you**". The context block reports the phone's state, the
     * phone is in his pocket, and nothing in the prompt said so — a 1.5B will not infer it. The
     * prompt now states the co-location outright, but that fix alone did not change the answers:
     * the rows were already in the DB, `recall()` surfaced them for exactly the questions that
     * provoke the confusion, and the model copied its own recorded behaviour.
     *
     * This is the same failure as the `FACT:` artifacts in v0.11.4 and takes the same remedy —
     * see [purgeExtractionArtifacts]. Fixing the prompt stops new ones; only this stops the old
     * ones from re-teaching it.
     *
     * Deliberately limited to `assistant` rows. "Why are you far away from me" is a real thing Coxy
     * said, and deleting his side of the conversation to make the model behave would be rewriting
     * history rather than cleaning up after a defect.
     */
    suspend fun purgeSeparationClaims(): Int {
        val rows = dao.all()
        var removed = 0
        for (e in rows) {
            if (e.role == "assistant" && claimsSeparation(e.content)) {
                dao.delete(e.id)
                removed++
            }
        }
        Log.i(TAG, "purgeSeparationClaims: removed $removed/${rows.size} rows")
        return removed
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

        /**
         * True when a line asserts that GHOST and Coxy are in different places.
         *
         * Matches both directions ("far away from you", "far away from me") because the model
         * produced both. Kept narrow on purpose: it is a delete, and the cost of a false positive
         * is a real memory destroyed.
         */
        @JvmStatic
        fun claimsSeparation(content: String): Boolean = SEPARATION.containsMatchIn(content)

        // "apart from" and "not with" were in an earlier version of this and have been removed.
        // Both are ordinary English idioms that mean the opposite of separation — "apart from you,
        // nobody knows" is *besides*, and "I'm not with you on that" is *I don't follow* — and
        // neither was ever observed in the rows this exists to clean up. On a matcher that drives a
        // delete, an unobserved pattern is not worth the risk of destroying a real memory.
        private val SEPARATION = Regex(
            """\b(?:far\s+away\s+from|far\s+from|away\s+from)\s+(?:you|me)\b""",
            RegexOption.IGNORE_CASE,
        )

        private const val TAG = "MemoryStore"
        private const val MAX_TURNS = 1000
        private const val DEFAULT_TOP_K = 5
        private const val MIN_SIM = 0.20f // below this, not similar enough to inject

        /**
         * How many of her own conversational replies may appear in one `[MEMORY]` block.
         *
         * One is enough to keep continuity — "you already told him this" — without handing the
         * model a set of its own answers to imitate. Extracted facts do not count against it.
         */
        private const val MAX_SPOKEN_RECALLS = 1

        /** Candidate multiple fetched so the quota has better entries to promote in place. */
        private const val CANDIDATE_FACTOR = 4

        /** Weight on recallCount in the retention score. Mirrored in [MemoryDao.refreshImportance]. */
        private const val RECALL_WEIGHT = 0.15

        /**
         * True when [text] is fact-extraction output rather than something GHOST said to Coxy.
         *
         * `FactExtractor` drives the *same* single conversation context as normal chat — the engine
         * exposes one context and giving it a second would need a `:lib` rebuild — so its
         * `[FACT_EXTRACTION]` meta-turns become part of the model's own history. A 1.5B does not
         * reliably honour the system prompt's "skip those"; it learns the pattern instead and starts
         * answering ordinary questions with `FACT: GHOST did X`.
         *
         * That is the root of "the AI keeps calling me ghost". Those replies were stored as GHOST's
         * conversational turns, so `[MEMORY]` fed them back as `you: FACT: GHOST can't hear the
         * user clearly` — dozens of examples of GHOST referring to *itself* in the third person and
         * to a participant named "GHOST". The role structure collapsed and the name landed on Coxy.
         * Observed live: 11 of 37 assistant turns in the memory DB were extraction output, and three
         * assistant turns were the literal text "Hey Ghost, what are you doing?".
         *
         * Blocking it at the storage boundary keeps the damage from compounding across restarts even
         * when the model does emit it. Fact extraction is also off by default now
         * ([com.coxy.ghost.GhostPrefs.factExtractionEnabledFlow]) so the pollution stops at source.
         */
        @JvmStatic
        fun looksLikeExtractionOutput(text: String): Boolean {
            val t = text.trimStart()
            return t.startsWith("FACT:", ignoreCase = true) ||
                t.startsWith("[FACT_EXTRACTION]", ignoreCase = true) ||
                t.startsWith("NONE", ignoreCase = true) && t.length <= 6
        }

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