package com.coxy.ghost.memory

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.coxy.ghost.EngineGate
import com.coxy.ghost.GhostPrefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phase 6.2 async fact-extraction layer.
 *
 * After a user/assistant exchange completes, [extract] runs one short inference pass that asks the
 * model to list durable facts worth remembering long-term. Each extracted fact is persisted as a
 * `MemoryEntry` with `source="fact"` so it can be recalled semantically alongside turns.
 *
 * This is intentionally gated and cheap-skipped:
 *  - Disabled entirely via [GhostPrefs.factExtractionEnabled].
 *  - A content heuristic skips greetings/chitchat (no engine call).
 *  - The extraction prompt caps the response at 128 tokens.
 *
 * The extractor respects [EngineGate] because it uses the single-threaded [InferenceEngine]. It
 * launches from a process-surviving scope so it outlives the Activity.
 */
class FactExtractor(
    private val context: Context,
    private val engine: InferenceEngine,
    private val memoryStore: MemoryStore
) {

    /**
     * Extract facts from a completed exchange and store them. Runs the engine call on a
     * process-surviving scope so it survives Activity destruction. If the engine is busy with another
     * user query, the extraction is skipped (logged) rather than queued indefinitely.
     */
    fun extract(userMsg: String, assistantReply: String, source: String = "typed") {
        GlobalScope.launch {
            extractSuspending(userMsg, assistantReply, source)
        }
    }

    /** Suspending implementation; public so instrumented tests can await completion. */
    suspend fun extractSuspending(userMsg: String, assistantReply: String, source: String) {
        if (userMsg.isBlank() || assistantReply.isBlank()) return

        if (!GhostPrefs.isFactExtractionEnabled(context)) {
            Log.d(TAG, "fact extraction disabled — skipping")
            return
        }

        if (!shouldExtract(userMsg, assistantReply)) {
            Log.d(TAG, "exchange does not look fact-bearing — skipping extraction")
            return
        }

        if (!EngineGate.tryAcquire()) {
            Log.i(TAG, "engine busy — fact extraction skipped")
            return
        }

        val start = System.currentTimeMillis()
        try {
            val prompt = buildExtractionPrompt(userMsg, assistantReply)
            val sb = StringBuilder()
            engine.sendUserPrompt(prompt, PREDICT_LENGTH).collect { token ->
                sb.append(token)
            }
            val raw = sb.toString().trim()
            val duration = System.currentTimeMillis() - start
            Log.i(TAG, "extraction took ${duration}ms, raw=${raw.take(80)}")

            val facts = parseFacts(raw)
            if (facts.isEmpty()) return

            for (fact in facts) {
                memoryStore.remember("assistant", fact, "fact")
            }
            memoryStore.prune()
            Log.i(TAG, "stored ${facts.size} fact(s) from $source exchange")
        } catch (e: Exception) {
            Log.w(TAG, "fact extraction failed", e)
        } finally {
            EngineGate.release()
        }
    }

    private fun buildExtractionPrompt(userMsg: String, assistantReply: String): String =
        """[FACT_EXTRACTION]
Extract durable facts from this exchange that GHOST should remember long-term.
Skip greetings, chitchat, and transient details.
If no durable facts, output exactly NONE.
Otherwise output one fact per line, prefixed with "FACT: ".

user: $userMsg
assistant: $assistantReply""".trimIndent()

    /**
     * Heuristic filter: only pay the inference cost for exchanges likely to contain durable facts.
     * Triggers on first-person statements, preference/commitment markers, or substantive length.
     */
    private fun shouldExtract(userMsg: String, assistantReply: String): Boolean {
        val combined = "$userMsg $assistantReply"
        val lowerUser = userMsg.lowercase()
        val lowerCombined = combined.lowercase()

        // First-person / identity markers: only in the user's text. The assistant says "I" all
        // the time ("I can help"), so checking the combined text would false-trigger on chitchat.
        val selfRegexes = listOf(
            Regex("\\bmy\\b"), Regex("\\bi\\b"), Regex("\\bi'm\\b"), Regex("\\bi’ve\\b"),
            Regex("\\bi am\\b"), Regex("\\bmyself\\b"), Regex("\\bname is\\b")
        )
        // Preference / commitment markers: primarily user, but also look at combined text for
        // phrases like "I work at" in the assistant echo.
        val prefMarkers = listOf(
            "favorite", "like", "dislike", "hate", "love", "prefer", "don’t want",
            "don't want", "remember", "remind", "don’t forget", "don't forget",
            "schedule", "birthday", "deadline", "work at", "live in", "live at"
        )

        if (selfRegexes.any { it.containsMatchIn(lowerUser) }) return true
        if (prefMarkers.any { lowerCombined.contains(it) }) return true

        // Substantive exchanges are more likely to contain facts than one-word greetings.
        val wordCount = combined.trim().split(Regex("\\s+")).size
        if (wordCount >= MIN_WORDS_FOR_EXTRACTION) return true

        return false
    }

    private fun parseFacts(response: String): List<String> {
        if (response.isBlank() || response.equals("NONE", ignoreCase = true)) return emptyList()
        return response.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf(FACT_PREFIX, ignoreCase = true)
                if (idx >= 0) {
                    line.substring(idx + FACT_PREFIX.length).trim()
                        .removePrefix("-") // tolerate "FACT: - fact text"
                        .trim()
                        .takeIf { it.isNotBlank() }
                } else null
            }
            .toList()
    }

    companion object {
        private const val TAG = "FactExtractor"
        private const val PREDICT_LENGTH = 128
        private const val MIN_WORDS_FOR_EXTRACTION = 25
        private const val FACT_PREFIX = "FACT:"
    }
}
