package com.coxy.ghost.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * What is on the home network, from the three discoveries that each see a different slice.
 *
 * Gated on Wi-Fi and nothing else. On mobile data there is no "home network" to look at — the
 * carrier's CGNAT range is not Coxy's, and sweeping it would be scanning strangers. Off Wi-Fi this
 * returns [Result.NotOnWifi] rather than an empty list, because "nothing found" and "not looking"
 * are different answers and GHOST should be able to say which one happened.
 */
object LanScanner {

    private const val TAG = "LanScanner"

    /**
     * How long a scan stays good for.
     *
     * A full sweep takes several seconds and wakes every device on the network, so repeating it per
     * question would be both slow and rude. Two minutes is long enough that a conversation about
     * the network runs off one scan, and short enough that a device switched on mid-conversation
     * turns up when asked about again.
     */
    private const val TTL_MS = 120_000L

    sealed interface Result {
        data class Scanned(
            val devices: List<LanDevice>,
            val subnet: String,
            /** The remembered network this scan belongs to, if the gateway answered. */
            val network: KnownNetwork? = null
        ) : Result
        data object NotOnWifi : Result
        data class Failed(val reason: String) : Result
    }

    @Volatile private var cache: Result.Scanned? = null
    @Volatile private var scannedAt = 0L

    /**
     * Control endpoints learned about a device, keyed by MAC or address, accumulated across scans.
     *
     * SSDP is multicast UDP and genuinely lossy: consecutive scans of this network returned six
     * identified devices and then five, and the television that dropped out took its control URLs
     * with it — so "pause the office TV" worked or failed depending on which round had last run.
     * A device's UPnP description does not come and go, so once seen it is remembered, and a scan
     * that misses it no longer takes the capability away.
     */
    private val knownUrls = HashMap<String, Set<String>>()

    fun invalidate() {
        cache = null
        scannedAt = 0L
    }

    /** The last scan, without running one. Null if nothing has been scanned this process. */
    fun cached(): Result? = cache

    /**
     * The remembered network the phone is on, without scanning.
     *
     * Deliberately not read off the scan cache. That cache expires after two minutes because a
     * *scan* goes stale; the network does not, and deriving "you are here now" from it made the
     * current network stop being marked as current two minutes after arriving on it.
     *
     * Confirmed against the live subnet rather than trusted outright, so walking to a different
     * Wi-Fi does not leave the old network wearing the badge.
     */
    suspend fun currentNetwork(context: Context): KnownNetwork? {
        val ip = wifiIpv4(context) ?: return null
        val subnet = "${ip.substringBeforeLast('.')}.0/24"
        val remembered = lastNetwork
        if (remembered != null && remembered.subnet == subnet) return remembered
        // Not scanned yet this process: the subnet alone is a weak match — two networks are
        // routinely 192.168.1.0/24 — so this only stands until a scan settles it properly.
        return runCatching { NetworkMemory.networks(context) }.getOrDefault(emptyList())
            .firstOrNull { it.subnet == subnet }
    }

    @Volatile private var lastNetwork: KnownNetwork? = null

    /** Forget learned endpoints too. Only for tests and a genuine "start over". */
    fun forgetEverything() {
        invalidate()
        lastNetwork = null
        synchronized(knownUrls) { knownUrls.clear() }
        UpnpControl.invalidate()
    }

    /** Fold this scan's endpoints into what is already known, and hand every device the union. */
    private fun rememberUrls(devices: List<LanDevice>): List<LanDevice> =
        synchronized(knownUrls) {
            devices.map { d ->
                val key = d.mac?.lowercase()?.let { "mac:$it" } ?: "ip:${d.ip}"
                val merged = (knownUrls[key] ?: emptySet()) + d.descriptionUrls
                if (merged.isEmpty()) d else {
                    knownUrls[key] = merged
                    d.copy(descriptionUrls = merged)
                }
            }
        }

