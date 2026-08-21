package com.coxy.ghost.net

/**
 * One thing on the home network.
 *
 * Built from up to three sources that each see a different slice, and merged by [ip]:
 *
 * - **mDNS** knows what a device *is* and what it will answer to — "Living Room TV, casts" — but
 *   only sees devices that advertise themselves.
 * - **SSDP** covers the UPnP half that mDNS misses: TVs, media renderers, the router itself.
 * - **The ARP table** sees everything with an address, including the laptops and phones that
 *   advertise nothing, but knows nothing about them beyond a MAC.
 *
 * Which is why [name] and [kind] are nullable and [ip] is not. A device that only ARP found is a
 * real answer to "what is on my network" and a useless answer to "turn the lights off", and the
 * shape of this type says so rather than inventing a name to fill the gap.
 */
data class LanDevice(
    val ip: String,
    /** Advertised name, if anything advertised one. */
    val name: String? = null,
    /** What it appears to be — "casts", "Hue bridge", "printer". Null when only ARP saw it. */
    val kind: String? = null,
    /** Hardware address, when the ARP table supplied one. The vendor prefix is often the only clue. */
    val mac: String? = null,
    /** Ports worth knowing about, from whichever source advertised them. */
    val ports: Set<Int> = emptySet(),
    /** Raw service types seen, e.g. `_googlecast._tcp`. Kept for the control layer to match on. */
    val services: Set<String> = emptySet(),
    /**
     * UPnP device-description URL, from SSDP's `LOCATION` header.
     *
     * Kept because it is the entry point to actually controlling the device: the XML it returns
     * lists the services it exposes and the control URL for each. Without it a MediaRenderer is
     * just an address that admitted to being a MediaRenderer.
     */
    val descriptionUrl: String? = null,
    /** Which discoveries saw it. Useful when a device is present but unidentifiable. */
    val seenBy: Set<Source> = emptySet()
) {
    enum class Source { MDNS, SSDP, ARP }

    /** True if anything is known beyond "an address responded". */
    val isIdentified: Boolean get() = name != null || kind != null

    /**
     * Merge two sightings of the same address, preferring whichever actually knows something.
     * mDNS names beat SSDP names beat nothing, and every source's ports and services accumulate.
     */
    fun mergedWith(other: LanDevice): LanDevice = LanDevice(
        ip = ip,
        name = name ?: other.name,
        kind = kind ?: other.kind,
        mac = mac ?: other.mac,
        descriptionUrl = descriptionUrl ?: other.descriptionUrl,
        ports = ports + other.ports,
        services = services + other.services,
        seenBy = seenBy + other.seenBy
    )

    companion object {
        /**
         * Collapse a mixed list into one entry per address.
         *
         * Order matters: earlier entries win on name and kind, so callers pass the discoveries that
         * know the most first. Sorted by address at the end, numerically within the last octet, so
         * a list of twenty devices reads like a network rather than like a hash order.
         */
        fun merge(all: List<LanDevice>): List<LanDevice> {
            val byIp = LinkedHashMap<String, LanDevice>()
            for (d in all) {
                byIp[d.ip] = byIp[d.ip]?.mergedWith(d) ?: d
            }
            return byIp.values.sortedWith(compareBy({ it.ip.substringBeforeLast('.') }, {
                it.ip.substringAfterLast('.').toIntOrNull() ?: 0
            }))
        }
    }
}
