package com.coxy.ghost.memory

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * The retention score decides which memories are destroyed when the store is over cap, and it moved
 * from a Kotlin loop into a single SQL UPDATE. Room validates that SQL's syntax at compile time but
 * not its arithmetic, so these tests pin the SQL to the same formula the Kotlin used:
 * `1 / (1 + ageDays) + 0.15 * recallCount`, for unpinned rows only.
 */
@RunWith(AndroidJUnit4::class)
class MemoryRetentionTest {

    private lateinit var context: Context
    private lateinit var dao: MemoryDao

    private val recallWeight = 0.15
    private val dayMs = 86_400_000L

    /** The formula as MemoryStore.recencyScore expresses it, for comparison against the SQL. */
    private fun expected(nowMs: Long, tsMs: Long, recallCount: Int): Double {
        val ageDays = max(0.0, (nowMs - tsMs) / 86_400_000.0)
        return 1.0 / (1.0 + ageDays) + recallWeight * recallCount
    }

    private fun entry(tsMs: Long, recallCount: Int = 0, pinned: Boolean = false) = MemoryEntry(
        role = "user",
        content = "retention probe ts=$tsMs rc=$recallCount",
        tsMs = tsMs,
        embedding = ByteArray(0),
        importance = -1.0,
        recallCount = recallCount,
        pinned = pinned,
        source = "typed"
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dao = GhostDatabase.get(context).dao()
        clear()
    }

    @After
    fun tearDown() = clear()

    private fun clear() = runBlocking {
        for (e in dao.all()) dao.delete(e.id)
    }

    @Test
    fun refreshImportance_matchesTheKotlinFormula() = runBlocking {
        val now = System.currentTimeMillis()
        val cases = listOf(
            Triple(now, 0, "fresh, never recalled"),
            Triple(now - dayMs, 0, "one day old"),
            Triple(now - 30 * dayMs, 0, "a month old"),
            Triple(now - 30 * dayMs, 7, "old but frequently recalled"),
            Triple(now + dayMs, 0, "clock skew — future timestamp must clamp, not go negative")
        )
        for ((ts, rc, _) in cases) dao.insert(entry(ts, rc))

        dao.refreshImportance(now, recallWeight)

        val rows = dao.all().associateBy { it.tsMs to it.recallCount }
        for ((ts, rc, label) in cases) {
            val row = rows[ts to rc] ?: error("row missing for $label")
            assertEquals(label, expected(now, ts, rc), row.importance, 1e-9)
        }
    }

    @Test
    fun refreshImportance_rankingSurvivesTheMove() = runBlocking {
        val now = System.currentTimeMillis()
        val chitchat = dao.insert(entry(now, recallCount = 0))                 // fresh, never recalled
        val treasured = dao.insert(entry(now - 30 * dayMs, recallCount = 7))   // old, recalled often

        dao.refreshImportance(now, recallWeight)

        val byId = dao.all().associateBy { it.id }
        assertTrue(
            "a frequently-recalled old memory must outrank fresh chitchat, or eviction destroys the wrong thing",
            byId.getValue(treasured).importance > byId.getValue(chitchat).importance
        )
    }

    @Test
    fun refreshImportance_leavesPinnedRowsAlone() = runBlocking {
        val now = System.currentTimeMillis()
        val pinned = dao.insert(entry(now - 5 * dayMs, recallCount = 0, pinned = true))

        dao.refreshImportance(now, recallWeight)

        assertEquals(
            "pinned rows are excluded by WHERE pinned = 0 and must keep their stored importance",
            -1.0,
            dao.all().first { it.id == pinned }.importance,
            1e-9
        )
    }

    @Test
    fun embeddings_projectionSkipsRowsWithoutAVector() = runBlocking {
        dao.insert(entry(System.currentTimeMillis()))                                   // empty embedding
        val withVector = dao.insert(
            entry(System.currentTimeMillis()).copy(embedding = ByteArray(1536) { 1 })
        )

        val ids = dao.embeddings().map { it.id }
        assertEquals("only the row with a usable vector is recallable", listOf(withVector), ids)
    }

    @Test
    fun byIds_returnsExactlyTheRequestedRows() = runBlocking {
        val now = System.currentTimeMillis()
        val a = dao.insert(entry(now))
        val b = dao.insert(entry(now - dayMs))
        dao.insert(entry(now - 2 * dayMs))

        val fetched = dao.byIds(listOf(a, b)).map { it.id }.sorted()
        assertEquals(listOf(a, b).sorted(), fetched)
    }
}
