package com.coxy.ghost.net

import android.content.Context
import android.util.Log
import com.coxy.ghost.GhostPrefs
import org.json.JSONObject

/**
 * What Coxy calls a device, as opposed to what it calls itself.
 *
 * The live network made the case for this on its own: all three televisions advertise as
 * `Smart-TV-4df02b09600928db702d41131980b458` and nothing else. Nobody says that out loud, "the TV"
 * is a three-way question, and the whole control layer is unusable until a device can be named.
 *
 * Keyed by MAC where one is known, because that survives what an IP does not — a DHCP lease
 * expiring overnight would otherwise silently point "the office TV" at whichever device took the
 * address next, which is exactly the wrong-device-silently failure the control layer is built to
 * avoid. Addresses are the fallback for devices only mDNS saw, and are marked as the weaker key
 * they are.
 */
object DeviceNames {

    private const val TAG = "DeviceNames"

    /** The stable identity of a device, for naming purposes. */
    internal fun keyFor(device: LanDevice): String =
        device.mac?.lowercase()?.let { "mac:$it" } ?: "ip:${device.ip}"

    suspend fun all(context: Context): Map<String, String> =
        parse(GhostPrefs.getDeviceNames(context))

    /** Give [device] a name. Returns the key it was stored under. */
    suspend fun set(context: Context, device: LanDevice, name: String): String {
        val key = keyFor(device)
        val updated = all(context).toMutableMap().apply { this[key] = name.trim() }
        GhostPrefs.setDeviceNames(context, render(updated))
        Log.i(TAG, "named $key \"$name\"")
        return key
    }

    suspend fun clear(context: Context, device: LanDevice) {
        val updated = all(context).toMutableMap().apply { remove(keyFor(device)) }
        GhostPrefs.setDeviceNames(context, render(updated))
    }

    /** Attach stored nicknames to a freshly scanned list. */
    fun apply(devices: List<LanDevice>, names: Map<String, String>): List<LanDevice> {
        if (names.isEmpty()) return devices
        return devices.map { d ->
            val nickname = names[keyFor(d)]
                // A device can be named by address and later gain a MAC, or the reverse. Checking
                // both keys means a name given once does not vanish when a scan learns more.
                ?: d.mac?.lowercase()?.let { names["mac:$it"] }
                ?: names["ip:${d.ip}"]
            if (nickname.isNullOrBlank()) d else d.copy(nickname = nickname)
        }
    }

    internal fun parse(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) {
                    val value = obj.optString(key).trim()
                    if (value.isNotEmpty()) put(key, value)
                }
            }
        }.getOrElse {
            Log.w(TAG, "stored device names were unreadable; starting empty", it)
            emptyMap()
        }
    }

    internal fun render(names: Map<String, String>): String =
        JSONObject().apply { for ((k, v) in names) put(k, v) }.toString()
}
