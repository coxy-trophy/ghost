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

        // These were one log line reading "cloud disabled or no key", which is why GHOST ran on the
        // 1.5B for a long time without it being obvious that the API key had never been stored.
        // Each reason is now distinct, and the chosen brain is published for the UI.
        if (!prefs.enabled) {
            Log.i(TAG, "cloud switched off in settings — using local engine")
            return local(fullPrompt, Brain.LOCAL_DISABLED)
        }
        if (key.isNullOrBlank()) {
            Log.w(TAG, "cloud is ON but no API key is stored — using local engine. " +
                "Store one via the Cloud screen or CloudKeyInjectionTest -e keyFile.")
            return local(fullPrompt, Brain.LOCAL_NO_KEY)
        }
        // Without this, an offline phone waits out a 15s connect timeout per model in the chain
        // before falling back — up to 30s of silence for a query the local engine could have
        // started answering immediately. "Offline fallback" has to actually be fast.
        if (!isOnline()) {
            Log.i(TAG, "no network — using local engine")
            return local(fullPrompt, Brain.LOCAL_OFFLINE)
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
                lastBrain = Brain.CLOUD
                lastModel = client.lastModelUsed
                logInteraction(filtered, reply, wasFallback, latency, prefs.privacyMode, prefs.loggingEnabled)
                // Emit the whole cloud reply as a single token so the existing streaming UI works.
                flow { emit(reply) }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            } else {
                Log.w(TAG, "Cloud returned empty reply — falling back to local")
                local(fullPrompt, Brain.LOCAL_CLOUD_FAILED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud routing failed — falling back to local engine", e)
            local(fullPrompt, Brain.LOCAL_CLOUD_FAILED)
        }
    }

    private fun local(prompt: String, why: Brain): Flow<String> {
        lastBrain = why
        return engine.sendUserPrompt(prompt)
    }

    /**
     * A capabilities check, not a reachability check — it says the phone has a validated internet
     * transport, not that Ollama is up. A cloud call that fails anyway still falls back below.
     */
    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private suspend fun logInteraction(
        prompt: String,
        response: String,
        wasFallback: Boolean,
        latencyMs: Long,
        mode: CloudPromptFilter.Mode,
        loggingEnabled: Boolean
    ) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!loggingEnabled) return@withContext
        // Was reported as chain[0] regardless of which model answered, which mislabels every
        // distillation row whenever the primary fails over.
        val modelUsed = if (wasFallback) "local" else lastModel ?: "unknown"
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
        /**
         * Which brain answered the last query, and if it was the local one, why.
         *
         * Published so the UI can show it. This being invisible is how GHOST ran on a 1.5B for
         * a long stretch while the cloud was believed to be primary.
         */
        enum class Brain(val label: String) {
            CLOUD("cloud"),
            LOCAL_DISABLED("local · cloud off"),
            LOCAL_NO_KEY("local · no API key"),
            LOCAL_OFFLINE("local · offline"),
            LOCAL_CLOUD_FAILED("local · cloud failed"),
            UNKNOWN("—"),
        }

        @Volatile
        @JvmStatic
        var lastBrain: Brain = Brain.UNKNOWN

        /** The cloud model that answered last, e.g. "qwen3.5". Null until one has. */
        @Volatile
        @JvmStatic
        var lastModel: String? = null

        private const val TAG = "CloudRouter"
        private const val MAX_LOG_CHARS = 8_000
    }
}
