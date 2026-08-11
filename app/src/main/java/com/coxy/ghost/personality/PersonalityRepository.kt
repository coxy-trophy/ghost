package com.coxy.ghost.personality

import android.content.Context
import com.coxy.ghost.memory.GhostDatabase

/**
 * Repository for the Phase 6.3 personality subsystem. Wraps [PersonalityDao] and owns the
 * snapshot/rollback lifecycle.
 *
 * All operations are suspend → run on Room's executor (or the caller's dispatcher). The repository
 * is lightweight; callers (GhostService, MainActivity) can create it on demand.
 */
class PersonalityRepository(context: Context) {

    private val dao = GhostDatabase.get(context.applicationContext).personalityDao()

    /** Get the active profile, creating the baseline if none exists yet. */
    suspend fun activeProfile(): PersonalityProfile {
        return dao.activeProfile() ?: run {
            val baseline = PersonalityTranslator.baseline()
            dao.insertProfile(baseline)
            baseline
        }
    }

    /**
     * Save a new active profile. Clamps to drift budget, timestamps it, upserts the active row, and
     * records a snapshot with the given reason. Returns the clamped profile that was actually saved.
     */
    suspend fun save(profile: PersonalityProfile, reason: String = "explicit_save"): PersonalityProfile {
        val clamped = PersonalityTranslator.clamp(profile).copy(updatedMs = System.currentTimeMillis())
        dao.upsert(clamped)
        dao.insertSnapshot(
            PersonalitySnapshot(
                profileId = clamped.id,
                reason = reason,
                serializedProfile = PersonalityTranslator.toJson(clamped)
            )
        )
        return clamped
    }

    /** Reset to the hardcoded baseline and snapshot the reset. */
    suspend fun resetToBaseline(): PersonalityProfile {
        val baseline = PersonalityTranslator.baseline().copy(updatedMs = System.currentTimeMillis())
        dao.upsert(baseline)
        dao.insertSnapshot(
            PersonalitySnapshot(
                profileId = baseline.id,
                reason = "reset_to_baseline",
                serializedProfile = PersonalityTranslator.toJson(baseline)
            )
        )
        return baseline
    }

    /** Recent snapshots, newest first. */
    suspend fun recentSnapshots(limit: Int = 20): List<PersonalitySnapshot> =
        dao.recentSnapshots(profileId = 1L, limit = limit)

    /** Roll back to a snapshot by deserializing it and making it the active profile. */
    suspend fun rollback(snapshot: PersonalitySnapshot): PersonalityProfile {
        val restored = PersonalityTranslator.fromJson(snapshot.serializedProfile)
            .copy(updatedMs = System.currentTimeMillis())
        dao.upsert(restored)
        dao.insertSnapshot(
            PersonalitySnapshot(
                profileId = restored.id,
                reason = "rollback_to_snapshot_${snapshot.id}",
                serializedProfile = PersonalityTranslator.toJson(restored)
            )
        )
        return restored
    }

    /** Save a snapshot with a human-readable name without changing the active profile. */
    suspend fun snapshot(name: String, profile: PersonalityProfile): PersonalityProfile {
        return save(profile, reason = name)
    }

    /** Delete a snapshot. */
    suspend fun deleteSnapshot(snapshot: PersonalitySnapshot) {
        dao.deleteSnapshot(snapshot.id)
    }
}
