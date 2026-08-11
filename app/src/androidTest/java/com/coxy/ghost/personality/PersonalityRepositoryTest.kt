package com.coxy.ghost.personality

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coxy.ghost.memory.GhostDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6.3 instrumented gate: verify active profile creation, save/snapshot versioning,
 * and rollback using the Room database on the device.
 */
@RunWith(AndroidJUnit4::class)
class PersonalityRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: PersonalityRepository
    private lateinit var db: GhostDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = GhostDatabase.get(context)
        repo = PersonalityRepository(context)
        clearPersonalityData()
    }

    @After
    fun tearDown() {
        clearPersonalityData()
    }

    private fun clearPersonalityData() = runBlocking {
        db.personalityDao().deleteActiveProfile()
        for (s in db.personalityDao().recentSnapshots(limit = Int.MAX_VALUE)) {
            db.personalityDao().deleteSnapshot(s.id)
        }
    }

    @Test
    fun activeProfile_createsBaselineWhenEmpty() = runBlocking {
        val p = repo.activeProfile()
        assertEquals(1L, p.id)
        assertEquals(50, p.humor)
        assertEquals(50, p.sarcasm)
    }

    @Test
    fun save_clampsAndCreatesSnapshot() = runBlocking {
        val p = PersonalityProfile(
            humor = 95, humorMin = 0, humorMax = 60,
            sarcasm = 5, sarcasmMin = 40, sarcasmMax = 100
        )
        val saved = repo.save(p, reason = "test_save")
        assertEquals(60, saved.humor)
        assertEquals(40, saved.sarcasm)

        val active = repo.activeProfile()
        assertEquals(saved.humor, active.humor)

        val snaps = repo.recentSnapshots(10)
        assertTrue("expected at least one snapshot", snaps.isNotEmpty())
        assertEquals("test_save", snaps.first().reason)
    }

    @Test
    fun resetToBaseline_restoresDefaultsAndSnapshots() = runBlocking {
        repo.save(PersonalityProfile(humor = 99), reason = "noisy")
        val reset = repo.resetToBaseline()
        assertEquals(50, reset.humor)
        assertEquals(50, reset.sarcasm)

        val active = repo.activeProfile()
        assertEquals(50, active.humor)

        val snaps = repo.recentSnapshots(10)
        assertTrue(snaps.any { it.reason == "reset_to_baseline" })
    }

    @Test
    fun rollback_restoresSavedSnapshot() = runBlocking {
        val original = repo.save(PersonalityProfile(humor = 77), reason = "original")
        repo.save(PersonalityProfile(humor = 22), reason = "changed")
        val snapshots = repo.recentSnapshots(10)
        val target = snapshots.first { it.reason == "original" }
        val restored = repo.rollback(target)
        assertEquals(original.humor, restored.humor)

        val active = repo.activeProfile()
        assertEquals(77, active.humor)
    }
}
