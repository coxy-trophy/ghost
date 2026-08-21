package com.coxy.ghost.net

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A network the phone has been on.
 *
 * Identified by the **gateway's MAC address**, not by SSID or subnet. Two networks are routinely
 * `192.168.1.0/24`, an SSID can be duplicated by anyone with a router, and reading the SSID at all
 * needs location permission and is restricted in the background on modern Android. The gateway's
 * hardware address is none of those things: it comes free with the ARP sweep, it is unique, and it
 * survives the network being renamed.
 *
 * [ssid] is stored anyway when it can be had, because "Home" is what Coxy calls it and
 * `aa:bb:cc:11:22:33` is not.
 */
@Entity(tableName = "known_network", indices = [Index(value = ["gatewayMac"], unique = true)])
data class KnownNetwork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The router's hardware address. The identity of the network. */
    val gatewayMac: String,
    /** Wi-Fi name, when it could be read. Null is common and not a fault. */
    val ssid: String? = null,
    /** What Coxy calls it, if he has said. */
    val nickname: String? = null,
    /** e.g. `192.168.100.0/24`. Descriptive; networks do renumber. */
    val subnet: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** How many separate scans have been run here. */
    val scanCount: Int = 1
) {
    /** What to call it: his name, then its SSID, then the subnet. */
    val label: String get() = nickname ?: ssid ?: subnet
}

/**
 * A device seen on a network, remembered between visits.
 *
 * The scan cache could only ever answer "what is here now", which made every command a gamble on
 * timing: a television that did not answer one lossy SSDP round became a television that did not
 * exist, and "turn off the office TV" failed as though the name were wrong. Rows here outlive the
 * scan, so the answer becomes "that one, last seen four minutes ago" — or "not since Tuesday",
 * which is a different and more useful failure.
 *
 * Keyed by MAC within a network where one is known. A device only ever seen over mDNS has no MAC
 * and falls back to its address, which is weaker and marked as such in [stableKey].
 */
@Entity(
    tableName = "known_device",
    indices = [Index(value = ["networkId", "stableKey"], unique = true), Index(value = ["networkId"])]
)
data class KnownDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    /** `mac:aa:bb:...` or `ip:192.168.1.5`. Unique within a network. */
    val stableKey: String,
    val ip: String,
    val mac: String? = null,
    /** What the device advertises, e.g. `Smart-TV-4df02b09...`. */
    val name: String? = null,
    /** What Coxy calls it. Beats [name] everywhere. */
    val nickname: String? = null,
    /** "casts", "printer", "router" — what it appears to be. */
    val kind: String? = null,
    /** Space-separated service types, for capability matching. */
    val services: String = "",
    /** Newline-separated UPnP description URLs. */
    val descriptionUrls: String = "",
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val timesSeen: Int = 1,
    /** True once a television has accepted GHOST's remote-control certificate. */
    val paired: Boolean = false
) {
    val bestName: String? get() = nickname ?: name
    val isIdentified: Boolean get() = nickname != null || name != null || kind != null

    fun serviceSet(): Set<String> = services.split(' ').filter { it.isNotBlank() }.toSet()
    fun urlSet(): Set<String> = descriptionUrls.split('\n').filter { it.isNotBlank() }.toSet()

    /** Can it be told to play, pause or change volume? */
    val canControlMedia: Boolean get() = serviceSet().any { it.contains("AVTransport") || it.contains("RenderingControl") }

    /** Is it an Android TV, and so pairable for real remote keys? */
    val isAndroidTv: Boolean get() = serviceSet().any { it.contains("androidtvremote") }

    /** Can a magic packet be sent to it? */
    val canWake: Boolean get() = mac != null
}
