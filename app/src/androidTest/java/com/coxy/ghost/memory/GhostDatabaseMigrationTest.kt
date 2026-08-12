package com.coxy.ghost.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations had no test, and they are the one piece of code that can destroy a user's existing
 * data on upgrade rather than merely misbehave.
 *
 * `MigrationTestHelper` needs an exported schema for the version being migrated *from*, and v1 and
 * v2 shipped with `exportSchema = false`, so they cannot be reconstructed. Instead this builds a
 * real v1 database by hand — the `memory` DDL is taken verbatim from Room's own exported v3 schema,
 * where that table is unchanged — puts a row in it, then opens it through Room with the real
 * migrations attached.
 *
 * That open is the assertion: Room runs 1→2→3 and then validates the resulting schema against what
 * it expects for v3, throwing if a migration produced anything different. The data check on top
 * proves the migrations are additive and do not drop the memory the user already had.
 */
@RunWith(AndroidJUnit4::class)
class GhostDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration_probe.db"

    /** Verbatim from app/schemas/com.coxy.ghost.memory.GhostDatabase/3.json — `memory` is unchanged since v1. */
    private val memoryV1Ddl = """
        CREATE TABLE IF NOT EXISTS `memory` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `role` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `tsMs` INTEGER NOT NULL,
            `embedding` BLOB NOT NULL,
            `importance` REAL NOT NULL,
            `recallCount` INTEGER NOT NULL,
            `pinned` INTEGER NOT NULL,
            `source` TEXT NOT NULL
        )
    """.trimIndent()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteProbe()
    }

    @After
    fun tearDown() = deleteProbe()

    private fun deleteProbe() {
        context.deleteDatabase(dbName)
    }

    /** A database as it existed at schema v1: the memory table only, and one turn already stored. */
    private fun createV1WithOneMemory() {
        val path = context.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(memoryV1Ddl)
        db.execSQL(
            "INSERT INTO memory (role, content, tsMs, embedding, importance, recallCount, pinned, source) " +
                "VALUES ('user', 'a memory from before the upgrade', 1000, X'00', 0.5, 2, 0, 'typed')"
        )
        db.version = 1
        db.close()
    }

    private fun openThroughRoom(): GhostDatabase =
        Room.databaseBuilder(context, GhostDatabase::class.java, dbName)
            .addMigrations(GhostDatabase.MIGRATION_1_2, GhostDatabase.MIGRATION_2_3)
            .build()

    @Test
    fun migratesFromV1ToCurrentAndKeepsExistingMemory() = runBlocking {
        createV1WithOneMemory()

        val db = openThroughRoom()
        try {
            // Touching the DAO forces the open, the 1→2→3 migrations, and Room's schema validation.
            assertEquals("the pre-upgrade turn must survive", 1, db.dao().count())
            val survivor = db.dao().all().single()
            assertEquals("a memory from before the upgrade", survivor.content)
            assertEquals(2, survivor.recallCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun migratedDatabaseHasWorkingPersonalityAndCloudTables() = runBlocking {
        createV1WithOneMemory()

        val db = openThroughRoom()
        try {
            // The tables MIGRATION_1_2 and MIGRATION_2_3 create must be usable, not merely present —
            // a CREATE TABLE that Room accepts can still have the wrong columns for its own queries.
            assertTrue("personality_profile unusable after migration", db.personalityDao().activeProfile() == null)
            // Exercises personality_snapshot and the (profileId, createdMs DESC) index the migration builds.
            assertTrue("personality_snapshot unusable after migration", db.personalityDao().recentSnapshots().isEmpty())
            assertEquals("cloud log should start empty", 0, db.cloudDao().count())
        } finally {
            db.close()
        }
    }

    @Test
    fun openingAFreshDatabaseNeedsNoMigration() = runBlocking {
        // The other half of the contract: a new install creates v3 directly.
        val db = openThroughRoom()
        try {
            assertEquals(0, db.dao().count())
            assertEquals(0, db.cloudDao().count())
        } finally {
            db.close()
        }
    }
}
