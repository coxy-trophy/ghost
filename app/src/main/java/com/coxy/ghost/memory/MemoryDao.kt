package com.coxy.ghost.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Room DAO for the [MemoryEntry] store. All queries are suspend → run on Room's executor (off the
 * engine's single-threaded `llamaDispatcher`).
 *
 * Recall scores every row, but via the [embeddings] projection rather than whole entities: at the
 * ~1000-row cap the content strings are dead weight on a query that only needs the vectors. The
 * winners are then fetched by id. Eviction is a single SQL delete ordered by the denormalised
 * [MemoryEntry.importance] column, which [refreshImportance] recomputes in one statement.
 */
@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("SELECT * FROM memory ORDER BY tsMs DESC")
    suspend fun all(): List<MemoryEntry>

    /**
     * id + embedding for every recallable row. Rows stored without an embedding (the embedder was
     * unavailable at write time) can never match, so they are filtered in SQL rather than loaded
     * and skipped in Kotlin.
     */
    @Query("SELECT id, embedding FROM memory WHERE LENGTH(embedding) > 0")
    suspend fun embeddings(): List<EmbeddingRow>

    @Query("SELECT * FROM memory WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MemoryEntry>

    @Query("SELECT * FROM memory ORDER BY tsMs DESC LIMIT :n")
    suspend fun recent(n: Int): List<MemoryEntry>

    @Query("SELECT COUNT(*) FROM memory")
    suspend fun count(): Int

    @Query("UPDATE memory SET recallCount = recallCount + 1 WHERE id IN (:ids)")
    suspend fun bumpRecall(ids: List<Long>)

    @Query("UPDATE memory SET pinned = :p WHERE id = :id")
    suspend fun setPinned(id: Long, p: Boolean)

    /**
     * Recompute importance for every evictable row in one statement, so the eviction order reflects
     * current recency and recall frequency. This mirrors `MemoryStore.recencyScore` —
     * `1 / (1 + ageDays)` — plus the recall weight; the two must be kept in step.
     *
     * Previously this was a Kotlin loop issuing one UPDATE per row, un-batched and outside a
     * transaction: up to 1000 round-trips whenever the cap was exceeded.
     */
    @Query(
        "UPDATE memory SET importance = " +
            "1.0 / (1.0 + MAX(0, :nowMs - tsMs) / 86400000.0) + :recallWeight * recallCount " +
            "WHERE pinned = 0"
    )
    suspend fun refreshImportance(nowMs: Long, recallWeight: Double)

    /** Delete the `n` lowest-importance non-pinned rows (the retention eviction). */
    @Query(
        "DELETE FROM memory WHERE id IN " +
            "(SELECT id FROM memory WHERE pinned = 0 ORDER BY importance ASC LIMIT :n)"
    )
    suspend fun evictLowest(n: Int)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Scoring projection for [MemoryDao.embeddings] — the vector without the row it belongs to. */
data class EmbeddingRow(val id: Long, val embedding: ByteArray) {
    // ByteArray in a data class gets identity equals/hashCode, which would be a trap for callers.
    override fun equals(other: Any?): Boolean =
        this === other || (other is EmbeddingRow && id == other.id && embedding.contentEquals(other.embedding))

    override fun hashCode(): Int = 31 * id.hashCode() + embedding.contentHashCode()
}