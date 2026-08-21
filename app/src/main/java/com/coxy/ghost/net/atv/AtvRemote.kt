package com.coxy.ghost.net.atv

import android.util.Log
import com.coxy.ghost.net.atv.Protobuf.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * A remote-control session with a paired television.
 *
 * **The television drives this conversation, not us.** The first version of this read a fixed
 * sequence — wait for configure, reply, announce active, wait for start — and timed out every time,
 * because the TV also sends its own `remote_set_active` and waits for that to be answered before it
 * will say `remote_start`. A script that talks past it deadlocks against a device that is patiently
 * waiting its turn.
 *
 * So this is an event loop: read whatever arrives, answer it, and send the keys once the TV says it
 * is started. Every message is logged by field number, because a television that accepts the
 * connection and then goes quiet gives nothing else to go on.
 *
 * Not held open between commands. The protocol needs its pings answered or it drops the session,
 * and a daemon keeping a TLS socket to a television all day to save half a second on an occasional
 * keypress is a poor trade on a phone.
 */
object AtvRemote {

    private const val TAG = "AtvRemote"
    const val PORT = 6466

    /** Per-read wait. Short, so a silent TV is nudged rather than waited out. */
    private const val READ_TIMEOUT_MS = 3_000

    /** Whole-session ceiling, across as many messages as it takes. */
    private const val SESSION_MS = 20_000L

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
     * The value every implementation of this protocol sends as `active`.
     *
     * Undocumented; it is what the Google TV remote app sends. Televisions given something else
     * have been seen to accept the session and then ignore every key.
     */
    private const val ACTIVE = 622

    suspend fun sendKeys(host: String, keyCodes: List<Int>): Unit = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            socket = AtvIdentity.connect(host, PORT, READ_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.outputStream
            val ins = socket.inputStream

            var sentConfigure = false
            var sentActive = false
            var started = false
            val deadline = System.currentTimeMillis() + SESSION_MS

            while (System.currentTimeMillis() < deadline && !started) {
                val body = try {
                    readFramed(ins)
                } catch (_: SocketTimeoutException) {
                    // The TV is waiting for us. Opening with our own configure is harmless if it
                    // was simply slow, and is the thing that unsticks it if it expects us first.
                    if (!sentConfigure) {
                        Log.i(TAG, "$host quiet — sending configure unprompted")
                        sendConfigure(out); sentConfigure = true
                    } else if (!sentActive) {
                        Log.i(TAG, "$host quiet after configure — announcing active")
                        sendSetActive(out); sentActive = true
                    }
                    continue
                }

                val fields = Protobuf.parse(body)
                Log.i(TAG, "$host -> fields ${fields.map { it.number }}")

                if (fields.field(F_ERROR) != null) {
                    throw IOException("the television reported an error")
                }
                fields.field(F_PING_REQUEST)?.let { ping ->
                    val value = Protobuf.parse(ping.bytes ?: ByteArray(0)).field(1)?.varint ?: 0
                    send(out) { message(F_PING_RESPONSE) { varint(1, value) } }
                }
                if (fields.field(F_CONFIGURE) != null && !sentConfigure) {
                    sendConfigure(out); sentConfigure = true
                }
                // The message the fixed sequence never answered, and the reason it hung.
                if (fields.field(F_SET_ACTIVE) != null && !sentActive) {
                    sendSetActive(out); sentActive = true
                }
                if (fields.field(F_START) != null) {
                    started = true
                }
                // This television never sends remote_start. After the two-message handshake it
                // simply begins talking — fields 40, 50 and 20 here, then pings every five seconds
                // — and a session it pings is a session it considers live. Waiting for a start
                // that was never coming meant timing out on a connection that was working
                // perfectly, answering its pings the whole time.
                if (sentConfigure && sentActive &&
                    fields.any { it.number != F_CONFIGURE && it.number != F_SET_ACTIVE }
                ) {
                    started = true
                }
                // Nothing is volunteered here on purpose. An earlier version announced active as
                // soon as configure had been sent, which spent the reply before the television
                // asked — so when its own set_active arrived the guard below had already been set
                // and it went unanswered, and the TV waited for a turn that never came. The
                // television gets to ask; the read-timeout branch above covers a firmware that
                // does not.
            }

            if (!started) throw IOException("the television never became ready for commands")

            // Settled, not merely started. The handshake is followed by a burst — this set sends
            // fields 40, 50 and 20 within a tenth of a second — and a key injected into the middle
            // of it is accepted and discarded, which looks from here exactly like a key that
            // worked. Draining until the television either goes quiet or starts pinging means the
            // keypress lands on a session that has finished setting itself up.
            var quiet = false
            val settleBy = System.currentTimeMillis() + 8_000
            while (!quiet && System.currentTimeMillis() < settleBy) {
                val body = try {
                    readFramed(ins)
                } catch (_: SocketTimeoutException) {
                    quiet = true          // nothing more to say: it is ready
                    continue
                }
                val fields = Protobuf.parse(body)
                Log.i(TAG, "$host -> fields ${fields.map { it.number }} (settling)")
                fields.field(F_PING_REQUEST)?.let { ping ->
                    val value = Protobuf.parse(ping.bytes ?: ByteArray(0)).field(1)?.varint ?: 0
                    send(out) { message(F_PING_RESPONSE) { varint(1, value) } }
                    quiet = true          // a ping means idle, and idle means ready
                }
                if (fields.field(F_ERROR) != null) {
                    throw IOException("the television reported an error")
                }
            }

            for (code in keyCodes) {
                send(out) {
                    message(F_KEY_INJECT) {
                        varint(1, code)             // key_code
                        varint(2, DIRECTION_SHORT)  // direction
                    }
                }
                Log.i(TAG, "sent keycode $code to $host")
            }

            // Stay a moment so the TV acts before the socket closes under it, answering anything
            // it says in the meantime rather than leaving a ping unanswered on the way out.
            val settle = System.currentTimeMillis() + 1_000
            while (System.currentTimeMillis() < settle) {
                val body = try { readFramed(ins) } catch (_: SocketTimeoutException) { break }
                val fields = Protobuf.parse(body)
                fields.field(F_PING_REQUEST)?.let { ping ->
                    val value = Protobuf.parse(ping.bytes ?: ByteArray(0)).field(1)?.varint ?: 0
                    send(out) { message(F_PING_RESPONSE) { varint(1, value) } }
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            // The TV refusing the certificate is what "not paired" looks like from here.
            Log.w(TAG, "TLS refused by $host", e)
            throw IOException("that television hasn't been paired with GHOST yet")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun sendConfigure(out: OutputStream) = send(out) {
        message(F_CONFIGURE) {
            // code1 is the same 622 as set_active, not 1. With 1 the television accepted the
            // connection, sent its configure and its set_active, and then went silent for ever —
            // no error, no close, just a device that had decided we were not a remote.
            varint(1, ACTIVE)               // code1
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

    private fun sendSetActive(out: OutputStream) =
        send(out) { message(F_SET_ACTIVE) { varint(1, ACTIVE) } }

    private fun send(out: OutputStream, build: Protobuf.Writer.() -> Unit) {
        val body = Protobuf.Writer().apply(build).build()
        // Logged alongside what arrives, so a silent television can be read as a conversation
        // rather than guessed at.
        Log.i(TAG, "<- fields ${Protobuf.parse(body).map { it.number }} (${body.size}b)")
        out.write(Protobuf.frame(body))
        out.flush()
    }

    private fun readFramed(ins: InputStream): ByteArray {
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
