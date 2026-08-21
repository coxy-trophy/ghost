package com.coxy.ghost.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Which addresses have Android's debug bridge listening.
 *
 * A plain TCP connect and nothing more. Completing an adb handshake would raise the "Allow
 * debugging from this device?" dialog on somebody's phone purely because a scan ran, and a scan
 * must never be the reason a stranger's phone asks them a question. Whether the connection would
 * be *accepted* is answered later, by a command Coxy actually asked for.
 */
object AdbProbe {

    private const val TAG = "AdbProbe"

    /** Recorded on the device row like any other service, so capabilities read uniformly. */
    const val SERVICE = "_adb._tcp"

    private const val PORT = 5555
    private const val TIMEOUT_MS = 400

    /** How many sockets at once. Enough to sweep a /24 quickly, few enough not to thrash. */
    private const val PARALLEL = 24

    suspend fun probe(addresses: List<String>): Set<String> = withContext(Dispatchers.IO) {
        val open = mutableSetOf<String>()
        for (batch in addresses.chunked(PARALLEL)) {
            coroutineScope {
                batch.map { ip ->
                    async {
                        val reachable = runCatching {
                            Socket().use {
                                it.connect(InetSocketAddress(ip, PORT), TIMEOUT_MS)
                                true
                            }
                        }.getOrDefault(false)
                        if (reachable) ip else null
                    }
                }.awaitAll().filterNotNull().let { open.addAll(it) }
            }
        }
        if (open.isNotEmpty()) Log.i(TAG, "debug bridge open on ${open.joinToString()}")
        open
    }
}
