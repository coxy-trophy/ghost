package com.coxy.ghost.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Ollama's web search API.
 *
 * Deliberately the same provider as the cloud brain: same host, same `Authorization: Bearer`
 * header, same key out of [com.coxy.ghost.cloud.CloudKeyStorage]. A second search provider would
 * have meant a second credential to store, a second auth path to get wrong, and a second set of
 * failure modes to learn — for a response shape that is three fields.
 *
 * `POST https://ollama.com/api/web_search` takes `{"query", "max_results"}` and returns
 * `{"results":[{"title","url","content"}]}`.
 */
object WebSearchClient {

    private const val TAG = "WebSearchClient"
    private const val SEARCH_URL = "https://ollama.com/api/web_search"

    /** The API's own ceiling. Asking for more is not an error, it is just silently capped. */
    const val MAX_RESULTS = 10

    /**
     * Five is the API default and about as much as fits a small model's attention without
     * crowding out the question that prompted the search.
     */
    const val DEFAULT_RESULTS = 5

    /** Kept identical to the chat client's; see the note where it is set. */
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** One hit. [content] is the provider's snippet, not the whole page. */
    data class Result(val title: String, val url: String, val content: String)

    /**
     * What a search attempt produced. A failure is a value rather than an exception because the
     * caller's job is to tell GHOST what happened either way — "I couldn't reach the web" is a
     * sentence she should be able to say, and a thrown IOException would instead surface as a
     * generic action failure with no way to phrase it.
     */
    sealed interface Outcome {
        data class Ok(val query: String, val results: List<Result>) : Outcome
        data class Failed(val query: String, val reason: String) : Outcome
    }

    suspend fun search(
        apiKey: String,
        query: String,
        maxResults: Int = DEFAULT_RESULTS
    ): Outcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Outcome.Failed(query, "no query given")
        if (apiKey.isBlank()) return@withContext Outcome.Failed(q, "no API key stored")

        val body = JSONObject()
            .put("query", q)
            .put("max_results", maxResults.coerceIn(1, MAX_RESULTS))
            .toString()

        var conn: HttpsURLConnection? = null
        try {
            conn = (URL(SEARCH_URL).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                // Copied from OllamaCloudClient rather than chosen. Without these the first live
                // attempt came back 403 with an HTML body — an edge refusal, not the API's own JSON
                // error shape — because the request carried Dalvik's default User-Agent and no
                // Accept header, and so read as a bot before it ever reached the endpoint. The chat
                // client had already learned this; the search client had to learn it again.
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().take(200)
                Log.w(TAG, "search failed: HTTP $code $err")
                // The key is the usual cause and worth naming, because "search didn't work" sends
                // you looking at the network when the fix is in the Cloud dialog.
                return@withContext Outcome.Failed(
                    q,
                    when (code) {
                        401, 403 -> "the API key was rejected"
                        429 -> "rate limited by the search provider"
                        else -> "search provider returned HTTP $code"
                    }
                )
            }

            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val array = JSONObject(text).optJSONArray("results")
            val results = buildList {
                for (i in 0 until (array?.length() ?: 0)) {
                    val o = array!!.optJSONObject(i) ?: continue
                    add(
                        Result(
                            title = o.optString("title").trim(),
                            url = o.optString("url").trim(),
                            content = o.optString("content").trim()
                        )
                    )
                }
            }.filter { it.content.isNotEmpty() || it.title.isNotEmpty() }

            Log.i(TAG, "search '$q' -> ${results.size} results")
            if (results.isEmpty()) Outcome.Failed(q, "nothing came back") else Outcome.Ok(q, results)
        } catch (e: IOException) {
            Log.w(TAG, "search failed", e)
            Outcome.Failed(q, "couldn't reach the web (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            Outcome.Failed(q, "search error (${e.javaClass.simpleName})")
        } finally {
            conn?.disconnect()
        }
    }
}
