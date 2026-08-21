package com.coxy.ghost.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN: six bytes of 0xFF followed by the target's MAC sixteen times.
 *
 * The whole protocol is that one packet, which is why it needs no library and no reply handling —
 * there is no acknowledgement, by design. A machine that is off cannot say it heard you, so success
 * here means "the packet went out", never "the machine woke up", and the wording GHOST uses has to
 * keep that distinction.
 *
 * Sent to the subnet broadcast rather than 255.255.255.255: a sleeping NIC is listening on the
 * network segment, and the limited broadcast is dropped by more equipment than the directed one.
 */
object WakeOnLan {

    private const val TAG = "WakeOnLan"

    /** Port 9 (discard) is the convention. Port 7 also works on most hardware. */
    private const val PORT = 9

    /** Parse `aa:bb:cc:dd:ee:ff` (or with dashes) into six bytes, or null if it is not a MAC. */
    internal fun parseMac(mac: String): ByteArray? {
        val parts = mac.trim().split(':', '-')
        if (parts.size != 6) return null
        return parts.map {
            it.toIntOrNull(16)?.takeIf { v -> v in 0..255 } ?: return null
        }.map { it.toByte() }.toByteArray()
    }

    internal fun magicPacket(mac: ByteArray): ByteArray {
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
        return packet
    }

    /**
     * @param mac the target's hardware address, from the ARP table.
     * @param localIp the phone's own address, used to derive the subnet broadcast.
     */
    suspend fun wake(mac: String, localIp: String): Result<Unit> = withContext(Dispatchers.IO) {
        val bytes = parseMac(mac)
            ?: return@withContext Result.failure(IOException("'$mac' is not a hardware address"))
        val broadcastIp = localIp.substringBeforeLast('.', "").let {
            if (it.count { c -> c == '.' } == 2) "$it.255" else null
        } ?: return@withContext Result.failure(IOException("can't work out the broadcast address"))

        var socket: DatagramSocket? = null
        try {
            // Named broadcastIp, not broadcast: inside apply{} the latter resolves to the
            // socket's own property and the assignment silently means something else.
            socket = DatagramSocket().apply { broadcast = true }
            val payload = magicPacket(bytes)
            val target = InetAddress.getByName(broadcastIp)
            // Three times: this is fire-and-forget UDP with no acknowledgement, so a single
            // dropped packet would be a silent no-op.
            repeat(3) { socket.send(DatagramPacket(payload, payload.size, target, PORT)) }
            Log.i(TAG, "magic packet sent to $mac via $broadcastIp")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "wake failed", e)
            Result.failure(IOException("could not send the packet (${e.javaClass.simpleName})"))
        } finally {
            socket?.close()
        }
    }
}
