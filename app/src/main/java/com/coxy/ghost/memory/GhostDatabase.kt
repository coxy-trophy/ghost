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
        com.coxy.ghost.cloud.CloudInteraction::class,
        com.coxy.ghost.net.KnownNetwork::class,
        com.coxy.ghost.net.KnownDevice::class
    ],
    version = 4,
    // Exported to app/schemas so future migrations can be diffed and tested against the version
    // they migrate from. v1 and v2 predate this and cannot be reconstructed.
    exportSchema = true
)
abstract class GhostDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao
    abstract fun personalityDao(): com.coxy.ghost.personality.PersonalityDao
    abstract fun cloudDao(): com.coxy.ghost.cloud.CloudInteractionDao
    abstract fun networkDao(): com.coxy.ghost.net.NetworkDao

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
        /**
         * v3 → v4: remember networks and what was on them.
         *
         * The SQL matches Room's generated schema exactly — column order, nullability, defaults and
         * both indexes — because migration validation compares them field by field and a mismatch
         * fails at open time on a device holding real data rather than at build time here.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `known_network` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `gatewayMac` TEXT NOT NULL,
                        `ssid` TEXT,
                        `nickname` TEXT,
                        `subnet` TEXT NOT NULL,
                        `firstSeenMs` INTEGER NOT NULL,
                        `lastSeenMs` INTEGER NOT NULL,
                        `scanCount` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_known_network_gatewayMac` " +
                        "ON `known_network` (`gatewayMac`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `known_device` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `networkId` INTEGER NOT NULL,
                        `stableKey` TEXT NOT NULL,
                        `ip` TEXT NOT NULL,
                        `mac` TEXT,
                        `name` TEXT,
                        `nickname` TEXT,
                        `kind` TEXT,
                        `services` TEXT NOT NULL,
                        `descriptionUrls` TEXT NOT NULL,
                        `firstSeenMs` INTEGER NOT NULL,
                        `lastSeenMs` INTEGER NOT NULL,
                        `timesSeen` INTEGER NOT NULL,
                        `paired` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_known_device_networkId_stableKey` " +
                        "ON `known_device` (`networkId`, `stableKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_known_device_networkId` " +
                        "ON `known_device` (`networkId`)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
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

        /**
         * The SQL below is Room's own exported DDL, copied verbatim from
         * `app/schemas/.../3.json`, and must stay that way.
         *
         * It originally carried `DEFAULT` clauses on every column and created an extra index on
         * `personality_snapshot`. The entities declare neither, so Room's post-migration validation
         * compared what it expected against what it found and threw
         * `IllegalStateException: Migration didn't properly handle: personality_snapshot` — every
         * device holding a v1 database would have crashed on open. It went unnoticed because fresh
         * installs build v3 directly and never run this path. Caught 2026-08-12 by
         * `GhostDatabaseMigrationTest`, the first test this migration ever had.
         *
         * `MIGRATION_2_3` already noted this rule for itself; it was never applied back here.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personality_profile` (" +
                        "`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `humor` INTEGER NOT NULL, " +
                        "`sarcasm` INTEGER NOT NULL, `bluntness` INTEGER NOT NULL, `warmth` INTEGER NOT NULL, " +
                        "`formality` INTEGER NOT NULL, `verbosity` INTEGER NOT NULL, `curiosity` INTEGER NOT NULL, " +
                        "`initiative` INTEGER NOT NULL, `humorMin` INTEGER NOT NULL, `humorMax` INTEGER NOT NULL, " +
                        "`sarcasmMin` INTEGER NOT NULL, `sarcasmMax` INTEGER NOT NULL, " +
                        "`bluntnessMin` INTEGER NOT NULL, `bluntnessMax` INTEGER NOT NULL, " +
                        "`warmthMin` INTEGER NOT NULL, `warmthMax` INTEGER NOT NULL, " +
                        "`formalityMin` INTEGER NOT NULL, `formalityMax` INTEGER NOT NULL, " +
                        "`verbosityMin` INTEGER NOT NULL, `verbosityMax` INTEGER NOT NULL, " +
                        "`curiosityMin` INTEGER NOT NULL, `curiosityMax` INTEGER NOT NULL, " +
                        "`initiativeMin` INTEGER NOT NULL, `initiativeMax` INTEGER NOT NULL, " +
                        "`updatedMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personality_snapshot` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, " +
                        "`reason` TEXT NOT NULL, `serializedProfile` TEXT NOT NULL, " +
                        "`createdMs` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): GhostDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GhostDatabase::class.java,
                "ghost_memory.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}