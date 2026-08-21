package com.coxy.ghost.net.atv

import android.util.Log
import com.coxy.ghost.net.atv.Protobuf.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/**
 * The pairing handshake: teaching one television to trust GHOST's certificate.
 *
 * Four exchanges, then the TV puts a six-character code on screen, then one hash proves both ends
 * are looking at the same two certificates and the same code. That hash is the entire security of
 * this protocol — the TLS is unauthenticated in both directions, so what stops a stranger pairing
 * with the TV is that they cannot see the screen.
 *
 * The connection has to stay open across the code being read out and typed back, which is why this
 * is a session object rather than a function: [begin] runs up to the point the code appears, and
 * [finish] completes it. A dropped connection in between means starting over, and the TV will show
 * a different code.
 */
class AtvPairing(private val host: String) {

    companion object {
        private const val TAG = "AtvPairing"
        const val PORT = 6467

        /** Generous: the handshake is four short round trips but the TV may be waking up. */
        private const val TIMEOUT_MS = 15_000

        private const val STATUS_OK = 200

        // PairingMessage field numbers.
        private const val F_PROTOCOL_VERSION = 1
        private const val F_STATUS = 2
        private const val F_REQUEST = 10
        private const val F_REQUEST_ACK = 11
        private const val F_OPTION = 20
        private const val F_OPTION_ACK = 21
        private const val F_CONFIGURATION = 30
        private const val F_CONFIGURATION_ACK = 31
        private const val F_SECRET = 40
        private const val F_SECRET_ACK = 41

        private const val ENCODING_HEXADECIMAL = 3
        private const val ROLE_INPUT = 1
    }

    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /**
     * Connect and run the handshake up to the point the television displays its code.
     *
     * @return nothing on success; throws with a readable reason otherwise.
     */
    suspend fun begin(clientName: String = "GHOST") = withContext(Dispatchers.IO) {
        val s = AtvIdentity.connect(host, PORT, TIMEOUT_MS)
        socket = s
        input = s.inputStream
        output = s.outputStream
        Log.i(TAG, "paired socket open to $host")

        send {
            varint(F_PROTOCOL_VERSION, 2)
            varint(F_STATUS, STATUS_OK)
            message(F_REQUEST) {
                string(1, "com.coxy.ghost/ghost")   // service_name
                string(2, clientName)               // client_name
            }
        }
        expect(listOf(F_REQUEST_ACK), "the television refused the pairing request")

        send {
            varint(F_PROTOCOL_VERSION, 2)
            varint(F_STATUS, STATUS_OK)
            message(F_OPTION) {
                // input_encodings: what GHOST can accept from the screen.
                message(1) {
                    varint(1, ENCODING_HEXADECIMAL)
                    varint(2, 6)                    // symbol_length: a six-character code
                }
                varint(3, ROLE_INPUT)               // preferred_role
            }
        }
        // The television answers our options with *its own* options rather than an ack — the only
        // step in this handshake that does not follow request/ack, and the reason this failed with
        // "unexpected reply" while every other exchange had already worked. Both are accepted so a
        // firmware that does send an ack is not broken by the fix for one that does not.
        expect(listOf(F_OPTION, F_OPTION_ACK), "the television would not agree on a code format")

        send {
            varint(F_PROTOCOL_VERSION, 2)
            varint(F_STATUS, STATUS_OK)
            message(F_CONFIGURATION) {
                message(1) {                        // encoding
                    varint(1, ENCODING_HEXADECIMAL)
                    varint(2, 6)
                }
                varint(2, ROLE_INPUT)               // client_role
            }
        }
        expect(listOf(F_CONFIGURATION_ACK), "the television rejected the pairing configuration")
        Log.i(TAG, "$host should now be showing a code")
    }

    /**
     * Send the proof that the code on screen was seen.
     *
     * @param code the six hex characters the television is displaying.
     */
    suspend fun finish(code: String) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IllegalStateException("pairing was not started")
        val clean = normalise(code)
        val bad = clean.filterNot { it.isDigit() || it in 'A'..'F' }
        require(clean.length == 6 && bad.isEmpty()) {
            // Naming the actual rule, not an example. The example used to be "it should look like
            // 1A2B3C", and GHOST read a pattern into it that does not exist — she told Coxy his
            // code was wrong because it had no digits in the places hers did.
            val why = when {
                bad.isNotEmpty() -> "'$bad' can't appear in it"
                else -> "it has ${clean.length} characters"
            }
            "'${code.trim()}' isn't a pairing code — six characters, digits and A to F only, and $why"
        }

        val (clientMod, clientExp) = AtvIdentity.clientRsa()
        val (serverMod, serverExp) = AtvIdentity.serverRsa(s)
        // The code's first byte is a checksum over the hash; the remaining four hex characters are
        // the nonce that goes into it. Hashing the whole code instead is the classic way to get a
        // STATUS_BAD_SECRET with nothing to say why.
        val nonce = clean.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(clientMod); update(clientExp)
            update(serverMod); update(serverExp)
            update(nonce)
        }.digest()

        // The first byte of the code is a checksum over this hash, so a misread character can be
        // caught here rather than by sending a wrong secret. It is a warning and not a refusal:
        // this check is only as good as the hash, and if the hash were subtly wrong a *correct*
        // code would be rejected for ever with no way through. The television is the authority on
        // whether the secret is right, so a mismatch still gets sent and still gets its real answer.
        val expected = clean.substring(0, 2).toInt(16)
        val actual = digest[0].toInt() and 0xFF
        if (actual != expected) {
            Log.w(TAG, "code checksum mismatch: expected $expected, computed $actual — sending anyway")
        }

        send {
            varint(F_PROTOCOL_VERSION, 2)
            varint(F_STATUS, STATUS_OK)
            message(F_SECRET) { bytes(1, digest) }
        }
        expect(listOf(F_SECRET_ACK), "the television did not accept that code")
        Log.i(TAG, "paired with $host")
    }

    /**
     * Tidy a code as read off a screen.
     *
     * A television renders `0` in a font where it is hard to tell from `O`, and someone reading it
     * out will write what they see — which is how a perfectly good code got refused as invalid.
     * The substitutions are safe in one direction only, which is why they are safe at all: `O`,
     * `I` and `L` are not hexadecimal digits, so a code containing one is certainly a misread and
     * there is only one thing it can have been.
     */
    internal fun normalise(code: String): String =
        code.trim().filterNot { it.isWhitespace() || it == '-' }.uppercase()
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> it
                }
            }.joinToString("")

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(build: Protobuf.Writer.() -> Unit) {
        val body = Protobuf.Writer().apply(build).build()
        val out = output ?: throw IllegalStateException("not connected")
        out.write(Protobuf.frame(body))
        out.flush()
    }

    /** Read one message and require it to carry one of [accepted] with an OK status. */
    private fun expect(accepted: List<Int>, failure: String) {
        val fields = Protobuf.parse(readFramed())
        val status = fields.field(F_STATUS)?.varint?.toInt() ?: 0
        if (status != STATUS_OK) {
            throw IOException("$failure (status $status)")
        }
        if (accepted.none { fields.field(it) != null }) {
            val seen = fields.map { it.number }.joinToString(",")
            throw IOException("$failure — the television replied with field(s) $seen")
        }
    }

    private fun readFramed(): ByteArray {
        val ins = input ?: throw IllegalStateException("not connected")
        var length = 0L
        var shift = 0
        while (true) {
            val b = ins.read()
            if (b < 0) throw IOException("the television closed the connection")
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("the television sent a message length we can't read")
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
