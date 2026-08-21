package com.coxy.ghost.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * mDNS discovery: the half of the network that knows what it is.
 *
 * SSDP tells you a device exists; Bonjour tells you it is a Chromecast called "Living Room" that
 * will take a cast on port 8009. That distinction is the whole reason this is here — a control
 * layer needs the service type, not just an address.
 *
 * Android's [NsdManager] cannot enumerate service *types*, only instances of a type you already
 * name, so the list below is the discovery: anything advertising a type not on it is invisible
 * here (it will still turn up in the ARP sweep, just unidentified). The types are chosen for what
 * they imply about control rather than for completeness.
 */
object MdnsDiscovery {

    private const val TAG = "MdnsDiscovery"

    /** How long to let each type's browse run. Devices answer fast; stragglers are not worth it. */
    private const val BROWSE_MS = 4_000L

    /**
     * NsdManager is documented as supporting a limited number of concurrent operations and is
     * unreliable well before any documented limit, so the types are browsed in small batches.
     */
    private const val BATCH = 4

    /** Service type to what it means, in words Coxy would use. */
    private val TYPES = linkedMapOf(
        "_googlecast._tcp." to "casts",
        "_androidtvremote2._tcp." to "Android TV",
        "_airplay._tcp." to "AirPlay",
        "_raop._tcp." to "AirPlay speaker",
        "_spotify-connect._tcp." to "Spotify Connect",
        "_hue._tcp." to "Hue bridge",
        "_home-assistant._tcp." to "Home Assistant",
        "_hap._tcp." to "HomeKit accessory",
        "_matter._tcp." to "Matter device",
        "_printer._tcp." to "printer",
        "_ipp._tcp." to "printer",
        "_smb._tcp." to "file share",
        "_workstation._tcp." to "computer",
        "_http._tcp." to "web interface"
    )

    suspend fun discover(context: Context): List<LanDevice> = withContext(Dispatchers.IO) {
        val nsd = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as NsdManager
        }.getOrElse {
            Log.w(TAG, "NsdManager unavailable", it)
            return@withContext emptyList()
        }

        val found = ConcurrentHashMap<String, LanDevice>()
        for (batch in TYPES.entries.chunked(BATCH)) {
            val listeners = batch.mapNotNull { (type, kind) ->
                runCatching { browse(nsd, type, kind, found) }.getOrNull()
            }
            withTimeoutOrNull(BROWSE_MS) { CompletableDeferred<Unit>().await() }
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
        Log.i(TAG, "mDNS found ${found.size} devices")
        found.values.toList()
    }

    private fun browse(
        nsd: NsdManager,
        type: String,
        kind: String,
        found: ConcurrentHashMap<String, LanDevice>
    ): NsdManager.DiscoveryListener {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start failed for $serviceType: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // A browse result carries a name but no address; only a resolve produces one, and
                // resolves must be serialised or NsdManager returns FAILURE_ALREADY_ACTIVE.
                nsd.resolveService(serviceInfo, resolveListener(type, kind, found))
            }
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    private fun resolveListener(
        type: String,
        kind: String,
        found: ConcurrentHashMap<String, LanDevice>
    ) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val ip = serviceInfo.host?.hostAddress ?: return
            val device = LanDevice(
                ip = ip,
                name = serviceInfo.serviceName?.takeIf { it.isNotBlank() },
                kind = kind,
                ports = setOf(serviceInfo.port).filter { it > 0 }.toSet(),
                services = setOf(type.trimEnd('.')),
                seenBy = setOf(LanDevice.Source.MDNS)
            )
            found.merge(ip, device) { old, new -> old.mergedWith(new) }
        }
    }
}
