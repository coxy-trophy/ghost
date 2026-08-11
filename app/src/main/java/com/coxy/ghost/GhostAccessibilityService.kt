package com.coxy.ghost

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hardware kill channel (D3): hold both volume keys for ~1.2 s → [KillSwitch.killGhost].
 *
 * Uses [AccessibilityService] key-event filtering (`flagRequestFilterKeyEvents` +
 * `canRequestFilterKeyEvents`, configured in `res/xml/ghost_accessibility.xml`). This is the
 * fragile/secondary channel — it requires the user to **manually enable** this service in
 * Settings → Accessibility (one-time; ColorOS shows a warning), and key events only arrive while
 * the screen is on. The QS tile + shell broadcast are the guaranteed channels; this is the
 * belt-and-suspenders hardware path. We do **not** consume the keys (return false) so volume still
 * adjusts — the combo merely observes them.
 */
class GhostAccessibilityService : AccessibilityService() {

    // Required abstract overrides — unused (this service only filters key events for the kill combo).
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private var volUp = false
    private var volDown = false
    private var bothDownSince = 0L
    private var fired = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUp = true
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volDown = true
                if (volUp && volDown) {
                    if (bothDownSince == 0L) {
                        bothDownSince = System.currentTimeMillis()
                        fired = false
                    }
                    if (!fired && System.currentTimeMillis() - bothDownSince > HOLD_MS) {
                        fired = true
                        Log.i(TAG, "both volume keys held → kill")
                        KillSwitch.killGhost(this, "volume-key-combo")
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUp = false
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volDown = false
                if (!volUp || !volDown) {
                    bothDownSince = 0L
                    fired = false
                }
            }
        }
        // Don't consume — let the system still adjust volume.
        return false
    }

    companion object {
        private const val TAG = "GhostAccessibility"
        private const val HOLD_MS = 1200L
    }
}