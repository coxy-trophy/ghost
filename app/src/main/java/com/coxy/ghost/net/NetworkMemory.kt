package com.coxy.ghost.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.coxy.ghost.memory.GhostDatabase

/**
 * What GHOST knows about networks and the things on them, across visits.
 *
 * The scan cache could only answer "what is here now", and that made every command a gamble on
 * timing: SSDP is lossy, so a television that missed one round became a television that did not
 * exist, and "turn off the office TV" failed as though the name were wrong. Rows here outlive the
 * scan, so the answer becomes "that one, last seen four minutes ago" — or "not since Tuesday",
 * which is a different failure and a far more useful one.
 *
 * A scan therefore *adds to* what is known rather than replacing it. Nothing is ever deleted for
 * failing to answer; a device that is off is not a device that is gone.
 */
object NetworkMemory {

    private const val TAG = "NetworkMemory"

    /** Roughly how long a sighting stays "now" rather than "earlier". */
    const val FRESH_MS = 5 * 60_000L

    private fun dao(context: Context) = GhostDatabase.get(context).networkDao()

    /** The key a device is remembered under within its network. */
    fun stableKey(device: LanDevice): String =
        device.mac?.lowercase()?.let { "mac:$it" } ?: "ip:${device.ip}"

    /**
     * The network the phone is on now, created if it has not been seen before.
     *
     * @param gatewayMac the router's hardware address, which is the identity of the network.
     * @return the row, or null when off Wi-Fi or when the gateway did not answer the sweep.
     */
    suspend fun currentNetwork(
        context: Context,
        gatewayMac: String?,
        subnet: String,
        nowMs: Long
    ): KnownNetwork? {
        if (gatewayMac.isNullOrBlank()) {
            // Without the gateway there is no stable identity, and guessing from the subnet would
            // merge every 192.168.1.0/24 anyone has ever joined into one remembered network.
            Log.w(TAG, "no gateway MAC — this scan cannot be attributed to a network")
            return null
        }
        val mac = gatewayMac.lowercase()
        val existing = dao(context).networkByGateway(mac)
        if (existing != null) {
            val updated = existing.copy(
                subnet = subnet,
                lastSeenMs = nowMs,
                scanCount = existing.scanCount + 1,
                ssid = ssid(context) ?: existing.ssid
            )
            dao(context).updateNetwork(updated)
            return updated
        }
        val row = KnownNetwork(
            gatewayMac = mac,
            ssid = ssid(context),
            subnet = subnet,
            firstSeenMs = nowMs,
            lastSeenMs = nowMs
        )
        val id = dao(context).insertNetwork(row)
        Log.i(TAG, "first visit to $mac ($subnet)")
        return if (id > 0) row.copy(id = id) else dao(context).networkByGateway(mac)
    }

    /** Fold a scan's results into what is remembered about [networkId]. */
    suspend fun record(
        context: Context,
        networkId: Long,
        devices: List<LanDevice>,
        nowMs: Long
    ) {
        val d = dao(context)
        val legacyNames = runCatching { DeviceNames.all(context) }.getOrDefault(emptyMap())
        for (device in devices) {
            val key = stableKey(device)
            val existing = d.device(networkId, key)
            if (existing == null) {
                d.insertDevice(
                    KnownDevice(
                        networkId = networkId,
                        stableKey = key,
                        ip = device.ip,
                        mac = device.mac,
                        name = device.name,
                        // Carried over from the DataStore blob names used to live in, so a device
                        // named before this table existed keeps its name. That is the whole
                        // migration: names move across the first time each device is seen again.
                        nickname = device.nickname ?: legacyNames[key],
                        kind = device.kind,
                        services = device.services.joinToString(" "),
                        descriptionUrls = device.descriptionUrls.joinToString("\n"),
                        firstSeenMs = nowMs,
                        lastSeenMs = nowMs
                    )
                )
            } else {
                // Everything accumulates, and nothing learned is unlearned by a quiet round: a
                // scan where SSDP dropped a device must not erase the control URLs it gave last
                // time. Only the address and the advertised name are allowed to change.
                d.updateDevice(
                    existing.copy(
                        ip = device.ip,
                        mac = device.mac ?: existing.mac,
                        name = device.name ?: existing.name,
                        kind = device.kind ?: existing.kind,
                        services = (existing.serviceSet() + device.services).joinToString(" "),
                        descriptionUrls =
                            (existing.urlSet() + device.descriptionUrls).joinToString("\n"),
                        lastSeenMs = nowMs,
                        timesSeen = existing.timesSeen + 1
                    )
                )
            }
        }
    }

