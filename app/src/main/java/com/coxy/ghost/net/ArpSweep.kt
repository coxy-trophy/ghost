package com.coxy.ghost.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything with an address, including the things that advertise nothing.
 *
 * mDNS and SSDP only see devices that choose to announce themselves, which leaves out most laptops,
 * most phones, and anything with its discovery protocols switched off — so on their own they cannot
 * answer "what is on my network". This can.
 *
 * It works by pinging every host in the local /24 in parallel and then reading the kernel's
 * neighbour table, which the replies have just populated. The ping is the point: `ip neigh` on its
 * own only lists what the phone happens to have spoken to recently, which after a fresh join is
 * usually the router and nothing else.
 *
 * Root is required and not worked around. `/proc/net/arp` has been unreadable to apps since
 * Android 10, and a scan that silently returned only the gateway would be worse than one that says
 * it needs root — GHOST already runs as a privileged app with a root helper, so this is a
 * capability she has rather than one being taken.
 *
 * Scope is deliberately one /24 and nothing else. That is the home network the phone is joined to;
 * widening it would be scanning someone else's.
 */
object ArpSweep {

    private const val TAG = "ArpSweep"

    /** Ping timeout per host, in seconds. Anything slower than this on a LAN is not answering. */
    private const val PING_WAIT_S = 1

    /** Whole-sweep ceiling. 254 parallel pings finish in about two seconds on a quiet network. */
    private const val TIMEOUT_MS = 20_000L

    /**
     * Sweep the /24 containing [localIp] and return what answered.
     *
     * @param localIp the phone's own address on the Wi-Fi network, e.g. `192.168.1.37`.
     */
    suspend fun sweep(localIp: String): List<LanDevice> = withContext(Dispatchers.IO) {
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isEmpty() || prefix.count { it == '.' } != 2) {
            Log.w(TAG, "not a usable IPv4 address: $localIp")
            return@withContext emptyList()
        }

        // One shell round trip rather than 254. Backgrounding every ping and waiting once is the
        // difference between two seconds and four minutes.
        val script =
            "for i in $(seq 1 254); do ping -c 1 -W $PING_WAIT_S $prefix.\$i >/dev/null 2>&1 & done; " +
                "wait; ip neigh show"

        val output = runCatching { exec(script) }.getOrElse {
            Log.w(TAG, "sweep failed (root available?)", it)
            return@withContext emptyList()
        }

        val devices = output.lineSequence().mapNotNull { parseNeighbour(it, prefix) }.toList()
        Log.i(TAG, "ARP sweep found ${devices.size} hosts on $prefix.0/24")
        devices
    }

    /**
     * Parse one `ip neigh show` line, e.g.
     * `192.168.1.1 dev wlan0 lladdr a4:2b:b0:11:22:33 REACHABLE`.
     *
     * `FAILED` and `INCOMPLETE` entries are dropped: they are addresses that were pinged and did
     * not answer, which is the sweep's own footprint rather than a device.
     */
    internal fun parseNeighbour(line: String, prefix: String): LanDevice? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val ip = parts[0]
        if (!ip.startsWith("$prefix.")) return null
        if (line.contains("FAILED") || line.contains("INCOMPLETE")) return null
        val mac = parts.getOrNull(parts.indexOf("lladdr") + 1)
            ?.takeIf { parts.contains("lladdr") && it.count { c -> c == ':' } == 5 }
        return LanDevice(ip = ip, mac = mac, seenBy = setOf(LanDevice.Source.ARP))
    }

    /**
     * Run [command] as root with a hard ceiling.
     *
     * Not [com.coxy.ghost.action.SuExecutor]'s `exec`, which reads stdout on the calling thread and
     * then waits: a sweep that hangs would block until the process was killed by something else.
     * stdout is drained on its own thread here so a wedged `wait` cannot deadlock the read, and the
     * process is destroyed outright at the timeout.
     */
    private fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
            }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw java.io.IOException("sweep timed out")
        }
        drain.join(2_000)
        return stdout.toString().trim()
    }
}
