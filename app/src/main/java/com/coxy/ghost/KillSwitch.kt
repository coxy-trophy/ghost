package com.coxy.ghost

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Phase 5 independent kill switch (GHOST.md §8: "a hard, always-available way to fully stop
 * GHOST instantly, independent of the app's own UI. Build this before Phase 5, not after.").
 *
 * Three funnels converge here: [KillSwitchTile] (Quick Settings), [KillReceiver] (shell broadcast),
 * and [GhostAccessibilityService] (volume-key combo). `killGhost` (1) persists `enabled=false` so
 * [BootReceiver] won't restart after a reboot, and (2) sends [ACTION_KILL] to [GhostService] for
 * in-process teardown (stop perception/mic/camera + wipe the retention cache + `stopSelf`).
 *
 * All components run in the same process as GhostService, so [GhostService.isRunning] is an accurate
 * gate. If the service isn't up, `startService` may throw on O+ (caught) — but `enabled=false` already
 * guarantees it won't come back. Safe by construction: this only ever *stops* GHOST.
 */
object KillSwitch {
    private const val TAG = "KillSwitch"
    const val ACTION_KILL = "com.coxy.ghost.action.KILL"

    fun killGhost(context: Context, source: String) {
        Log.i(TAG, "killGhost invoked from $source")
        // 1. Persist disabled so BootReceiver won't restart after a reboot.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { GhostPrefs.setEnabled(context, false) }
        }
        // 2. In-process teardown if the daemon is up (stop perception + wipe cache + stopSelf).
        val intent = Intent(context, GhostService::class.java).setAction(ACTION_KILL)
        runCatching { context.startService(intent) }
    }
}