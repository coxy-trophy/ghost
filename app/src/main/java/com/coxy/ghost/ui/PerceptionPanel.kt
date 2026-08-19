package com.coxy.ghost.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import com.coxy.ghost.BuildConfig
import com.coxy.ghost.perception.Activity
import com.coxy.ghost.perception.CallState
import com.coxy.ghost.perception.ContextState
import com.coxy.ghost.perception.LightBucket
import com.coxy.ghost.perception.LocationBucket
import com.coxy.ghost.perception.MicState
import com.coxy.ghost.perception.MotionState
import com.coxy.ghost.perception.Posture
import com.coxy.ghost.perception.Proximity
import com.coxy.ghost.perception.clauseCount
import com.coxy.ghost.perception.suppressedClauses
import com.coxy.ghost.perception.toPromptBlock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Senses screen: what GHOST can perceive, and what she is being told about it right now.
 *
 * The page is a bundled asset rendered in a [WebView], and it is deliberately dumb — every number
 * on it is computed here, by the *same* functions that assemble the real prompt
 * ([ContextState.toPromptBlock], [ContextState.clauseCount], [ContextState.suppressedClauses]).
 * A screen that described perception in its own words could drift from what actually reaches the
 * model, which is the class of bug this project keeps finding; deriving it from one source makes
 * that impossible rather than unlikely.
 */
class PerceptionPanel(private val webView: WebView) {

    @SuppressLint("SetJavaScriptEnabled")
    fun attach() {
        // Local asset only, no network, no file access. JS is on solely so Kotlin can push state in.
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.blockNetworkLoads = true
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.isVerticalScrollBarEnabled = true
        webView.loadUrl("file:///android_asset/perception.html")
    }

    /** Push a fresh snapshot into the page. Safe to call before the page has finished loading. */
    fun render(state: ContextState) {
        val json = snapshot(state).toString()
        webView.post {
            runCatching { webView.evaluateJavascript("ghostUpdate($json)", null) }
        }
    }

    private fun snapshot(s: ContextState): JSONObject = JSONObject().apply {
        put("build", "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}")
        put("block", s.toPromptBlock())
        put("dropped", JSONArray(s.suppressedClauses()))
        put("fields", CONTEXT_STATE_FIELDS)
        put("clauses", s.clauseCount())
        put("senses", SENSOR_REGISTRATIONS)
        put("groups", groups(s))
    }