    /** The cached scan if it is still fresh, otherwise a new one. */
    suspend fun scan(context: Context, force: Boolean = false): Result {
        val localIp = wifiIpv4(context) ?: return Result.NotOnWifi

        val cached = cache
        if (!force && cached != null && SystemClock.elapsedRealtime() - scannedAt < TTL_MS) {
            return cached
        }

        // The lock wraps the whole scan, not just the two multicast discoveries, because it is
        // cheaper to hold it for one extra second than to reason about when the last reply lands.
        return withMulticast(context) {
            coroutineScope {
                // All three run at once: mDNS and SSDP are mostly waiting on a listen window, and
                // the sweep is mostly waiting on 254 pings. In sequence this would be three windows
                // back to back for no benefit.
                val mdns = async { runCatching { MdnsDiscovery.discover(context) }.getOrDefault(emptyList()) }
                val ssdp = async { runCatching { SsdpDiscovery.discover() }.getOrDefault(emptyList()) }
                val arp = async { runCatching { ArpSweep.sweep(localIp) }.getOrDefault(emptyList()) }

                // Order is precedence: mDNS names a device best, SSDP second, ARP not at all.
                // Coxy's own names go on last and beat all three, because a television calls
                // itself Smart-TV-4df02b09600928db702d41131980b458 and he does not.
                val devices = com.coxy.ghost.net.DeviceNames.apply(
                    rememberUrls(
                        LanDevice.merge(mdns.await() + ssdp.await() + arp.await())
                            .filterNot { it.ip == localIp }
                    ),
                    com.coxy.ghost.net.DeviceNames.all(context)
                )


                if (devices.isEmpty()) {
                    Log.w(TAG, "scan found nothing on ${localIp.substringBeforeLast('.')}.0/24")
                }
                // The gateway is x.x.x.1 in practice on every consumer router, and its MAC is what
                // identifies this network across visits. Falling back to whatever answered at .1 is
                // better than nothing; a network with no gateway sighting simply is not recorded.
                val prefix = localIp.substringBeforeLast('.')
                val gatewayMac = devices.firstOrNull { it.ip == "$prefix.1" }?.mac
                val now = System.currentTimeMillis()
                val network = runCatching {
                    NetworkMemory.currentNetwork(context, gatewayMac, "$prefix.0/24", now)
                }.getOrNull()
                if (network != null) {
                    runCatching { NetworkMemory.record(context, network.id, devices, now) }
                }
                // Names come back out of Room afterwards, not from the DataStore blob that was
                // applied above: recording first means a device seen for the first time already
                // has a row, and reading second means a rename made on the Devices screen reaches
                // the matcher without waiting for anything.
                val named = runCatching {
                    NetworkMemory.applyKnown(context, network?.id, devices)
                }.getOrDefault(devices)

                lastNetwork = network
                val result = Result.Scanned(named, "$prefix.0/24", network)
                cache = result
                scannedAt = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "scan: ${named.size} devices (${named.count { it.isIdentified }} identified)" +
                        (network?.let { " on ${it.label}" } ?: " — network unidentified")
                )
                // The identified ones logged individually: they are the only ones a request can
                // land on, and when control misbehaves this line is the difference between
                // "the TV ignored her" and "there was never a TV at that address".
                named.filter { it.isIdentified }.forEach {
                    Log.i(TAG, "  ${it.ip} ${it.kind ?: "?"} \"${it.bestName ?: "?"}\" " +
                        "svc=${it.services} urls=${it.descriptionUrls}")
                }
                result
            }
        }
    }

    /**
     * The phone's own IPv4 address on Wi-Fi, or null if Wi-Fi is not the active network.
     *
     * Read from [LinkProperties] rather than the long-deprecated `WifiManager.connectionInfo.ipAddress`,
     * which returns a little-endian int and is empty on modern Android anyway.
     */
    fun wifiIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    /**
     * Hold a multicast lock for [block].
     *
     * Wi-Fi hardware filters multicast when idle to save power, which silently drops both mDNS and
     * SSDP replies — the symptom is an empty scan on a network full of devices, with no error
     * anywhere. The lock is scoped to the scan rather than held permanently because it costs real
     * battery.
     */
    suspend fun <T> withMulticast(context: Context, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = runCatching {
                wifi?.createMulticastLock("ghost-lan-scan")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }.getOrNull()
            try {
                block()
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
        }
}
