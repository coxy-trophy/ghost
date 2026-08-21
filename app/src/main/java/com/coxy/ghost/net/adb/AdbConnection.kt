package com.coxy.ghost.net.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A client for another Android phone's debug bridge.
 *
 * This is the protocol `adb` itself speaks, which is why it needs nothing installed on the other
 * phone — only that its owner has switched debugging on and tapped "Allow" once. Every message is a
 * 24-byte header and an optional payload; a command runs by opening a stream named `shell:...` and
 * reading until the far end closes it.
 *
 * Connections are made per command rather than held open. A phone across the room is not a session
 * worth keeping alive on a battery, and the handshake costs about a hundred milliseconds.
 */
internal class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream
) : AutoCloseable {

    companion object {
        private const val TAG = "AdbConnection"

        /** The legacy debug-bridge port, opened by `adb tcpip 5555`. */
        const val PORT = 5555

        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 10_000

        /**
         * Connect and authenticate.
         *
         * @throws AdbUnauthorized when the phone is waiting on its owner to tap Allow. That is a
         *   distinct outcome rather than a generic failure, because it is the one case where the
         *   fix is "go and look at the other phone" rather than anything technical.
         */
        suspend fun open(host: String, port: Int = PORT): AdbConnection = withContext(Dispatchers.IO) {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val conn = AdbConnection(socket, DataInputStream(socket.getInputStream()), socket.getOutputStream())
            runCatching { conn.handshake(host) }.getOrElse {
                conn.close()
                throw it
            }
            conn
        }
    }

    /** The phone is reachable but its owner has not accepted GHOST's key. */
    class AdbUnauthorized(val host: String) :
        IOException("$host is waiting for you to tap Allow on it")

    private var nextLocalId = 1

    private fun handshake(host: String) {
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray())

        // The phone may ask more than once: the first refusal is normal, and the second is when
        // it puts the dialog up. Bounded so a phone that will never say yes does not hang here.
        var sentPublicKey = false
        repeat(4) {
            val message = read()
            when (message.command) {
                A_CNXN -> {
                    Log.i(TAG, "connected to $host: ${String(message.payload).trim('\u0000')}")
                    return
                }
                A_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN) throw IOException("unexpected auth stage")
                    if (!sentPublicKey) {
                        // Signature first: if this phone already knows the key, nothing is shown
                        // to its owner at all and the connection just works.
                        send(A_AUTH, AUTH_SIGNATURE, 0, AdbKey.sign(message.payload))
                    } else {
                        throw AdbUnauthorized(host)
                    }
                    val next = read()
                    if (next.command == A_CNXN) {
                        Log.i(TAG, "connected to $host")
                        return
                    }
                    if (next.command == A_AUTH) {
                        // Key unknown: offering it is what raises the prompt on the other phone.
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, AdbKey.publicKeyForAdb() + byteArrayOf(0))
                        sentPublicKey = true
                        // The phone now waits on a human. Reading further would just time out.
                        throw AdbUnauthorized(host)
                    }
                }
                else -> throw IOException("unexpected message during connect")
            }
        }
        throw IOException("$host would not complete the handshake")
    }

    /**
     * Run one shell command and return everything it printed.
     *
     * `shell:` streams are line-buffered and closed by the far end when the command exits, so the
     * read loop ends on CLSE rather than on any length known in advance.
     */
    fun shell(command: String): String {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, (command + "\u0000").toByteArray())

        val out = StringBuilder()
        var remoteId = 0
        while (true) {
            val message = try {
                read()
            } catch (e: java.net.SocketTimeoutException) {
                // A command that produces nothing and never exits (rare, but `input` can) should
                // not hang the whole turn.
                Log.w(TAG, "timed out reading output of '$command'")
                break
            }
            when (message.command) {
                A_OKAY -> remoteId = message.arg0
                A_WRTE -> {
                    out.append(String(message.payload))
                    remoteId = message.arg0
                    send(A_OKAY, localId, remoteId, ByteArray(0))
                }
                A_CLSE -> {
                    send(A_CLSE, localId, remoteId, ByteArray(0))
                    return out.toString().trim()
                }
                else -> Unit
            }
        }
        return out.toString().trim()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    // --- wire ------------------------------------------------------------------------------------

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(checksum(payload))
        // The "magic" is the command with every bit flipped, and it is how the far end tells a
        // header from payload that happens to look like one.
        header.putInt(command.inv())
        output.write(header.array())
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    private fun read(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        buffer.int // checksum, unverified: adb itself stopped checking it years ago
        val magic = buffer.int
        if (command != magic.inv()) throw IOException("corrupt message from the other phone")
        val payload = if (length > 0) ByteArray(length).also { input.readFully(it) } else ByteArray(0)
        return Message(command, arg0, arg1, payload)
    }

    /** adb's checksum is the sum of the payload bytes, unsigned. */
    private fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += (b.toInt() and 0xFF)
        return sum
    }
}

/** Read [InputStream] fully into a [ByteArray]. */
private fun DataInputStream.readFully(into: ByteArray) {
    var read = 0
    while (read < into.size) {
        val n = read(into, read, into.size - read)
        if (n < 0) throw IOException("the other phone closed the connection")
        read += n
    }
}
