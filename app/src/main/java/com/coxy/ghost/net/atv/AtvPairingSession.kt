package com.coxy.ghost.net.atv

import android.util.Log

/**
 * The one pairing currently in progress, if any.
 *
 * Pairing cannot complete inside a single turn: the television puts a code on screen and someone
 * has to read it out, which takes a sentence from Coxy and therefore another message. The TLS
 * connection has to stay open across that gap — the code is only valid for the session that
 * produced it — so the session outlives the turn that started it, which nothing else in the action
 * layer does.
 *
 * Deliberately one at a time. Two half-finished pairings would mean a code arriving with no way to
 * tell which television it came from, and the cost of that guess is pairing the wrong screen.
 */
object AtvPairingSession {

    private const val TAG = "AtvPairingSession"

    /** How long a code is worth waiting for before the socket is assumed stale. */
    private const val EXPIRY_MS = 5 * 60_000L

    private var pairing: AtvPairing? = null
    private var host: String? = null
    private var startedAt = 0L

    val pendingHost: String? get() = if (isLive()) host else null

    @Synchronized
    fun start(newHost: String, session: AtvPairing) {
        abandon()
        pairing = session
        host = newHost
        startedAt = android.os.SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun take(): Pair<String, AtvPairing>? {
        if (!isLive()) {
            abandon()
            return null
        }
        val p = pairing ?: return null
        val h = host ?: return null
        return h to p
    }

    @Synchronized
    fun done() {
        pairing = null
        host = null
        startedAt = 0L
    }

    @Synchronized
    fun abandon() {
        runCatching { pairing?.close() }
        if (pairing != null) Log.i(TAG, "abandoned pairing with $host")
        done()
    }

    private fun isLive(): Boolean =
        pairing != null && android.os.SystemClock.elapsedRealtime() - startedAt < EXPIRY_MS
}
