package com.coxy.ghost.net.ios

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Sends one named action to an iPhone's relay.
 *
 * The request is the same shape whatever the relay is: a POST with a JSON body naming the action,
 * an optional value, and a single-use reply URL. Pushcut puts the notification name in the path and
 * Home Assistant puts everything in the body, so `{action}` is substituted into the URL as well —
 * which covers both without either being hardcoded.
 *
 * What happens next is entirely on the far side: a Shortcut reads the action name and does whatever
 * Coxy built it to do. That is the point of the arrangement, and the reason GHOST never needs a new
 * build to gain an iPhone capability.
 */
object IosRelay {

    private const val TAG = "IosRelay"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * Fire [action] at [phone].
     *
     * @param replyUrl when non-null, included as `reply_to` so a Shortcut can post an answer back.
     * @return the relay's own response body, which is usually empty and rarely interesting — the
     *   answer, if there is one, comes back through [IosCallback].
     */
    suspend fun send(
        phone: IosPhone,
        action: String,
        value: String? = null,
        replyUrl: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = phone.relayUrl.replace("{action}", encode(action))
        val body = JSONObject().apply {
            put("action", action)
            // Pushcut shows `title`/`text` on its notification and passes `input` to the shortcut;
            // Home Assistant reads `message`. Sending all of them costs nothing and means one
            // payload works for both without Coxy editing anything.
            put("input", action)
            put("message", action)
            put("title", "GHOST")
            put("text", if (value.isNullOrBlank()) action else "$action $value")
            if (!value.isNullOrBlank()) put("value", value)
            if (replyUrl != null) put("reply_to", replyUrl)
        }.toString()

        val connection = URL(url).openConnection()
        if (connection !is HttpsURLConnection && !url.startsWith("http://")) {
            throw IOException("that relay address isn't a URL I can post to")
        }

        val http = connection as java.net.HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            http.setRequestProperty("Accept", "application/json")
            phone.authHeader?.takeIf { it.isNotBlank() }?.let {
                http.setRequestProperty(it, phone.authValue.orEmpty())
            }
            http.connectTimeout = CONNECT_TIMEOUT_MS
            http.readTimeout = READ_TIMEOUT_MS
            http.doOutput = true
            http.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = http.responseCode
            if (code !in 200..299) {
                val error = runCatching {
                    http.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().take(160)
                Log.w(TAG, "relay for ${phone.nickname} returned $code $error")
                // Named individually because each sends Coxy somewhere different: a 401 is the key,
                // a 404 is usually a Shortcut or notification name that does not exist on that phone.
                throw IOException(
                    when (code) {
                        401, 403 -> "the relay rejected GHOST's key"
                        404 -> "the relay has nothing called \"$action\""
                        429 -> "the relay is rate limiting"
                        else -> "the relay returned HTTP $code"
                    }
                )
            }
            http.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: java.net.UnknownHostException) {
            throw IOException("can't reach that relay — no route to ${URL(url).host}")
        } finally {
            http.disconnect()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
