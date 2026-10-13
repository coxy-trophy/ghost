package com.coxy.ghost

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.coxy.ghost.perception.SummonSource

/**
 * Hardware channels over the volume keys, via [AccessibilityService] key-event filtering
 * (`flagRequestFilterKeyEvents`, configured in `res/xml/ghost_accessibility.xml`). Requires the
 * user to enable this service once in Settings → Accessibility; key events only arrive while the
 * screen is on. Two gestures, deliberately distinct so neither triggers the other:
 *
 *  - **Summon** — long-press **volume-up alone** for ~[SUMMON_HOLD_MS]. Raises the summon overlay.
 *  - **Kill** — hold **both** volume keys for ~[KILL_HOLD_MS] → [KillSwitch.killGhost].
 *
 * The summon check is scheduled on a [Handler] rather than read off repeated key-down events, so it
 * does not depend on auto-repeat and can be cancelled the instant the second key joins (which turns
 * the gesture into the kill combo instead). Keys are never consumed (return false) so volume still
 * adjusts — the gestures only observe them.
 */
class GhostAccessibilityService : AccessibilityService() {

    // Required abstract overrides — unused (this service only filters key events for the gestures).
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private val handler = Handler(Looper.getMainLooper())

    private var volUp = false
    private var volDown = false
    private var bothDownSince = 0L
    private var killFired = false

    /** Pending "volume-up held alone long enough" summon; cancelled if the gesture changes. */
    private val summonRunnable = Runnable {
        if (volUp && !volDown) {
            Log.i(TAG, "volume-up long-press → summon")
            runCatching {
                startService(
                    Intent(this, GhostService::class.java).apply {
                        action = GhostService.ACTION_SUMMON
                        putExtra(GhostService.EXTRA_SOURCE, SummonSource.KEY.label)
                    }
                )
            }.onFailure { Log.w(TAG, "failed to start summon from key gesture", it) }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && !volUp) {
                    volUp = true
                    // Arm the summon only while volume-up is alone. If volume-down is already held
                    // this is the kill combo forming, not a summon.
                    if (!volDown) handler.postDelayed(summonRunnable, SUMMON_HOLD_MS)
                }
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && !volDown) {
                    volDown = true
                    // Second key joined: this is now a kill combo, never a summon.
                    handler.removeCallbacks(summonRunnable)
                }
                if (volUp && volDown) {
                    if (bothDownSince == 0L) {
                        bothDownSince = System.currentTimeMillis()
                        killFired = false
                    }
                    if (!killFired && System.currentTimeMillis() - bothDownSince > KILL_HOLD_MS) {
                        killFired = true
                        Log.i(TAG, "both volume keys held → kill")
                        KillSwitch.killGhost(this, "volume-key-combo")
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUp = false
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volDown = false
                // Any release ends both pending gestures.
                handler.removeCallbacks(summonRunnable)
                if (!volUp || !volDown) {
                    bothDownSince = 0L
                    killFired = false
                }
            }
        }
        // Don't consume — let the system still adjust volume.
        return false
    }

    companion object {
        private const val TAG = "GhostAccessibility"
        private const val KILL_HOLD_MS = 1200L
        private const val SUMMON_HOLD_MS = 600L
    }
}
