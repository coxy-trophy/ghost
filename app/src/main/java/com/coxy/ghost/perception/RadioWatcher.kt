package com.coxy.ghost.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Phase 4.5: the radios, as context rather than as controls.
 *
 * **Telephony.** Call state is the valuable part — "he is on a call" is the clearest possible
 * signal that GHOST should not start talking. Signal strength and network generation are kept
 * because a weak signal explains why a cloud reply is slow or fails. This reads *state only*: no
 * numbers, no contacts, no messages, no subscriber identifiers.
 *
 * **NFC.** Reported as an on/off capability and nothing more. NFC is not an ambient sensor — a tag
 * read requires a physical tap into a foreground activity's reader mode, which a background daemon
 * cannot do. Included for completeness; it will never produce a reading on its own.
 *
 * **Biometrics.** Not represented in [ContextState] at all, and this is deliberate: fingerprint and
 * face are *authentication* APIs, not readable sensors. GHOST can ask the system to authenticate
 * the user, but it cannot sense a fingerprint or a face, and there is no honest context field to
 * fill from them. [biometricsAvailable] exists so the capability can be logged once, not sensed.
 */
class RadioWatcher(private val ctx: Context) {

    companion object {
        private const val TAG = "RadioWatcher"
    }

    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ghost-radio").apply { isDaemon = true }
    }

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null

    fun start() {
        publishNfcState()
        logBiometricCapability()

        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            Log.i(TAG, "READ_PHONE_STATE not granted — call state and signal disabled")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "telephony callbacks need API 31+; skipping")
            return
        }
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager = tm
            val cb = GhostTelephonyCallback(tm)
            callback = cb
            tm.registerTelephonyCallback(executor, cb)
            Log.i(TAG, "telephony callbacks registered")
        }.onFailure { Log.w(TAG, "telephony registration failed", it) }
    }

    fun stop() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            }
        }
        callback = null
        telephonyManager = null
        ContextStore.update {
            it.copy(callState = CallState.UNKNOWN, networkType = null, signalLevel = -1, nfcEnabled = null)
        }
    }

    /** NFC is a capability flag, not a sensor — see the class doc. */
    private fun publishNfcState() {
        val enabled = runCatching { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled }.getOrNull()
        ContextStore.update { it.copy(nfcEnabled = enabled) }
        Log.i(TAG, "NFC adapter enabled=$enabled (tag reads require a foreground tap, not available here)")
    }

    private fun logBiometricCapability() {
        val pm = ctx.packageManager
        val fingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val face = pm.hasSystemFeature("android.hardware.biometrics.face")
        Log.i(TAG, "biometrics present: fingerprint=$fingerprint face=$face " +
            "(authentication APIs — not readable as context, so not surfaced in [CONTEXT])")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private inner class GhostTelephonyCallback(private val tm: TelephonyManager) :
        TelephonyCallback(),
        TelephonyCallback.CallStateListener,
        TelephonyCallback.SignalStrengthsListener {

        override fun onCallStateChanged(state: Int) {
            val mapped = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.IN_CALL
                else -> CallState.UNKNOWN
            }
            Log.i(TAG, "call state -> $mapped")
            ContextStore.update { it.copy(callState = mapped) }
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            val level = signalStrength.level.coerceIn(0, 4)
            val net = runCatching { networkTypeName(tm) }.getOrNull()
            ContextStore.update { it.copy(signalLevel = level, networkType = net) }
        }
    }

    /** Requires READ_PHONE_STATE, which the caller has already checked. */
    private fun networkTypeName(tm: TelephonyManager): String? = try {
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
            else -> "mobile"
        }
    } catch (e: SecurityException) {
        null
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
