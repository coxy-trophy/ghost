package com.coxy.ghost.cloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Room DAO for the Phase 8 cloud-interaction log.
 */
@Dao
interface CloudInteractionDao {

    @Insert
    suspend fun insert(row: CloudInteraction): Long

    @Query("SELECT * FROM cloud_interaction ORDER BY requestedMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CloudInteraction>

    @Query("SELECT COUNT(*) FROM cloud_interaction")
    suspend fun count(): Int

    @Query("DELETE FROM cloud_interaction WHERE requestedMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
