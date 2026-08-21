package com.coxy.ghost.net.atv

import android.util.Log
import com.coxy.ghost.net.atv.Protobuf.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.net.ssl.SSLSocket

/**
 * A remote-control session with a paired television.
 *
 * Opens, does the short configure/set-active dance the TV expects, sends the keys, and closes. Not
 * held open between commands on purpose: the protocol requires answering the TV's periodic pings or
 * being dropped, and a background daemon holding a TLS socket to a television all day to save half
 * a second on an occasional keypress is a poor trade on a phone.
 *
 * That does mean the TV's own pings can arrive mid-session — it starts sending them as soon as the
 * session is active — so the read loop answers anything that turns up rather than assuming the next
 * message is the one it asked for.
 */
object AtvRemote {

    private const val TAG = "AtvRemote"
    const val PORT = 6466

    private const val TIMEOUT_MS = 10_000

    // RemoteMessage field numbers.
    private const val F_CONFIGURE = 1
    private const val F_SET_ACTIVE = 2
    private const val F_ERROR = 3
    private const val F_PING_REQUEST = 8
    private const val F_PING_RESPONSE = 9
    private const val F_KEY_INJECT = 10
    private const val F_START = 30

    /** SHORT: a normal press and release. */
    private const val DIRECTION_SHORT = 3

    /**
     * The magic number every implementation of this protocol sends as `active`.
     *
     * It is not documented anywhere; it is what the Google TV remote app sends, and televisions
     * that receive anything else have been observed to accept the session and then ignore keys.
     */
    private const val ACTIVE = 622

    /**
     * Send one or more key codes to [host].
     *
     * @throws IOException with a readable reason if the television is unreachable or not paired.
     */
    suspend fun sendKeys(host: String, keyCodes: List<Int>): Unit = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            socket = AtvIdentity.connect(host, PORT, TIMEOUT_MS)
            val out = socket.outputStream
            val ins = socket.inputStream

            fun send(build: Protobuf.Writer.() -> Unit) {
                out.write(Protobuf.frame(Protobuf.Writer().apply(build).build()))
                out.flush()
            }

            // The TV opens with its own configure; ours answers it.
            readUntil(ins, ::identity, F_CONFIGURE, out)
            send {
                message(F_CONFIGURE) {
                    varint(1, 1)                    // code1
                    message(2) {                    // device_info
                        string(1, "GHOST")          // model
                        string(2, "coxy")           // vendor
                        varint(3, 1)                // unknown, always 1
                        string(4, "1")              // unknown
                        string(5, "com.coxy.ghost") // package_name
                        string(6, "1.0")            // app_version
                    }
                }
            }

            send { message(F_SET_ACTIVE) { varint(1, ACTIVE) } }
            // The TV replies with remote_start once it is willing to take keys. Sending before
            // that lands the keypress in a session the TV has not activated, and it is discarded
            // with no error at all.
            readUntil(ins, ::identity, F_START, out)

            for (code in keyCodes) {
                send {
                    message(F_KEY_INJECT) {
                        varint(1, code)             // key_code
                        varint(2, DIRECTION_SHORT)  // direction
                    }
                }
                Log.i(TAG, "sent keycode $code to $host")
            }
            // Give the TV a moment to act before the socket closes under it.
            Thread.sleep(300)
        } catch (e: javax.net.ssl.SSLException) {
            // The TV rejecting the certificate is what "not paired" looks like from here.
            throw IOException("that television hasn't been paired with GHOST yet")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun identity(x: ByteArray) = x

    /**
     * Read until a message carrying [wanted] arrives, answering pings on the way.
     *
     * A television starts pinging as soon as the session is up, and an unanswered ping closes the
     * connection — so the message being waited for is frequently not the next one to arrive.
     */
    private fun readUntil(
        ins: java.io.InputStream,
        transform: (ByteArray) -> ByteArray,
        wanted: Int,
        out: java.io.OutputStream
    ) {
        repeat(12) {
            val body = transform(readFramed(ins))
            val fields = Protobuf.parse(body)

            fields.field(F_ERROR)?.let {
                throw IOException("the television reported an error")
            }
            fields.field(F_PING_REQUEST)?.let { ping ->
                val value = Protobuf.parse(ping.bytes ?: ByteArray(0)).field(1)?.varint ?: 0
                out.write(
                    Protobuf.frame(
                        Protobuf.Writer()
                            .message(F_PING_RESPONSE) { varint(1, value) }
                            .build()
                    )
                )
                out.flush()
                return@repeat
            }
            if (fields.field(wanted) != null) return
        }
        throw IOException("the television never became ready for commands")
    }

    private fun readFramed(ins: java.io.InputStream): ByteArray {
        var length = 0L
        var shift = 0
        while (true) {
            val b = ins.read()
            if (b < 0) throw IOException("the television closed the connection")
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("unreadable message length")
        }
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = ins.read(body, read, body.size - read)
            if (n < 0) throw IOException("the television closed the connection mid-message")
            read += n
        }
        return body
    }
}
