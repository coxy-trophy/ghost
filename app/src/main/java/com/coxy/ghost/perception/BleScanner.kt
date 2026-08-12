package com.coxy.ghost.perception

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 4.5: who and what is physically near GHOST.
 *
 * This is the first perception source that can see *other people's* devices, so it is deliberately
 * narrow:
 *  - It reports a **count** of distinct BLE devices seen, never their addresses.
 *  - It names a device only if it is already **bonded** to this phone — i.e. something Coxy paired
 *    himself (his car, his headphones). A stranger's phone contributes to the count and nothing
 *    more, and no identifier for it is stored or sent anywhere.
 *  - Nothing is persisted. The result lives in [ContextState] and is overwritten by the next scan.
 *
 * Duty-cycled rather than continuous: [SCAN_WINDOW_MS] of low-power scanning every
 * [SCAN_INTERVAL_MS]. The LE radio is already powered on for other system users, so the marginal
 * cost is the scan window itself.
 */
class BleScanner(private val ctx: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val SCAN_WINDOW_MS = 8_000L
        private const val SCAN_INTERVAL_MS = 5 * 60_000L
        /** Cap the names in the context block; the block rides on every prompt. */
        private const val MAX_KNOWN_NAMES = 3
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null

    private val adapter: BluetoothAdapter? by lazy {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    /** Addresses seen in the current window — local only, never written to state or disk. */
    private val seen = mutableSetOf<String>()
    private val knownNames = mutableSetOf<String>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            synchronized(seen) {
                seen += device.address
                // Only name it if Coxy already paired with it. Unbonded devices stay a bare count.
                if (device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    runCatching { device.name }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { knownNames += it }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    fun start() {
        if (!hasScanPermission()) {
            Log.i(TAG, "BLUETOOTH_SCAN not granted — nearby-device sensing disabled")
            return
        }
        loop = scope.launch {
            while (true) {
                runCatching { scanOnce() }.onFailure { Log.w(TAG, "scan cycle failed", it) }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private suspend fun scanOnce() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            // BLE_ON (radio up, classic off) still allows scanning; a fully disabled adapter does not.
            if (a?.state != BluetoothAdapter.STATE_ON) {
                ContextStore.update { it.copy(nearbyCount = -1, nearbyKnown = null) }
                return
            }
        }
        val scanner = a?.bluetoothLeScanner ?: return
        synchronized(seen) { seen.clear(); knownNames.clear() }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }
            .onFailure { Log.w(TAG, "startScan threw", it); return }

        delay(SCAN_WINDOW_MS)
        runCatching { scanner.stopScan(callback) }

        val (count, names) = synchronized(seen) {
            seen.size to knownNames.take(MAX_KNOWN_NAMES).joinToString(", ")
        }
        Log.i(TAG, "BLE window: $count device(s), ${knownNames.size} known")
        ContextStore.update {
            it.copy(nearbyCount = count, nearbyKnown = names.ifBlank { null })
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        runCatching { scope.cancel() }
        ContextStore.update { it.copy(nearbyCount = -1, nearbyKnown = null) }
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
}
