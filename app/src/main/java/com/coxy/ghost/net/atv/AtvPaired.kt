package com.coxy.ghost.net.atv

import android.content.Context
import com.coxy.ghost.GhostPrefs
import org.json.JSONArray

/**
 * Which televisions have been paired.
 *
 * The television is the authority — it either accepts GHOST's certificate or it does not — so this
 * is a note rather than a source of truth. It exists so GHOST can answer "can you turn the TV off?"
 * without opening a TLS connection to find out, and so a failure can say "that one isn't paired"
 * rather than the SSL error it actually saw.
 */
object AtvPaired {

    suspend fun hosts(context: Context): Set<String> {
        val json = GhostPrefs.getPairedTvs(context) ?: return emptySet()
        return runCatching {
            val array = JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
        }.getOrDefault(emptySet()).filter { it.isNotBlank() }.toSet()
    }

    suspend fun remember(context: Context, host: String) {
        val updated = hosts(context) + host
        GhostPrefs.setPairedTvs(context, JSONArray(updated.toList()).toString())
    }

    suspend fun forget(context: Context, host: String) {
        val updated = hosts(context) - host
        GhostPrefs.setPairedTvs(context, JSONArray(updated.toList()).toString())
    }
}
