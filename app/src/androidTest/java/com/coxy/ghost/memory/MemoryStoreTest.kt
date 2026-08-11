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

/**
 * Phase 6.1 instrumented gate test for the on-device memory pipeline.
 *
 * Runs in the app process, so it can reach [Embedder] + [GhostDatabase] directly — no LLM and no
 * UI required. Verifies:
 *  1. The TFLite embedder initializes and emits a normalised 384-dim vector.
 *  2. [MemoryStore.remember] persists turns with embeddings.
 *  3. [MemoryStore.recall] returns semantically related past turns (cosine dot product > 0.20).
 *  4. Recall bumps the recall count.
 *  5. Importance-weighted pruning keeps the store bounded to MAX_TURNS.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreTest {

    private lateinit var context: Context
    private lateinit var store: MemoryStore

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Embedder.init(context)
        store = MemoryStore(context)
        // Start with a clean slate for deterministic assertions.
        runBlocking {
            for (e in store.recent(Int.MAX_VALUE)) {
                GhostDatabase.get(context).dao().delete(e.id)
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            for (e in store.recent(Int.MAX_VALUE)) {
                GhostDatabase.get(context).dao().delete(e.id)
            }
        }
    }

    @Test
    fun embedder_initializes_and_normalizes() {
        assertTrue("Embedder failed to initialize", Embedder.isReady())
        val emb = runBlocking { Embedder.embed("test sentence") }
        assertTrue("embedding should not be null", emb != null)
        assertEquals("MiniLM output dimension", 384, emb!!.size)
        var sum = 0.0
        for (f in emb) sum += (f * f).toDouble()
        assertEquals("L2-normalised vector", 1.0, sum, 1e-3)
    }

    @Test
    fun remember_and_recall_roundTrip() = runBlocking {
        store.remember("user", "My favorite food is jollof rice.", "test")
        store.remember("assistant", "Got it — jollof rice is your favorite food.", "test")

        val hits = store.recall("what is my favorite food", topK = 3)
        assertTrue("expected a related memory", hits.isNotEmpty())

        val userHit = hits.find { it.role == "user" }
        assertTrue("expected the user turn to be recalled", userHit != null)
        assertTrue(
            "recalled memory should mention jollof rice",
            userHit!!.content.contains("jollof rice", ignoreCase = true)
        )

        // Recall count must have been bumped for the winner.
        val freshUser = GhostDatabase.get(context).dao().recent(10).find { it.role == "user" }!!
        assertTrue("recallCount should be > 0 after recall", freshUser.recallCount > 0)
    }

    @Test
    fun prune_keeps_store_bounded() = runBlocking {
        // Seed enough turns to exceed the 1000 cap.
        repeat(1050) { i ->
            store.remember("user", "filler message number $i for pruning test", "prune-test")
        }
        val before = store.count()
        assertTrue("seed count should exceed cap", before > 1000)

        store.prune()
        val after = store.count()
        assertTrue("prune should reduce store to cap", after <= 1000)
    }

    @Test
    fun unrelated_query_returns_empty() = runBlocking {
        store.remember("user", "My car is a blue Honda Civic.", "test")
        val hits = store.recall("quantum computing lecture notes", topK = 3)
        assertTrue("unrelated query should not match", hits.isEmpty())
    }

    @Test
    fun remember_strips_ghost_prefix() = runBlocking {
        store.remember("assistant", "Ghost: I'm a girl.", "test")
        val recent = store.recent(1)
        assertEquals(1, recent.size)
        assertEquals("I'm a girl.", recent[0].content)
    }

    @Test
    fun stripGhostPrefix_helper_cases() {
        assertEquals("hi", MemoryStore.stripGhostPrefix("Ghost: hi"))
        assertEquals("hi", MemoryStore.stripGhostPrefix("  ghost:  hi"))
        assertEquals("hi", MemoryStore.stripGhostPrefix("GHOST:hi"))
        assertEquals("not: me", MemoryStore.stripGhostPrefix("not: me"))
        assertEquals("Ghost without colon", MemoryStore.stripGhostPrefix("Ghost without colon"))
    }

    @Test
    fun stripGhostPrefixesFromDb_cleans_existing_rows() = runBlocking {
        // Seed a prefixed row directly so it simulates legacy DB content that remember() would now strip.
        val dao = GhostDatabase.get(context).dao()
        dao.insert(
            MemoryEntry(
                role = "assistant",
                content = "Ghost: legacy reply",
                tsMs = System.currentTimeMillis(),
                embedding = ByteArray(0),
                source = "test"
            )
        )
        val changed = store.stripGhostPrefixesFromDb()
        assertEquals(1, changed)
        val contents = store.recent(10).map { it.content }
        assertTrue(contents.none { it.startsWith("Ghost:", ignoreCase = true) })
        assertTrue(contents.contains("legacy reply"))
    }
}
