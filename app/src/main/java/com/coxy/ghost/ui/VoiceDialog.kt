package com.coxy.ghost.ui

import android.app.AlertDialog
import android.speech.tts.Voice
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.R
import com.coxy.ghost.perception.GhostVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Which voice GHOST speaks in, chosen by hearing them.
 *
 * A picker rather than a better heuristic, because the heuristic cannot win. This device offers
 * thirty voices and every one of them reports the same quality score, the API has no field for
 * gender, and "sounds robotic" is a judgement only the person listening can make. Code can pick a
 * sensible default; it cannot pick a voice someone likes.
 *
 * Every row speaks when tapped. Reading a list of names like `en-gb-x-gbd-network` and choosing
 * from it would be worse than the problem — the label exists to tell them apart afterwards, not to
 * decide between them.
 */
class VoiceDialog(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {

    /** What each voice says when tapped. Long enough to judge, short enough to sit through. */
    private val sample =
        "I'm here. It's about four in the afternoon, and you've had that window open for hours."

    fun show() {
        val voices = GhostVoice.available
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }

        if (voices.isEmpty()) {
            container.addView(caption("No voices installed for this language."))
        } else {
            container.addView(
                caption("Tap one to hear it. She'll keep whichever you choose.")
            )
        }

        var chosen = GhostVoice.preferredVoice ?: GhostVoice.voiceName
        val rows = mutableMapOf<String, TextView>()

        fun paint() {
            for ((name, view) in rows) {
                val on = name == chosen
                view.setTextColor(
                    ContextCompat.getColor(
                        activity, if (on) R.color.accent else R.color.on_surface
                    )
                )
            }
        }

        for (voice in voices) {
            val row = TextView(activity).apply {
                text = label(voice)
                textSize = 14f
                setPadding(0, dp(11), 0, dp(11))
                isClickable = true
                setOnClickListener {
                    chosen = voice.name
                    paint()
                    GhostVoice.preview(voice, sample)
                }
            }
            rows[voice.name] = row
            container.addView(row)
        }
        paint()

        AlertDialog.Builder(activity, R.style.Theme_Ghost_Dialog)
            .setTitle("Her voice")
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton("Use this one") { _, _ ->
                GhostVoice.stop()
                GhostVoice.applyPreferred(chosen)
                scope.launch(Dispatchers.IO) {
                    GhostPrefs.setPreferredVoice(activity.applicationContext, chosen)
                }
            }
            .setNeutralButton(
                if (GhostVoice.enabled) "Silence her" else "Let her speak"
            ) { _, _ ->
                val on = !GhostVoice.enabled
                GhostVoice.enabled = on
                if (!on) GhostVoice.stop()
                scope.launch(Dispatchers.IO) {
                    GhostPrefs.setVoiceEnabled(activity.applicationContext, on)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> GhostVoice.stop() }
            .show()
    }

    /**
     * A name someone can tell apart from the one above it.
     *
     * The variant code is kept because it is the only thing that actually distinguishes two voices
     * from the same place, and after previewing a few it becomes the thing they were choosing
     * between. "Online" matters separately: those need a connection, and she falls back without one.
     */
    private fun label(voice: Voice): String {
        val where = voice.locale.country.ifEmpty { voice.locale.language }.uppercase()
        val variant = voice.name.substringAfterLast("-x-", "")
            .substringBefore("-").ifEmpty { voice.name }
        val net = if (voice.isNetworkConnectionRequired) " · online" else ""
        return "$where · $variant$net"
    }

    private fun caption(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.START
        setTextColor(ContextCompat.getColor(activity, R.color.on_surface_muted))
        setPadding(0, dp(4), 0, dp(10))
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
