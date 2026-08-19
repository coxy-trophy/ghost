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
            // Reasoning off.
            //
            // qwen3.5 is a thinking model, and `num_predict` caps *all* generated tokens — thinking
            // included. With the cap at 512 the model spent the entire budget reasoning and returned
            // `content=""` with `done_reason=length`: measured 1709 characters of thinking and zero
            // of answer. The router read that as an empty reply and fell back to the local 1.5B, so
            // a working key and a valid model name still produced nothing but dumb answers.
            //
            // GHOST wants one or two spoken sentences, never a visible chain of thought, so the
            // reasoning is pure cost even when it fits. Accepted by the gpt-oss fallbacks too, which
            // simply ignore it.
            put("think", false)
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
            val message = json.optJSONObject("message")
            val content = message?.optString("content")?.trim()
            if (content.isNullOrEmpty()) {
                // Say why it was empty rather than just that it was. An answer lost to the token
                // budget looks identical to a refusal or a bad model name from the caller's side,
                // and telling them apart cost a live curl session the first time.
                val thinking = message?.optString("thinking")?.length ?: 0
                val reason = json.optString("done_reason", "unknown")
                throw IOException(
                    "Ollama returned no content (done_reason=$reason, thinking=$thinking chars)" +
                        if (thinking > 0) " — the model reasoned past num_predict; send think=false" else ""
                )
            }
            content
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
         * **Every name in the previous chain was wrong**, and it took a working key to notice.
         * `qwen3.5`, `gpt-oss:120b-cloud` and `gpt-oss:20b-cloud` all 404 — the `-cloud` suffix no
         * longer exists and `qwen3.5` needs its size tag. While the stored key was returning 401
         * this was invisible: every query failed at auth before any model name was ever tested, so
         * a second, independent fault sat behind the first one looking like nothing at all.
         *
         * These three are verified against `/api/tags` and a real `/api/chat` round trip on
         * 2026-08-19 — 1.3-4.4s each, all returning content.
         *
         * To re-confirm what a key can reach:
         *   curl -H "Authorization: Bearer \$KEY" https://ollama.com/api/tags
         */
        val DEFAULT_MODEL_CHAIN = listOf(
            "qwen3.5:397b",
            "gpt-oss:120b",
            "gpt-oss:20b"
        )
    }
}
