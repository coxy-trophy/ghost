package com.coxy.ghost.cloud

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.memory.GhostDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Phase 8 routing layer: cloud-first reasoning, local fallback.
 *
 * [route] builds the system prompt, applies the privacy filter, and tries the Ollama Cloud chain.
 * If the key is missing, cloud is disabled, or every cloud model fails, it falls back to the local
 * 1.5B engine via [InferenceEngine.sendUserPrompt]. Either path emits tokens through a single
 * [Flow<String>] so the UI code stays unchanged.
 *
 * Action JSON can be emitted by either model; the caller runs [com.coxy.ghost.action.ActionParser]
 * on the final text regardless of source.
 */
class CloudRouter(
    private val context: Context,
    private val engine: InferenceEngine,
    private val clientFactory: (String) -> CloudClient = { key ->
        OllamaCloudClient(apiKey = key)
    },
    private val keyProvider: () -> String? = { CloudKeyStorage.getKey(context) },
    private val settingsProvider: suspend () -> CloudSettings = { GhostPrefs.cloudSettings(context) }
) {

    /**
     * Generate a reply for [fullPrompt] (the same string that would previously have gone straight to
     * [engine.sendUserPrompt]). Returns a [Flow] of tokens. For cloud, the whole reply is emitted as
     * one token. For local, the native token stream is forwarded unchanged.
     */
    suspend fun route(
        fullPrompt: String,
        systemPrompt: String,
        predictLength: Int = 512
    ): Flow<String> {
        val prefs = settingsProvider()
        // One read: keyProvider hits encrypted storage, and this was calling it twice per query.
        val key = keyProvider()
        if (!prefs.enabled || key.isNullOrBlank()) {
            Log.i(TAG, "cloud disabled or no key — routing to local engine")
            return engine.sendUserPrompt(fullPrompt)
        }
        val filtered = CloudPromptFilter.apply(fullPrompt, prefs.privacyMode)

        return try {
            val client = clientFactory(key)
            var reply = ""
            var wasFallback = false
            val latency = measureTimeMillis {
                client.chat(systemPrompt, filtered, predictLength)
                    .onStart { Log.i(TAG, "Cloud request started (${prefs.privacyMode})") }
                    .onCompletion { e ->
                        if (e != null) {
                            Log.w(TAG, "Cloud request failed, will fall back to local", e)
                            wasFallback = true
                        }
                    }
                    .collect { reply = it }
            }

            if (reply.isNotBlank()) {
                logInteraction(filtered, reply, wasFallback, latency, prefs.privacyMode, prefs.loggingEnabled)
                // Emit the whole cloud reply as a single token so the existing streaming UI works.
                flow { emit(reply) }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            } else {
                Log.w(TAG, "Cloud returned empty reply — falling back to local")
                engine.sendUserPrompt(fullPrompt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud routing failed — falling back to local engine", e)
            engine.sendUserPrompt(fullPrompt)
        }
    }

    private suspend fun logInteraction(
        prompt: String,
        response: String,
        wasFallback: Boolean,
        latencyMs: Long,
        mode: CloudPromptFilter.Mode,
        loggingEnabled: Boolean
    ) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!loggingEnabled) return@withContext
        val modelUsed = if (wasFallback) "local" else OllamaCloudClient.DEFAULT_MODEL_CHAIN.firstOrNull() ?: "unknown"
        val row = CloudInteraction(
            requestedMs = System.currentTimeMillis(),
            modelUsed = modelUsed,
            prompt = prompt.take(MAX_LOG_CHARS),
            response = response.take(MAX_LOG_CHARS),
            wasFallback = wasFallback,
            latencyMs = latencyMs,
            privacyMode = mode.name
        )
        runCatching { GhostDatabase.get(context).cloudDao().insert(row) }
            .onFailure { Log.w(TAG, "Failed to log cloud interaction", it) }
    }

    companion object {
        private const val TAG = "CloudRouter"
        private const val MAX_LOG_CHARS = 8_000
    }
}
