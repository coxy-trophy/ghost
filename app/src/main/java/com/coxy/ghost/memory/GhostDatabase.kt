package com.coxy.ghost.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The persistent memory database (Phase 6.1). Lives in the app's internal storage (default Room
 * location) → survives process death, unlike the in-memory chat list in [com.coxy.ghost.MainActivity].
 * That persistence across kills/reboots is the whole point of this phase.
 *
 * Singleton: the engine, the Activity, and the service all share one process-wide instance.
 */
@Database(
    entities = [
        MemoryEntry::class,
        com.coxy.ghost.personality.PersonalityProfile::class,
        com.coxy.ghost.personality.PersonalitySnapshot::class,
        com.coxy.ghost.cloud.CloudInteraction::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GhostDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao
    abstract fun personalityDao(): com.coxy.ghost.personality.PersonalityDao
    abstract fun cloudDao(): com.coxy.ghost.cloud.CloudInteractionDao

    companion object {
        @Volatile private var instance: GhostDatabase? = null

        /**
         * Phase 6.3 migration from v1 → v2: add the personality profile + snapshot tables.
         * Existing memory rows are preserved; the active profile is created lazily by the DAO.
         */
        /**
         * Phase 8 migration from v2 → v3: add the cloud_interaction log table. The SQL matches the
         * Room-generated schema exactly (no defaults, no extra indexes) so migration validation passes.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_interaction` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `requestedMs` INTEGER NOT NULL,
                        `modelUsed` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `response` TEXT NOT NULL,
                        `wasFallback` INTEGER NOT NULL,
                        `latencyMs` INTEGER NOT NULL,
                        `privacyMode` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personality_profile (
                        id INTEGER PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL DEFAULT 'default',
                        humor INTEGER NOT NULL DEFAULT 50,
                        sarcasm INTEGER NOT NULL DEFAULT 50,
                        bluntness INTEGER NOT NULL DEFAULT 50,
                        warmth INTEGER NOT NULL DEFAULT 50,
                        formality INTEGER NOT NULL DEFAULT 50,
                        verbosity INTEGER NOT NULL DEFAULT 50,
                        curiosity INTEGER NOT NULL DEFAULT 50,
                        initiative INTEGER NOT NULL DEFAULT 50,
                        humorMin INTEGER NOT NULL DEFAULT 0,
                        humorMax INTEGER NOT NULL DEFAULT 100,
                        sarcasmMin INTEGER NOT NULL DEFAULT 0,
                        sarcasmMax INTEGER NOT NULL DEFAULT 100,
                        bluntnessMin INTEGER NOT NULL DEFAULT 0,
                        bluntnessMax INTEGER NOT NULL DEFAULT 100,
                        warmthMin INTEGER NOT NULL DEFAULT 0,
                        warmthMax INTEGER NOT NULL DEFAULT 100,
                        formalityMin INTEGER NOT NULL DEFAULT 0,
                        formalityMax INTEGER NOT NULL DEFAULT 100,
                        verbosityMin INTEGER NOT NULL DEFAULT 0,
                        verbosityMax INTEGER NOT NULL DEFAULT 100,
                        curiosityMin INTEGER NOT NULL DEFAULT 0,
                        curiosityMax INTEGER NOT NULL DEFAULT 100,
                        initiativeMin INTEGER NOT NULL DEFAULT 0,
                        initiativeMax INTEGER NOT NULL DEFAULT 100,
                        updatedMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personality_snapshot (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId INTEGER NOT NULL DEFAULT 1,
                        reason TEXT NOT NULL,
                        serializedProfile TEXT NOT NULL,
                        createdMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_personality_snapshot_profile_created
                    ON personality_snapshot(profileId, createdMs DESC)
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): GhostDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GhostDatabase::class.java,
                "ghost_memory.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}