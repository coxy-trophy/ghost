package com.coxy.ghost.memory

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6.2 instrumented tests for the async fact-extraction layer.
 *
 * Uses a fake [InferenceEngine] so no real Qwen model needs to be loaded. Verifies:
 *  1. The content heuristic correctly triggers on fact-bearing text and skips chitchat.
 *  2. The parser extracts facts from model output and ignores NONE/empty responses.
 *  3. [FactExtractor] stores extracted facts as recallable `source="fact"` memory entries.
 */
@RunWith(AndroidJUnit4::class)
class FactExtractorTest {

    private lateinit var context: Context
    private lateinit var store: MemoryStore
    private lateinit var fakeEngine: FakeInferenceEngine
    private lateinit var extractor: FactExtractor

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Embedder.init(context)
        store = MemoryStore(context)
        fakeEngine = FakeInferenceEngine()
        extractor = FactExtractor(context, fakeEngine, store)
        runBlocking {
            for (e in store.recent(Int.MAX_VALUE)) GhostDatabase.get(context).dao().delete(e.id)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            for (e in store.recent(Int.MAX_VALUE)) GhostDatabase.get(context).dao().delete(e.id)
        }
    }

    @Test
    fun heuristic_triggers_on_fact_bearing_exchange() = runBlocking {
        val user = "My favorite food is jollof rice."
        val assistant = "Got it — jollof rice is your favorite food."
        fakeEngine.setNextResponse(
            "FACT: The user's favorite food is jollof rice.\n" +
                "FACT: The user enjoys West African cuisine."
        )
        extractor.extractSuspending(user, assistant, "test")

        val facts = store.recent(10).filter { it.source == "fact" }
        assertEquals("expected 2 extracted facts", 2, facts.size)
        assertTrue("fact should mention jollof rice", facts.any { it.content.contains("jollof rice") })
    }

    @Test
    fun heuristic_skips_greeting_chitchat() = runBlocking {
        val user = "Hi there!"
        val assistant = "Hello! How can I help you today?"
        // Even if a fake engine is configured, the heuristic should skip before any engine call.
        fakeEngine.setNextResponse("FACT: greeting happened")
        extractor.extractSuspending(user, assistant, "test")

        val facts = store.recent(10).filter { it.source == "fact" }
        assertTrue("greeting should not trigger fact extraction", facts.isEmpty())
    }

    @Test
    fun parser_handles_none_and_malformed_lines() = runBlocking {
        val user = "I live in Lagos and I work as a developer."
        val assistant = "Noted."
        fakeEngine.setNextResponse(
            "NONE\n" +
                "Some unrelated line\n" +
                "FACT: The user lives in Lagos\n" +
                "fact: the user works as a developer" // case-insensitive prefix
        )
        extractor.extractSuspending(user, assistant, "test")

        val facts = store.recent(10).filter { it.source == "fact" }
        assertEquals("expected 2 parsed facts", 2, facts.size)
        assertTrue(facts.any { it.content.contains("Lagos") })
        assertTrue(facts.any { it.content.contains("developer") })
    }

    @Test
    fun extracted_fact_is_recallable() = runBlocking {
        val user = "Remember that my car is a blue Honda Civic."
        val assistant = "I'll remember that your car is a blue Honda Civic."
        fakeEngine.setNextResponse("FACT: The user's car is a blue Honda Civic.")
        extractor.extractSuspending(user, assistant, "test")

        val hits = store.recall("what car does the user drive", topK = 3)
        assertTrue("expected a recalled fact", hits.isNotEmpty())
        assertTrue("recalled fact should mention Honda Civic", hits.any { it.content.contains("Honda Civic") })
    }

    /** Minimal fake engine for testing fact extraction without loading a real model. */
    private class FakeInferenceEngine : InferenceEngine {
        private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.ModelReady)
        override val state: StateFlow<InferenceEngine.State> = _state
        private var nextResponse = ""

        fun setNextResponse(response: String) {
            nextResponse = response
        }

        override suspend fun loadModel(pathToModel: String) {}
        override suspend fun setSystemPrompt(systemPrompt: String) {}
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = ""
        override fun cleanUp() {}
        override fun destroy() {}

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            for (token in tokenize(nextResponse)) emit(token)
        }

        private fun tokenize(text: String): List<String> {
            // Emit word-by-word plus newlines so the collector sees realistic token boundaries.
            return text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        }
    }
}
