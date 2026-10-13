package com.coxy.ghost.ipc

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/** Authenticated, bounded snapshots. No conversation is persisted by this bridge. */
object GhostIsland {
    const val CONTROL = "com.coxy.ghost.ipc.CONTROL"
    const val PEER = "com.coxy.nocturne.ui"
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var instance = ""
    @Volatile var session = ""; private set
    @Volatile var state = "OFF"; private set
    @Volatile var requestId = ""
    private var seq = 0L
    private val surfaceLease = IslandLease()
    private val surface = InteractionSurface()
    @Synchronized fun advertiseSurface(enabled: Boolean) = surfaceLease.advertise(enabled, SystemClock.elapsedRealtime())
    @Synchronized fun surfaceAvailable() = surfaceLease.available(SystemClock.elapsedRealtime())
    @Synchronized fun displayText(): Pair<String, String> = transcript to response
    @Synchronized fun errorDetail(): String = message
    @Volatile private var preparing = false
    private var mode = "OFF"
    private var transcript = ""
    private var response = ""
    private var error = ""
    private var message = ""
    private var level = 0f
    private var lastSent = 0L
    var onControl: ((String, String, String) -> Unit)? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            if (state == "SPEAKING") level = com.coxy.ghost.perception.GhostVoice.pulse()
            publish(); handler.postDelayed(this, if (state == "SPEAKING") 100 else 5000)
        }
    }
    @Synchronized fun start(ctx: Context) {
        surfaceLease.clear(); surface.clear()
        context = ctx.applicationContext; instance = UUID.randomUUID().toString()
        session = ""; requestId = ""; preparing = false; seq = 0; state = "OFF"; mode = "OFF"
        transcript = ""; response = ""; error = ""; message = ""; level = 0f
        handler.removeCallbacks(heartbeat); handler.post(heartbeat)
    }
    @Synchronized fun close() {
        state = "OFF"; mode = "OFF"; transcript = ""; response = ""; publish()
        handler.removeCallbacks(heartbeat); onControl = null; context = null
        surfaceLease.clear(); surface.clear()
    }
    @Synchronized fun begin(request: String = "", useIsland: Boolean = true): String {
        requestId = request
        session = UUID.randomUUID().toString(); transcript = ""; response = ""
        surface.begin(session, useIsland)
        error = ""; message = ""; level = 0f; state = "OFF"; preparing = true; mode = "PUSH_TO_TALK"
        // Actual LISTENING is published only when recognition is ready.
        return session
    }
    fun active() = preparing || state in setOf("LISTENING", "THINKING", "SPEAKING")
    /** Claim fallback before showing it so a returning peer cannot display the same session. */
    @Synchronized fun recoverSurface(expected: String): Boolean {
        if (!surface.recover(expected, active(), surfaceAvailable())) return false
        publish()
        return true
    }

    @Synchronized fun update(phase: String? = null, mic: String? = null,
        heard: String? = null, reply: String? = null, audio: Float? = null,
        code: String? = null, detail: String? = null, expected: String? = null) {
        if (expected != null && expected != session) return
        val nextPhase = phase ?: state
        val nextError = code ?: if (phase != null && phase != "ERROR") "" else error
        val changed = nextPhase != state || nextError != error
        if (phase != null) preparing = false
        state = nextPhase; mode = mic ?: mode
        transcript = (heard ?: transcript).take(1000); response = (reply ?: response).take(4000)
        if (audio != null) level = if (audio.isFinite()) audio.coerceIn(0f, 1f) else 0f
        error = nextError.take(80)
        message = (detail ?: if (phase != null && phase != "ERROR") "" else message).take(240)
        if (changed || SystemClock.elapsedRealtime() - lastSent >= 80) publish()
        if (changed && nextPhase == "SPEAKING") { handler.removeCallbacks(heartbeat); handler.post(heartbeat) }
    }
    @Synchronized fun reject(request: String, code: String, detail: String) {
        publish(request, "ERROR", code, detail, "")
    }
    @Synchronized fun publish(request: String? = null, phase: String? = null,
        failure: String? = null, detail: String? = null, voiceSession: String? = null) {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT < 34) return
        lastSent = SystemClock.elapsedRealtime()
        val intent = Intent("com.coxy.ghost.ipc.STATE").setPackage(PEER)
            .putExtra("protocol_version", 1).putExtra("service_instance_id", instance)
            .putExtra("request_id", request ?: requestId).putExtra("session_id", voiceSession ?: session).putExtra("seq", ++seq)
            .putExtra("surface", if (phase == "ERROR" && voiceSession == "") "island" else surface.owner)
            .putExtra("state", phase ?: state).putExtra("mic_mode", mode).putExtra("audio_level", level)
            .putExtra("transcript", transcript).putExtra("response", response)
            .putExtra("error_code", failure ?: error).putExtra("error_message", detail ?: message)
            .putExtra("updated_elapsed_ms", lastSent)
        ctx.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }
}
