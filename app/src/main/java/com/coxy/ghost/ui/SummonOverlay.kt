package com.coxy.ghost.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.coxy.ghost.R
import com.coxy.ghost.EngineGate
import com.coxy.ghost.cloud.CloudRouter
import com.coxy.ghost.perception.ContextState
import com.coxy.ghost.perception.GhostVoice
import com.coxy.ghost.perception.MicListener
import com.coxy.ghost.perception.MicState

/**
 * GHOST's summon surface: the Live-mode presence hoisted into a system overlay window so it can be
 * called from anywhere — over the home screen, over another app, and over the lock screen.
 *
 * The window is a [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY], owned by
 * [com.coxy.ghost.GhostService] (the always-on process), and drawn with the same [OrbView] the app
 * uses inside Live mode — so which brain is answering is visible in the orb's temperature before a
 * word arrives, exactly as it is in the app.
 *
 * Conversation text is redacted while the device is locked. This view never dismisses the
 * keyguard; execution policy belongs to the action layer.
 *
 * All methods must be called on the main thread — `WindowManager` add/remove and the hosted views
 * require it. The service hops to `Dispatchers.Main` before touching this.
 */
class SummonOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var orb: OrbView? = null
    private var heardView: TextView? = null
    private var replyView: TextView? = null
    private var stateView: TextView? = null

    /** True while the overlay is attached to the window. */
    val isShowing: Boolean get() = root != null

    /**
     * Raise the overlay. Idempotent — a second call while already shown does nothing (the service's
     * [com.coxy.ghost.perception.SummonController] turns a re-summon into a mic re-open, not a
     * re-inflate). [onDismiss] fires when the scrim is tapped; [onSpeak] starts a guarded single command that ends on silence.
     */
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show(onDismiss: () -> Unit, onSpeak: () -> Unit) {
        if (root != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_summon, null)
        val orbView = view.findViewById<OrbView>(R.id.summon_orb)

        // Same level source as MainActivity's Live orb: her pulse while she talks, your voice while
        // you do, and genuinely flat when the mic is off — never the dead ContextState.audioLevelDb.
        orbView.levelProvider = {
            if (GhostVoice.speaking) {
                GhostVoice.pulse()
            } else if (MicListener.mode == MicListener.MicMode.OFF) {
                0f
            } else {
                (MicListener.lastRms - MicListener.RMS_FLOOR) /
                    (MicListener.RMS_CEIL - MicListener.RMS_FLOOR)
            }
        }

        // Tap the scrim (anything outside the panel) to dismiss; the panel eats its own touches so a
        // tap on the orb or the button does not close it.
        view.findViewById<View>(R.id.summon_scrim).setOnClickListener { onDismiss() }
        view.findViewById<View>(R.id.summon_panel).setOnClickListener { /* consume */ }

        view.findViewById<View>(R.id.summon_ptt).setOnClickListener { onSpeak() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // NOT_FOCUSABLE: never steal keys/IME from the app behind. WATCH_OUTSIDE_TOUCH is not
            // needed — the scrim fills the screen and handles its own taps. DIM_BEHIND dims the app
            // behind so the orb reads; SHOW_WHEN_LOCKED puts it over the keyguard (show + speak only).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = DIM_AMOUNT
        }

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                // The usual cause is SYSTEM_ALERT_WINDOW not granted. As a system app the Magisk
                // module's appops grant covers it; log rather than crash the service if it is missing.
                Log.w(TAG, "failed to add summon overlay — is SYSTEM_ALERT_WINDOW granted?", it)
                return
            }

        root = view
        orb = orbView
        heardView = view.findViewById(R.id.summon_heard)
        replyView = view.findViewById(R.id.summon_reply)
        stateView = view.findViewById(R.id.summon_state)
        Log.i(TAG, "summon overlay shown")
    }

    /**
     * Push the current perception + conversation into the overlay. Called on a short cadence by the
     * service while the overlay is up so the orb tracks speaking/thinking (which do not always ride a
     * [ContextState] emission) and the heard/reply text streams. Mirrors MainActivity's Live render.
     */
    fun render(cs: ContextState) {
        val orbView = orb ?: return
        val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked
        val (heard, reply) = com.coxy.ghost.ipc.GhostIsland.displayText()
        heardView?.text = if (locked) "Unlock to see your conversation" else heard
        replyView?.text = Markup.render(
            if (locked) "" else if (com.coxy.ghost.ipc.GhostIsland.state == "ERROR") com.coxy.ghost.ipc.GhostIsland.errorDetail() else reply,
            ContextCompat.getColor(context, R.color.on_live_field_muted),
        )

        val state = when {
            GhostVoice.speaking -> OrbView.OrbState.SPEAKING
            com.coxy.ghost.ipc.GhostIsland.state == "THINKING" -> OrbView.OrbState.THINKING
            com.coxy.ghost.ipc.GhostIsland.state == "LISTENING" -> OrbView.OrbState.LISTENING
            else -> OrbView.OrbState.IDLE
        }
        orbView.setOrbState(state)
        orbView.setCloudBrain(CloudRouter.lastBrain == CloudRouter.Companion.Brain.CLOUD)

        stateView?.setText(
            if (com.coxy.ghost.ipc.GhostIsland.state == "ERROR") R.string.ghost_summon_error
            else if (com.coxy.ghost.ipc.GhostIsland.active() && state == OrbView.OrbState.IDLE) R.string.ghost_summon_warming
            else when (state) {
                OrbView.OrbState.SPEAKING -> R.string.ghost_summon_speaking
                OrbView.OrbState.THINKING -> R.string.ghost_summon_thinking
                OrbView.OrbState.LISTENING -> R.string.ghost_summon_listening
                else -> R.string.ghost_summon_ready
            }
        )
    }

    /** Remove the overlay. Idempotent. */
    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "failed to remove summon overlay", it) }
        root = null
        orb = null
        heardView = null
        replyView = null
        stateView = null
        Log.i(TAG, "summon overlay hidden")
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    companion object {
        private const val TAG = "SummonOverlay"
        /** How much to dim the app behind. Enough to lift the orb off it without hiding context. */
        private const val DIM_AMOUNT = 0.55f
    }
}