    /** A remembered row, shaped as a scan result so the matcher and executor need not care. */
    fun asLanDevice(known: KnownDevice): LanDevice = LanDevice(
        ip = known.ip,
        name = known.name,
        nickname = known.nickname,
        kind = known.kind,
        mac = known.mac,
        services = known.serviceSet(),
        descriptionUrls = known.urlSet(),
        lastSeenMs = known.lastSeenMs,
        seenBy = setOf(LanDevice.Source.REMEMBERED)
    )

    /**
     * This scan's devices, plus everything remembered on this network that did not answer.
     *
     * The union is the point. A command has to be able to land on a device that is merely switched
     * off, and get told so — rather than failing as though the name were wrong, which is what a
     * lossy SSDP round used to look like from the outside.
     */
    suspend fun withRemembered(
        context: Context,
        networkId: Long?,
        live: List<LanDevice>
    ): List<LanDevice> {
        if (networkId == null) return live
        val seen = live.map { stableKey(it) }.toSet()
        val recalled = runCatching { dao(context).devices(networkId) }.getOrDefault(emptyList())
            .filter { it.stableKey !in seen }
            .map { asLanDevice(it) }
        return live + recalled
    }

    /**
     * Attach what is remembered — the name Coxy gave it, and when it was last seen — to a live scan.
     *
     * Run *after* recording, so a device seen for the first time in this scan already has a row and
     * picks up any name migrated onto it. Room is the one place nicknames live now; keeping a
     * second copy in DataStore is what let a rename on the Devices screen fail to reach the thing
     * that resolves "the office TV".
     */
    suspend fun applyKnown(
        context: Context,
        networkId: Long?,
        live: List<LanDevice>
    ): List<LanDevice> {
        if (networkId == null) return live
        val known = runCatching { dao(context).devices(networkId) }.getOrDefault(emptyList())
            .associateBy { it.stableKey }
        return live.map { d ->
            val row = known[stableKey(d)] ?: return@map d
            if (row.nickname.isNullOrBlank()) d else d.copy(nickname = row.nickname)
        }
    }

    /** Name a device by its address on the current network. Used by the conversational path. */
    suspend fun nameByIp(context: Context, networkId: Long, ip: String, nickname: String): Boolean {
        val row = dao(context).devices(networkId).firstOrNull { it.ip == ip } ?: return false
        dao(context).nameDevice(row.id, nickname.trim())
        return true
    }

    suspend fun networks(context: Context): List<KnownNetwork> = dao(context).networks()

    suspend fun devices(context: Context, networkId: Long): List<KnownDevice> =
        dao(context).devices(networkId)

    suspend fun allDevices(context: Context): List<KnownDevice> = dao(context).allDevices()

    suspend fun name(context: Context, deviceId: Long, nickname: String?) =
        dao(context).nameDevice(deviceId, nickname?.trim()?.takeIf { it.isNotBlank() })

    suspend fun nameNetwork(context: Context, networkId: Long, nickname: String?) =
        dao(context).nameNetwork(networkId, nickname?.trim()?.takeIf { it.isNotBlank() })

    suspend fun setPaired(context: Context, deviceId: Long, paired: Boolean) =
        dao(context).setPaired(deviceId, paired)

    suspend fun forgetDevice(context: Context, deviceId: Long) = dao(context).forgetDevice(deviceId)

    suspend fun forgetNetwork(context: Context, networkId: Long) {
        dao(context).forgetDevicesOn(networkId)
        dao(context).forgetNetwork(networkId)
    }

    suspend fun markPairedByIp(context: Context, networkId: Long, ip: String) {
        dao(context).devices(networkId).firstOrNull { it.ip == ip }
            ?.let { dao(context).setPaired(it.id, true) }
    }

    /**
     * The Wi-Fi name, when it can be read.
     *
     * Best-effort by design. It needs location permission, is restricted in the background on
     * modern Android, and comes back as the literal string `<unknown ssid>` when refused — which is
     * why the identity of a network is its gateway and this is only ever a label.
     */
    private fun ssid(context: Context): String? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        wifi?.connectionInfo?.ssid?.trim('"')
            ?.takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
    }.getOrNull()
}
