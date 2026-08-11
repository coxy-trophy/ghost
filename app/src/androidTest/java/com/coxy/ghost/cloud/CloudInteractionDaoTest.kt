package com.coxy.ghost.cloud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coxy.ghost.memory.GhostDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 Room persistence tests for the cloud-interaction log.
 */
@RunWith(AndroidJUnit4::class)
class CloudInteractionDaoTest {

    private lateinit var context: Context
    private lateinit var dao: CloudInteractionDao

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dao = GhostDatabase.get(context).cloudDao()
        runBlocking { dao.deleteOlderThan(System.currentTimeMillis() + 1) }
    }

    @Test
    fun insert_and_recent_roundTrip() = runBlocking {
        val row = CloudInteraction(
            requestedMs = 1_000_000,
            modelUsed = "deepseek-v4-pro:cloud",
            prompt = "user prompt",
            response = "assistant reply",
            wasFallback = false,
            latencyMs = 1234,
            privacyMode = "FULL"
        )
        val id = dao.insert(row)
        assertTrue("id should be positive", id > 0)

        val recent = dao.recent(5)
        assertEquals(1, recent.size)
        assertEquals("deepseek-v4-pro:cloud", recent[0].modelUsed)
        assertEquals("assistant reply", recent[0].response)
    }

    @Test
    fun deleteOlderThan_removes_old_rows() = runBlocking {
        val now = System.currentTimeMillis()
        val old = CloudInteraction(requestedMs = 1000, modelUsed = "old", prompt = "", response = "", wasFallback = true, latencyMs = -1, privacyMode = "FULL")
        val recent = CloudInteraction(requestedMs = now, modelUsed = "new", prompt = "", response = "", wasFallback = false, latencyMs = 1, privacyMode = "FULL")
        dao.insert(old)
        dao.insert(recent)

        dao.deleteOlderThan(now - 60_000)

        val rows = dao.recent(10)
        assertEquals(1, rows.size)
        assertEquals("new", rows[0].modelUsed)
    }
}
