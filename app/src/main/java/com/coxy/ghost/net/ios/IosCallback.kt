package com.coxy.ghost.net.ios

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The way an answer gets back from an iPhone.
 *
 * A relay is one-way: GHOST posts a webhook and a Shortcut runs. Nothing comes back, which is fine
 * for "pause the music" and useless for "how much battery has it got". So GHOST listens.
 *
 * Each request carries a single-use reply URL pointing at this listener. The Shortcut does its work
 * and POSTs the answer there, and the action that is waiting picks it up. That closes the loop
 * without a cloud service, an account, or anything on the iPhone beyond one more step in a Shortcut
 * Coxy was building anyway.
 *
 * **Only ever open while something is waiting.** The socket is started when a reply is expected and
 * closed when it arrives or times out, so there is no permanently listening port on a network that
 * carries forty-odd other devices. A token is 128 bits of `SecureRandom`, single-use, and only
 * accepted while its request is outstanding — a POST to an unknown or spent token is dropped without
 * being read, so the listener cannot be used as a way to hand GHOST arbitrary text.
 */
object IosCallback {

    private const val TAG = "IosCallback"

    /** Ephemeral: the port is chosen by the OS and told to the Shortcut in the reply URL. */
    private const val ANY_PORT = 0

    /** How long a Shortcut has to answer. Long enough for one to wake and run, short enough to say so. */
    const val WAIT_MS = 25_000L

    /** Nothing larger than this is read from a reply. A status line is a sentence, not a payload. */
    private const val MAX_BODY = 8 * 1024

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    data class Pending(val token: String, val replyUrl: String)

    /**
     * Open the listener if it is not already, and mint a single-use token.
     *
     * @param localIp the phone's own address on the Wi-Fi network — the Shortcut has to reach it,
     *   so a loopback address would be a reply that can never arrive.
     */
    @Synchronized
    fun expect(localIp: String): Pending {
        val socket = server ?: ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(ANY_PORT))
        }.also {
            server = it
            thread = Thread({ accept(it) }, "ghost-ios-callback").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on ${it.localPort} for a reply")
        }

        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        waiting[token] = CompletableDeferred()
        return Pending(token, "http://$localIp:${socket.localPort}/reply/$token")
    }

    /** Wait for the Shortcut to answer, or give up. */
    suspend fun await(pending: Pending): String? = withContext(Dispatchers.IO) {
        val deferred = waiting[pending.token] ?: return@withContext null
        try {
            withTimeoutOrNull(WAIT_MS) { deferred.await() }
        } finally {
            waiting.remove(pending.token)
            closeIfIdle()
        }
    }

    @Synchronized
    private fun closeIfIdle() {
        if (waiting.isNotEmpty()) return
        runCatching { server?.close() }
        server = null
        thread = null
        Log.i(TAG, "nothing waiting; listener closed")
    }

    /**
     * A deliberately tiny HTTP server: read the request line, match the token, take the body.
     *
     * Not a general server and should not become one. It answers exactly one shape of request and
     * replies 204 to everything, because a reply that says nothing is a reply that cannot be probed.
     */
    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                return
            }
            runCatching {
                client.soTimeout = 5_000
                client.use { c ->
                    val input = c.getInputStream().bufferedReader()
                    val requestLine = input.readLine().orEmpty()
                    val token = Regex("/reply/([0-9a-f]{32})").find(requestLine)?.groupValues?.get(1)

                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }

                    val deferred = token?.let { waiting[it] }
                    if (deferred != null && length in 1..MAX_BODY) {
                        val body = CharArray(length)
                        val read = input.read(body, 0, length)
                        if (read > 0) deferred.complete(String(body, 0, read).trim())
                    }
                    // Same answer either way: an unknown token learns nothing from the response.
                    c.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                }
            }
        }
    }
}
