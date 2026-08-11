package com.coxy.ghost.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Room DAO for the [MemoryEntry] store. All queries are suspend → run on Room's executor (off the
 * engine's single-threaded `llamaDispatcher`). Recall pulls every row (the store is bounded to
 * ~[MemoryStore.MAX_TURNS], so a full-table cosine scan is cheap); eviction is a single SQL delete
 * ordered by the denormalised [MemoryEntry.importance] column.
 */
@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("SELECT * FROM memory ORDER BY tsMs DESC")
    suspend fun all(): List<MemoryEntry>

    @Query("SELECT * FROM memory ORDER BY tsMs DESC LIMIT :n")
    suspend fun recent(n: Int): List<MemoryEntry>

    @Query("SELECT COUNT(*) FROM memory")
    suspend fun count(): Int

    @Query("UPDATE memory SET recallCount = recallCount + 1 WHERE id IN (:ids)")
    suspend fun bumpRecall(ids: List<Long>)

    @Query("UPDATE memory SET pinned = :p WHERE id = :id")
    suspend fun setPinned(id: Long, p: Boolean)

    @Query("UPDATE memory SET importance = :score WHERE id = :id")
    suspend fun setImportance(id: Long, score: Double)

    /** Delete the `n` lowest-importance non-pinned rows (the retention eviction). */
    @Query(
        "DELETE FROM memory WHERE id IN " +
            "(SELECT id FROM memory WHERE pinned = 0 ORDER BY importance ASC LIMIT :n)"
    )
    suspend fun evictLowest(n: Int)

    @Query("SELECT id, tsMs, recallCount, pinned FROM memory WHERE pinned = 0")
    suspend fun evictableSnapshots(): List<Snapshot>

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Minimal projection for importance recompute (avoid loading the embedding blob). */
data class Snapshot(val id: Long, val tsMs: Long, val recallCount: Int, val pinned: Boolean)