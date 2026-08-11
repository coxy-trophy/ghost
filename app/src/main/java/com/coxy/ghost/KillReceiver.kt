package com.coxy.ghost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Shell-reachable kill backstop (D3). Exported `BroadcastReceiver` on [KillSwitch.ACTION_KILL],
 * gated by the custom `com.coxy.ghost.permission.KILL` permission so only holders can fire it.
 *
 * Trigger from a shell (after a one-time `adb shell pm grant com.android.shell
 * com.coxy.ghost.permission.KILL`):
 *   adb shell am broadcast -a com.coxy.ghost.action.KILL -n com.coxy.ghost/.KillReceiver
 *
 * A safe action — it only stops GHOST — so the mild export risk is acceptable.
 */
class KillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == KillSwitch.ACTION_KILL) {
            KillSwitch.killGhost(context, "broadcast")
        }
    }
}