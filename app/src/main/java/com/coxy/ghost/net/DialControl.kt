package com.coxy.ghost.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DIAL: putting an app on the television.
 *
 * Both Smart TVs on this network advertise `urn:dial-multiscreen-org:device:dial:1`, which is the
 * protocol behind "cast YouTube to the TV" — but the launch half of it is plain HTTP, so it needs
 * neither the Cast SDK nor a pairing handshake.
 *
 * The endpoint is not at a fixed path. A GET on the device description returns an `Application-URL`
 * **header** — not an element in the body, which is the part that catches people out — and that
 * header is the base every app name hangs off.
 */
object DialControl {

    private const val TAG = "DialControl"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * DIAL app names are a registered, case-sensitive namespace rather than free text: the TV wants
     * `YouTube`, not `youtube` or `You Tube`. Coxy says the latter, so this maps what he says onto
     * what the protocol accepts. Anything not listed is passed through with its case intact, since
     * an unlisted app is more likely a correctly-spelled one than a mistake.
     */
    private val NAMES = mapOf(
        "youtube" to "YouTube",
        "yt" to "YouTube",
        "netflix" to "Netflix",
        "primevideo" to "PrimeVideo",
        "amazonprime" to "PrimeVideo",
        "prime" to "PrimeVideo",
        "disney" to "Disney",
        "disneyplus" to "Disney",
        "spotify" to "Spotify",
        "youtubetv" to "YouTubeTV",
        "youtubemusic" to "YouTubeMusic"
    )

    internal fun canonicalName(spoken: String): String {
        val key = spoken.lowercase().filter { it.isLetterOrDigit() }
        return NAMES[key] ?: spoken.trim()
    }

    /** The `Application-URL` a device advertises, or null if it does not speak DIAL. */
    suspend fun applicationUrl(descriptionUrl: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(descriptionUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            conn.responseCode
            // Header names are case-insensitive on the wire and devices disagree about the casing.
            val url = conn.headerFields.entries
                .firstOrNull { it.key?.equals("Application-URL", ignoreCase = true) == true }
                ?.value?.firstOrNull()
            url?.trimEnd('/')
        } catch (e: Exception) {
            Log.w(TAG, "no Application-URL at $descriptionUrl", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Launch [app] on the device.
     *
     * A POST with an empty body starts it; the TV answers 201 with a Location for the running
     * instance, or 404 when it does not have that app installed — which is worth distinguishing,
     * because "the TV said no" and "you don't have Netflix on it" are different things to be told.
     */
    suspend fun launch(applicationUrl: String, app: String): Result<String> =
        withContext(Dispatchers.IO) {
            val name = canonicalName(app)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$applicationUrl/$name").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Length", "0")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                }
                conn.outputStream.use { }
                when (val code = conn.responseCode) {
                    in 200..299 -> Result.success(name)
                    404 -> Result.failure(IOException("that device doesn't have $name"))
                    403 -> Result.failure(IOException("$name refused to start"))
                    else -> Result.failure(IOException("the device returned HTTP $code"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "launch $name failed", e)
                Result.failure(IOException("could not reach the device (${e.javaClass.simpleName})"))
            } finally {
                conn?.disconnect()
            }
        }
}
