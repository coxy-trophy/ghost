package com.coxy.ghost.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Ollama Cloud API client (Phase 8).
 *
 * Calls `https://ollama.com/api/chat` with a standard chat messages array. Supports a fallback
 * model chain: the strongest model is tried first; if it fails, the next one is attempted. The
 * caller receives a single Flow emission containing the full assistant reply (Ollama Cloud does
 * support streaming, but non-streaming keeps the integration simple and UI-compatible).
 *
 * All network work runs on [Dispatchers.IO].
 */
class OllamaCloudClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val models: List<String> = DEFAULT_MODEL_CHAIN
) : CloudClient {

    init {
        require(models.isNotEmpty()) { "model chain must not be empty" }
    }

    /**
     * Send the system + user messages to the cloud. Returns a Flow that emits the full assistant
     * reply once, or throws [IOException] if every model in the chain fails.
     */
    override fun chat(
        systemPrompt: String,
        userPrompt: String,
        predictLength: Int
    ): Flow<String> = flow {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
        }

        val body = JSONObject().apply {
            put("messages", messages)
            put("stream", false)
            put("options", JSONObject().apply {
                put("num_predict", predictLength)
            })
        }

        var lastError: Throwable? = null
        for (model in models) {
            val result = runCatching { sendToModel(model, body) }
            result.onSuccess { reply ->
                lastModelUsed = model
                Log.i(TAG, "Cloud reply from $model: ${reply.take(80)}")
                emit(reply)
                return@flow
            }
            result.onFailure { e ->
                lastError = e
                Log.w(TAG, "Cloud model $model failed: ${e.message}")
            }
        }
        throw IOException("all cloud models failed: ${lastError?.message ?: "unknown"}", lastError)
    }.flowOn(Dispatchers.IO)

    @Volatile
    override var lastModelUsed: String? = null
        private set

    private suspend fun sendToModel(model: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        body.put("model", model)
        val url = URL("$baseUrl/api/chat")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            // Ollama Cloud's edge/WAF rejects the default Android Java user-agent on some keys.
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }

        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        conn.outputStream.use { it.write(payload) }

        val status = conn.responseCode
        val responseText = if (status == HttpURLConnection.HTTP_OK) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $status: $err")
        }

        val json = JSONObject(responseText)
        if (json.optBoolean("done", false)) {
            json.optJSONObject("message")?.optString("content")?.trim()
                ?: throw IOException("Ollama response missing message.content")
        } else {
            json.optString("response").ifBlank {
                throw IOException("Ollama response did not finish: $responseText")
            }
        }
    }

    companion object {
        private const val TAG = "OllamaCloudClient"
        private const val DEFAULT_BASE_URL = "https://ollama.com"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /**
         * Model chain for Ollama Cloud inference, tried strongest-first: [chat] walks the list and
         * returns the first model that answers, so a name that no longer resolves costs one failed
         * request rather than the whole query.
         *
         * `qwen3.5` is the chosen model. The gpt-oss entries are kept behind it deliberately, not
         * as leftovers: Ollama Cloud names normally carry a `-cloud` suffix, so a bare `qwen3.5`
         * may resolve as a cloud model or may not, and until that is confirmed against the real API
         * the fallbacks are what stand between a wrong name and GHOST silently dropping to the
         * local 1.5B. `CloudRouter.lastBrain` and the chat chip will show which one actually
         * answered.
         *
         * To confirm what this key can reach, once one is stored:
         *   ./gradlew :app:connectedDebugAndroidTest \
         *     -Pandroid.testInstrumentationRunnerArguments.class=com.coxy.ghost.cloud.CloudModelDiscoveryTest \
         *     -Pandroid.testInstrumentationRunnerArguments.cloudLive=true
         */
        val DEFAULT_MODEL_CHAIN = listOf(
            "qwen3.5",
            "gpt-oss:120b-cloud",
            "gpt-oss:20b-cloud"
        )
    }
}