    private fun groups(s: ContextState): JSONArray {
        val out = JSONArray()

        out.put(
            group(
                "Motion & activity",
                row("activity", s.activity.label(), if (s.activity == Activity.UNKNOWN) "off" else "on"),
                row("held for", dwellLabel(s), if (dwellLabel(s) == "—") "off" else ""),
                row("confidence", if (s.activityConfidence > 0) "${s.activityConfidence}%" else "—", ""),
                row(
                    "motion",
                    s.motion.label(),
                    // Amber-ish "off" reads as "known but not being sent", which is exactly right
                    // for a reading suppressed as an echo of activity.
                    if (s.motion == MotionState.UNKNOWN || s.suppressedClauses().any { it.startsWith("motion=") }) "off" else "on",
                ),
                row("steps", if (s.stepRatePerMin > 0) "${s.stepRatePerMin}/min" else "—", ""),
                row("speed", if (s.speedKmh > 0f) "%.1f km/h".format(s.speedKmh) else "—", ""),
            )
        )

        out.put(
            group(
                "Place",
                row("location", s.locationBucket.label(), if (s.locationBucket == LocationBucket.UNKNOWN) "off" else "on"),
                row("heading", s.heading ?: "—", ""),
            )
        )

        out.put(
            group(
                "Environment",
                row("light", s.lightBucket.label() + if (s.lightLux >= 0f) " (${s.lightLux.toInt()} lx)" else "", if (s.lightBucket == LightBucket.UNKNOWN) "off" else "on"),
                row(
                    "proximity",
                    s.proximity.label(),
                    if (s.suppressedClauses().any { it.startsWith("proximity=") }) "off" else if (s.proximity == Proximity.UNKNOWN) "off" else "on",
                ),
            )
        )

        out.put(
            group(
                "Handling",
                row("posture", s.posture.label(), if (s.posture == Posture.UNKNOWN) "off" else "on"),
                row("in pocket", s.inPocket?.let { if (it) "yes" else "no" } ?: "—", if (s.inPocket == true) "on" else ""),
                row("picked up", if (s.lastPickUpMs > 0L) "recently" else "—", ""),
            )
        )

        out.put(
            group(
                "Hearing & sight",
                row(
                    "mic",
                    if (!s.micAvailable) "unavailable" else s.micState.label(),
                    if (!s.micAvailable) "err" else if (s.micState == MicState.OFF || s.micState == MicState.UNKNOWN) "off" else "on",
                ),
                row("heard", s.transcriptSnippet?.takeIf { it.isNotBlank() } ?: "—", ""),
                row(
                    "camera",
                    if (!s.cameraAvailable) "unavailable" else if (s.lastFrameMs > 0L) "frame captured" else "idle",
                    if (!s.cameraAvailable) "err" else "",
                ),
            )
        )

        out.put(
            group(
                "Nearby & radios",
                row("nearby", if (s.nearbyCount >= 0) "${s.nearbyCount}" else "not scanning", if (s.nearbyCount > 0) "on" else "off"),
                row("known", s.nearbyKnown?.takeIf { it.isNotBlank() } ?: "—", ""),
                row("call", s.callState.label(), if (s.callState == CallState.IN_CALL || s.callState == CallState.RINGING) "on" else ""),
                row("signal", if (s.signalLevel >= 0) "${s.signalLevel}/4${s.networkType?.let { " ($it)" } ?: ""}" else "—", if (s.signalLevel in 0..1) "err" else ""),
                row("nfc", s.nfcEnabled?.let { if (it) "on" else "off" } ?: "—", ""),
                row("play services", if (s.gmsAvailable) "available" else "unavailable", if (s.gmsAvailable) "" else "err"),
            )
        )

        return out
    }

    private fun group(name: String, vararg items: JSONObject) = JSONObject().apply {
        put("name", name)
        put("items", JSONArray().also { arr -> items.forEach { arr.put(it) } })
    }

    private fun row(k: String, v: String, s: String) = JSONObject().apply {
        put("k", k); put("v", v); put("s", s)
    }

    /** Mirrors the dwell the block itself shows, without duplicating the formatting rule. */
    private fun dwellLabel(s: ContextState): String {
        if (s.activity == Activity.UNKNOWN || s.activitySinceMs <= 0L) return "—"
        val held = System.currentTimeMillis() - s.activitySinceMs
        if (held < 60_000L) return "just now"
        val minutes = held / 60_000L
        if (minutes < 60L) return "${minutes}m"
        val h = minutes / 60L
        val m = minutes % 60L
        return if (m == 0L) "${h}h" else "${h}h ${m}m"
    }

    private fun Any.label(): String = when (this) {
        is Activity -> name.lowercase().replace('_', ' ')
        is MotionState -> name.lowercase()
        is LocationBucket -> name.lowercase()
        is LightBucket -> name.lowercase()
        is Proximity -> name.lowercase()
        is Posture -> name.lowercase().replace('_', ' ')
        is MicState -> name.lowercase()
        is CallState -> name.lowercase().replace('_', ' ')
        else -> toString()
    }.let { if (it == "unknown") "—" else it }

    private companion object {
        /** Count of fields on [ContextState]. Shown as "fields" against the clauses actually sent. */
        const val CONTEXT_STATE_FIELDS = 36

        /** Sensor/service registrations the perception layer holds (GHOST.md, Phase 4.5). */
        const val SENSOR_REGISTRATIONS = 12
    }
}
