package com.coxy.ghost.cloud

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Diagnostic test: discover which model names the stored Ollama Cloud API key can access.
 *
 * Tries both `https://ollama.com/api/tags` and `https://api.ollama.com/api/tags` with the stored
 * bearer token, and also probes a small set of common public model names against `/api/chat`.
 * Output is logged with tag [CloudModelDiscoveryTest].
 */
@RunWith(AndroidJUnit4::class)
class CloudModelDiscoveryTest {

    @Test
    fun discover_available_cloud_models() {
        runBlocking {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val key = CloudKeyStorage.getKey(ctx)
                ?: throw IllegalStateException("No API key stored. Run CloudKeyInjectionTest first.")

            for (baseUrl in listOf("https://ollama.com", "https://api.ollama.com")) {
                try {
                    val url = URL("$baseUrl/api/tags")
                    val conn = (url.openConnection() as HttpsURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $key")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    val status = conn.responseCode
                    val text = if (status == HttpURLConnection.HTTP_OK) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    Log.i(TAG, "tags probe for $baseUrl -> HTTP $status: ${text.take(200)}")

                    if (status == HttpURLConnection.HTTP_OK) {
                        val json = JSONArray(JSONObject(text).optJSONArray("models")?.toString() ?: "[]")
                        val names = (0 until json.length()).mapNotNull { idx ->
                            json.optJSONObject(idx)?.optString("name")
                        }
                        Log.i(TAG, "available models on $baseUrl: $names")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "tags probe for $baseUrl failed", e)
                }
            }

            val probes = listOf(
                "llama3.2", "llama3.1", "llama3", "qwen2.5", "qwen2.5-coder",
                "phi4", "gemma2", "mistral", "mixtral", "deepseek-coder-v2",
                "llama3.2-vision", "qwen3.5:cloud"
            )
            for (model in probes) {
                try {
                    val url = URL("https://ollama.com/api/chat")
                    val conn = (url.openConnection() as HttpsURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $key")
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        doOutput = true
                    }
                    val body = JSONObject().apply {
                        put("model", model)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply { put("role", "user"); put("content", "hi") })
                        })
                        put("stream", false)
                    }.toString().toByteArray(Charsets.UTF_8)
                    conn.outputStream.use { it.write(body) }
                    val status = conn.responseCode
                    Log.i(TAG, "chat probe for $model -> HTTP $status")
                    if (status == HttpURLConnection.HTTP_OK) {
                        val reply = conn.inputStream.bufferedReader().use { it.readText() }
                        val content = JSONObject(reply).optJSONObject("message")?.optString("content")?.trim()
                        Log.i(TAG, "WORKING MODEL: $model -> $content")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "chat probe for $model failed: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "CloudModelDiscoveryTest"
    }
}
