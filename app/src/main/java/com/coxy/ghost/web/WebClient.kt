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
 * Ollama's web APIs: search an index, or read one page.
 *
 * Deliberately the same provider as the cloud brain: same host, same `Authorization: Bearer`
 * header, same key out of [com.coxy.ghost.cloud.CloudKeyStorage]. A second provider would have
 * meant another credential to store, another auth path to get wrong, and another set of failure
 * modes to learn — for response shapes that are three fields each.
 *
 * - `POST /api/web_search` — `{"query", "max_results"}` -> `{"results":[{"title","url","content"}]}`
 * - `POST /api/web_fetch`  — `{"url"}` -> `{"title","content","links":[]}`
 *
 * Worth knowing about fetch: **Ollama's servers do the fetching, not the phone.** The URL the model
 * produced never resolves on this device, so a model talked into naming `192.168.1.1` or
 * `localhost` reaches Ollama's network and fails there rather than probing Coxy's. That is a
 * meaningful safety property this design gets for free, and it would be lost by fetching locally.
 */
object WebClient {

    private const val TAG = "WebClient"
    private const val SEARCH_URL = "https://ollama.com/api/web_search"
    private const val FETCH_URL = "https://ollama.com/api/web_fetch"

    /** The API's own ceiling. Asking for more is not an error, it is just silently capped. */
    const val MAX_RESULTS = 10

    /**
     * Five is the API default and about as much as fits a small model's attention without
     * crowding out the question that prompted the search.
     */
    const val DEFAULT_RESULTS = 5

    /** Kept identical to [com.coxy.ghost.cloud.OllamaCloudClient]'s; see where it is set below. */
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** A page can be enormous; fetch waits longer than search for that reason. */
    private const val FETCH_READ_TIMEOUT_MS = 30_000

    /** One search hit. [content] is the provider's snippet, not the whole page. */
    data class Result(val title: String, val url: String, val content: String)

    /**
     * What a web call produced. A failure is a value rather than an exception because the caller's
     * job is to tell GHOST what happened either way — "I couldn't reach it" is a sentence she should
     * be able to say, and a thrown IOException would instead surface as a generic action failure
     * with no way to phrase it.
     */
    sealed interface Outcome {
        data class Searched(val query: String, val results: List<Result>) : Outcome
        data class Fetched(val url: String, val title: String, val content: String) : Outcome
        data class Failed(val subject: String, val reason: String) : Outcome
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

        when (val r = post(SEARCH_URL, apiKey, body, READ_TIMEOUT_MS, q)) {
            is Raw.Failed -> r.outcome
            is Raw.Ok -> {
                val array = r.json.optJSONArray("results")
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
                if (results.isEmpty()) Outcome.Failed(q, "nothing came back")
                else Outcome.Searched(q, results)
            }
        }
    }

    /**
     * Read one page.
     *
     * [url] is rejected here rather than at the API if it is not plain http(s). The model produces
     * this string, sometimes off a page it has just read, so it is the same class of boundary as
     * [com.coxy.ghost.action.PackageNames] is for the shell: `file:`, `data:` and `javascript:`
     * have no business being handed to a fetcher on Coxy's behalf, whoever suggested them.
     */
    suspend fun fetch(apiKey: String, url: String): Outcome = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (u.isEmpty()) return@withContext Outcome.Failed(url, "no address given")
        if (apiKey.isBlank()) return@withContext Outcome.Failed(u, "no API key stored")
        if (!isFetchable(u)) return@withContext Outcome.Failed(u, "that is not a web address")

        val body = JSONObject().put("url", u).toString()

        when (val r = post(FETCH_URL, apiKey, body, FETCH_READ_TIMEOUT_MS, u)) {
            is Raw.Failed -> r.outcome
            is Raw.Ok -> {
                val content = r.json.optString("content").trim()
                val title = r.json.optString("title").trim()
                Log.i(TAG, "fetch '$u' -> ${content.length} chars")
                if (content.isEmpty()) Outcome.Failed(u, "the page had no readable text")
                else Outcome.Fetched(u, title.ifEmpty { u }, content)
            }
        }
    }

    /** http(s) only, and a host must actually be present. */
    internal fun isFetchable(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = lower.substringAfter("://").substringBefore('/').substringBefore('?')
        return host.isNotEmpty() && host.contains('.') && !host.contains(' ')
    }

    private sealed interface Raw {
        data class Ok(val json: JSONObject) : Raw
        data class Failed(val outcome: Outcome.Failed) : Raw
    }

    private fun post(
        endpoint: String,
        apiKey: String,
        body: String,
        readTimeoutMs: Int,
        subject: String
    ): Raw {
        var conn: HttpsURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                // Copied from OllamaCloudClient rather than chosen. Without these the first live
                // attempt came back 403 with an HTML body — an edge refusal, not the API's own JSON
                // error shape — because the request carried Dalvik's default User-Agent and no
                // Accept header, and so read as a bot before it ever reached the endpoint. The chat
                // client had already learned this; the web client had to learn it again.
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().take(200)
                Log.w(TAG, "$endpoint failed: HTTP $code $err")
                // The key is a common cause and worth naming, because "it didn't work" sends you
                // looking at the network when the fix is in the Cloud dialog.
                return Raw.Failed(
                    Outcome.Failed(
                        subject,
                        when (code) {
                            401, 403 -> "the API key was rejected"
                            404 -> "there is nothing at that address"
                            429 -> "rate limited by the provider"
                            else -> "the provider returned HTTP $code"
                        }
                    )
                )
            }

            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            Raw.Ok(JSONObject(text))
        } catch (e: IOException) {
            Log.w(TAG, "$endpoint failed", e)
            Raw.Failed(Outcome.Failed(subject, "couldn't reach the web (${e.javaClass.simpleName})"))
        } catch (e: Exception) {
            Log.w(TAG, "$endpoint failed", e)
            Raw.Failed(Outcome.Failed(subject, "web error (${e.javaClass.simpleName})"))
        } finally {
            conn?.disconnect()
        }
    }
}
