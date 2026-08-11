package com.coxy.ghost

import android.service.quicksettings.TileService

/**
 * Quick Settings tile — the primary always-available, app-UI-independent kill channel (D3).
 * Add "Kill GHOST" to the quick-settings shade (long-press the shade → edit); one tap fully stops
 * GHOST via [KillSwitch.killGhost]. Independent of the app UI; works whenever the shade is
 * reachable. Declared in the manifest with `BIND_QUICK_SETTINGS_TILE`.
 */
class KillSwitchTile : TileService() {
    override fun onClick() {
        super.onClick()
        KillSwitch.killGhost(this, "qs-tile")
    }
}