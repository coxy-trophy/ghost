package com.coxy.ghost.personality

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Room DAO for the Phase 6.3 personality subsystem.
 *
 * A single active profile is the default (`id = 1`). [PersonalitySnapshot] rows keep version history
 * for rollback. All operations are suspend → they run on Room's executor.
 */
@Dao
interface PersonalityDao {

    @Query("SELECT * FROM personality_profile WHERE id = 1")
    suspend fun activeProfile(): PersonalityProfile?

    @Insert
    suspend fun insertProfile(profile: PersonalityProfile): Long

    @Update
    suspend fun updateProfile(profile: PersonalityProfile): Int

    /**
     * Upsert the active profile. Room's @Update returns 0 if the row doesn't exist; in that case
     * we insert. This keeps default/baseline creation simple.
     */
    suspend fun upsert(profile: PersonalityProfile) {
        if (updateProfile(profile) == 0) {
            insertProfile(profile)
        }
    }

    @Query("DELETE FROM personality_profile WHERE id = 1")
    suspend fun deleteActiveProfile()

    @Insert
    suspend fun insertSnapshot(snapshot: PersonalitySnapshot): Long

    @Query("SELECT * FROM personality_snapshot WHERE profileId = :profileId ORDER BY createdMs DESC LIMIT :limit")
    suspend fun recentSnapshots(profileId: Long = 1, limit: Int = 20): List<PersonalitySnapshot>

    @Query("DELETE FROM personality_snapshot WHERE id = :id")
    suspend fun deleteSnapshot(id: Long)
}
