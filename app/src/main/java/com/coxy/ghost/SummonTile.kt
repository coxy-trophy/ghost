package com.coxy.ghost

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.coxy.ghost.perception.SummonSource

/**
 * Quick Settings tile — summon GHOST from any screen, including from the shade over the lock screen.
 *
 * Add "Summon GHOST" to the quick-settings shade (long-press the shade → edit); one tap raises the
 * overlay and opens the mic. The always-reliable, hands-on counterpart to the wake word: the shade
 * is reachable everywhere, so this works when the wake word is dormant. Mirrors [KillSwitchTile];
 * declared in the manifest with `BIND_QUICK_SETTINGS_TILE`.
 */
class SummonTile : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, GhostService::class.java).apply {
            action = GhostService.ACTION_SUMMON
            putExtra(GhostService.EXTRA_SOURCE, SummonSource.TILE.label)
        }
        // The overlay needs the service running; start it foreground so a tap from a cold shade
        // still lands (the daemon is normally already up, but do not assume it).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
