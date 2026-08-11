package com.coxy.ghost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts [GhostService] after a reboot if the kill-switch (`enabled`) flag is set.
 * Reading DataStore is async, so [goAsync] keeps the receiver alive across the suspend read.
 * (LOCKED_BOOT_COMPLETED / pre-unboot direct-boot is intentionally not handled — DataStore lives
 * in credential-encrypted storage, unavailable before first unlock. Normal reboots, where the
 * phone reaches the unlocked state, are what the Phase 3 gate exercises.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED received")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (GhostPrefs.isEnabled(context)) {
                    Log.i(TAG, "enabled=true — starting GhostService")
                    val svc = Intent(context, GhostService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        context.startService(svc)
                    }
                } else {
                    Log.i(TAG, "enabled=false — not starting")
                }
            } catch (e: Exception) {
                Log.e(TAG, "boot start failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GhostBootReceiver"
    }
}