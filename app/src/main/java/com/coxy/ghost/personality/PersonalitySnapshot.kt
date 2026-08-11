package com.coxy.ghost.personality

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A point-in-time snapshot of the active [PersonalityProfile] (Phase 6.3 versioning/rollback).
 *
 * Snapshots are taken on explicit saves and on a schedule. The full profile is stored as JSON so a
 * rollback is a single deserialization + upsert back into [personality_profile], independent of any
 * future schema changes to the active profile table.
 */
@Entity(tableName = "personality_snapshot")
data class PersonalitySnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long = 1,
    val reason: String,
    val serializedProfile: String,
    val createdMs: Long = System.currentTimeMillis()
)
