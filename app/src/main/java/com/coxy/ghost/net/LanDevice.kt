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
    /**
     * What Coxy calls it, if he has said. Beats [name] everywhere it matters, because the thing a
     * device advertises is often `Smart-TV-4df02b09600928db702d41131980b458`.
     */
    val nickname: String? = null,
    /** What it appears to be — "casts", "Hue bridge", "printer". Null when only ARP saw it. */
    val kind: String? = null,
    /** Hardware address, when the ARP table supplied one. The vendor prefix is often the only clue. */
    val mac: String? = null,
    /** Ports worth knowing about, from whichever source advertised them. */
    val ports: Set<Int> = emptySet(),
    /** Raw service types seen, e.g. `_googlecast._tcp`. Kept for the control layer to match on. */
    val services: Set<String> = emptySet(),
    /**
     * UPnP device-description URLs, from SSDP's `LOCATION` headers.
     *
     * A **set**, because one address is often several UPnP root devices. An Android TV answers on
     * port 8008 with a DIAL description *and* separately with a MediaRenderer description at
     * another location — and keeping only the first is why "pause the TV" came back as "doesn't
     * take playback commands" from a television that advertises AVTransport plainly.
     *
     * These are the entry point to controlling anything: each XML lists the services that device
     * exposes and the control URL for each.
     */
    val descriptionUrls: Set<String> = emptySet(),
    /**
     * When this device was last actually seen, if it is being recalled rather than observed.
     *
     * Null means "answering right now". A value means it is remembered from a previous scan and
     * may well be switched off — which is a thing to say out loud rather than to hide, because
     * "the office TV, but it hasn't answered since Tuesday" and "there is no office TV" are very
     * different answers to the same command.
     */
    val lastSeenMs: Long? = null,
    /** Which discoveries saw it. Useful when a device is present but unidentifiable. */
    val seenBy: Set<Source> = emptySet()
) {
    enum class Source { MDNS, SSDP, ARP, REMEMBERED }

    /** True if this is a recollection rather than something that just answered. */
    val isStale: Boolean get() = lastSeenMs != null

    /** True if anything is known beyond "an address responded". */
    val isIdentified: Boolean get() = nickname != null || name != null || kind != null

    /** What to call it: his name for it if he has one, otherwise its own. */
    val bestName: String? get() = nickname ?: name

    /**
     * Merge two sightings of the same address, preferring whichever actually knows something.
     * mDNS names beat SSDP names beat nothing, and every source's ports and services accumulate.
     */
    fun mergedWith(other: LanDevice): LanDevice = LanDevice(
        ip = ip,
        name = name ?: other.name,
        nickname = nickname ?: other.nickname,
        kind = kind ?: other.kind,
        mac = mac ?: other.mac,
        descriptionUrls = descriptionUrls + other.descriptionUrls,
        // A live sighting always wins over a recalled one.
        lastSeenMs = if (lastSeenMs == null || other.lastSeenMs == null) null
                     else maxOf(lastSeenMs, other.lastSeenMs),
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
