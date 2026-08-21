package com.coxy.ghost.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * SSDP discovery: the UPnP half of the network that mDNS does not see.
 *
 * Televisions, media renderers, Sonos, and the router itself mostly speak UPnP rather than
 * Bonjour, so leaving this out would have made "everything on the network" mean "everything Apple
 * and Google conventions cover".
 *
 * The protocol is one multicast UDP datagram and then whatever replies within a few seconds — no
 * library, no dependency, and simple enough to read here in full. Devices stagger their replies
 * randomly across the `MX` window, which is why the read loop runs the whole window rather than
 * stopping at the first answer.
 */
object SsdpDiscovery {

    private const val TAG = "SsdpDiscovery"
    private const val ADDRESS = "239.255.255.250"
    private const val PORT = 1900

    /** Seconds devices may wait before replying. Their stagger window, and so also ours. */
    private const val MX = 3

    /** A little longer than MX, to catch the ones that answer at the very edge of it. */
    private const val LISTEN_MS = 5_000

    private val SEARCH = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $ADDRESS:$PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $MX\r\n")
        // ssdp:all rather than a specific target: the point here is an inventory, and asking for
        // one device class would return only that class.
        append("ST: ssdp:all\r\n")
        append("\r\n")
    }

    suspend fun discover(): List<LanDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LanDevice>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 800
                broadcast = true
            }
            val payload = SEARCH.toByteArray()
            val target = InetSocketAddress(InetAddress.getByName(ADDRESS), PORT)
            // Sent three times: UDP multicast is lossy and a dropped search is a silent empty
            // result. Consecutive scans of a real network returned six identified devices and then
            // five — the missing one being a television that simply did not answer that round.
            repeat(3) {
                runCatching { socket.send(DatagramPacket(payload, payload.size, target)) }
            }

            val deadline = System.currentTimeMillis() + LISTEN_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val ip = packet.address?.hostAddress ?: continue
                val text = String(packet.data, 0, packet.length)
                val device = parse(ip, text)
                found[ip] = found[ip]?.mergedWith(device) ?: device
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP discovery failed", e)
        } finally {
            socket?.close()
        }
        Log.i(TAG, "SSDP found ${found.size} devices")
        found.values.toList()
    }

    /**
     * Pull what little a search response carries: the `SERVER` banner, which is usually the most
     * human-readable thing a UPnP device ever says about itself, and the `ST`/`USN` service type.
     */
    internal fun parse(ip: String, response: String): LanDevice {
        val headers = response.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.take(i).trim().uppercase() to line.substring(i + 1).trim()
            }
            .toMap()

        val server = headers["SERVER"]
        val st = headers["ST"] ?: headers["NT"]
        val location = headers["LOCATION"]
        val port = location?.let { Regex(":(\\d{2,5})/").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        return LanDevice(
            ip = ip,
            // The SERVER banner is an OS/product string like "Linux/4.4 UPnP/1.0 Sonos/70.3".
            // Its last token is the product, which is the part worth showing.
            name = server?.split(' ')?.lastOrNull()?.takeIf { it.isNotBlank() },
            kind = kindFor(st, server),
            ports = setOfNotNull(port),
            services = setOfNotNull(st),
            descriptionUrls = setOfNotNull(location),
            seenBy = setOf(LanDevice.Source.SSDP)
        )
    }

    private fun kindFor(st: String?, server: String?): String? {
        val hay = "${st.orEmpty()} ${server.orEmpty()}".lowercase()
        return when {
            hay.contains("mediarenderer") -> "media player"
            hay.contains("mediaserver") -> "media server"
            hay.contains("internetgatewaydevice") || hay.contains("wanconnection") -> "router"
            hay.contains("printer") -> "printer"
            hay.contains("sonos") -> "Sonos speaker"
            hay.contains("hue") -> "Hue bridge"
            hay.contains("roku") -> "Roku"
            hay.contains("dial") -> "TV or streaming stick"
            else -> null
        }
    }
}
