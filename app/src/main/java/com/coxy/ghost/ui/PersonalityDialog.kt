package com.coxy.ghost.ui

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.coxy.ghost.R
import com.coxy.ghost.GhostService
import com.coxy.ghost.personality.PersonalityProfile
import com.coxy.ghost.personality.PersonalityRepository
import com.coxy.ghost.personality.PersonalitySnapshot
import com.coxy.ghost.personality.PersonalityTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Phase 6.3 personality editor: 8 trait sliders with Save, Reset-to-baseline, Snapshot and
 * Rollback.
 *
 * Lifted out of [com.coxy.ghost.MainActivity], which had grown to 869 lines with roughly 250 of
 * them being programmatic view construction. This is a pure move — the view wiring, the positional
 * getChildAt casts and the save semantics are unchanged.
 */
class PersonalityDialog(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val repo: PersonalityRepository,
    private val startServiceWithAction: (String) -> Unit
) {

    fun show() {
        scope.launch(Dispatchers.IO) {
            val profileRef = repo.activeProfile()
            withContext(Dispatchers.Main) { build(profileRef) }
        }
    }

    private fun build(profileRef: PersonalityProfile) {
        val container = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_personality, null) as ScrollView
        val sliderContainer = container.findViewById<LinearLayout>(R.id.slider_container)
        val btnSnapshot = container.findViewById<android.widget.Button>(R.id.btn_snapshot)
        val btnRollback = container.findViewById<android.widget.Button>(R.id.btn_rollback)

        // Trait definition: display label + getter/setter against the mutable copy we load.
        data class TraitDef(
            val label: String,
            val minBudget: Int,
            val maxBudget: Int,
            val get: () -> Int,
            val set: (Int) -> Unit
        )

        var mutableProfile = profileRef.copy()

        val accent = ContextCompat.getColor(activity, R.color.accent)
        val track = ContextCompat.getColor(activity, R.color.glass_border)

        val traits = listOf(
            TraitDef("Humor", profileRef.humorMin, profileRef.humorMax, { mutableProfile.humor }, { mutableProfile = mutableProfile.copy(humor = it) }),
            TraitDef("Sarcasm", profileRef.sarcasmMin, profileRef.sarcasmMax, { mutableProfile.sarcasm }, { mutableProfile = mutableProfile.copy(sarcasm = it) }),
            TraitDef("Bluntness", profileRef.bluntnessMin, profileRef.bluntnessMax, { mutableProfile.bluntness }, { mutableProfile = mutableProfile.copy(bluntness = it) }),
            TraitDef("Warmth", profileRef.warmthMin, profileRef.warmthMax, { mutableProfile.warmth }, { mutableProfile = mutableProfile.copy(warmth = it) }),
            TraitDef("Formality", profileRef.formalityMin, profileRef.formalityMax, { mutableProfile.formality }, { mutableProfile = mutableProfile.copy(formality = it) }),
            TraitDef("Verbosity", profileRef.verbosityMin, profileRef.verbosityMax, { mutableProfile.verbosity }, { mutableProfile = mutableProfile.copy(verbosity = it) }),
            TraitDef("Curiosity", profileRef.curiosityMin, profileRef.curiosityMax, { mutableProfile.curiosity }, { mutableProfile = mutableProfile.copy(curiosity = it) }),
            TraitDef("Initiative", profileRef.initiativeMin, profileRef.initiativeMax, { mutableProfile.initiative }, { mutableProfile = mutableProfile.copy(initiative = it) })
        )

        // Per-trait slider rows: label | SeekBar 0..100 | value label.
        traits.forEach { def ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(activity).apply {
                text = def.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(activity, R.color.on_surface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val seekBar = SeekBar(activity).apply {
                max = 100
                progress = def.get()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                // Tinted in code rather than by a style, because these are constructed here and a
                // SeekBar cannot be given a style after construction. Left alone they render in
                // Material's own default, which was the last saturated orange in the app that
                // belonged to no palette.
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList = ColorStateList.valueOf(track)
            }
            val valueLabel = TextView(activity).apply {
                text = def.get().toString()
                // Monospace so the row does not shift as the number changes width. The old
                // proportional label moved the whole line every time a value crossed 99 or 100.
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.on_surface_variant))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
                textAlignment = TextView.TEXT_ALIGNMENT_TEXT_END
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val clamped = progress.coerceIn(def.minBudget, def.maxBudget)
                    def.set(clamped)
                    if (progress != clamped) seekBar?.progress = clamped
                    valueLabel.text = clamped.toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            row.addView(label)
            row.addView(seekBar)
            row.addView(valueLabel)
            sliderContainer.addView(row, sliderContainer.childCount - 1)
        }

        val dialog = AlertDialog.Builder(activity, R.style.Theme_Ghost_Dialog)
            .setTitle(activity.getString(R.string.ghost_personality_title))
            .setView(container)
            .setPositiveButton(R.string.ghost_personality_save, null)
            .setNegativeButton(R.string.ghost_personality_reset, null)
            .setNeutralButton(R.string.ghost_personality_snapshots, null)
            .create()

        // Helper: refresh all sliders from mutableProfile.
        fun refreshSliders() {
            traits.forEachIndexed { idx, def ->
                val row = sliderContainer.getChildAt(idx) as LinearLayout
                val seekBar = row.getChildAt(1) as SeekBar
                val valueLabel = row.getChildAt(2) as TextView
                seekBar.progress = def.get()
                valueLabel.text = def.get().toString()
            }
        }

        // Helper: pick a snapshot to roll back to and trigger service reload.
        fun showRollbackPicker(snapshots: List<PersonalitySnapshot>) {
            val names = snapshots.map { it.reason }
            if (names.isEmpty()) {
                Toast.makeText(activity, "No snapshots yet", Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(activity, R.style.Theme_Ghost_Dialog)
                .setTitle(R.string.ghost_personality_rollback)
                .setItems(names.toTypedArray()) { _, which ->
                    scope.launch(Dispatchers.IO) {
                        val restored = repo.rollback(snapshots[which])
                        withContext(Dispatchers.Main) {
                            mutableProfile = restored
                            refreshSliders()
                            Toast.makeText(activity, "Rolled back to ${snapshots[which].reason}", Toast.LENGTH_SHORT).show()
                            startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.setOnShowListener {
            // Save: persist clamped profile, then ask the service to reload.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    val clamped = PersonalityTranslator.clamp(mutableProfile)
                    repo.save(clamped)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, R.string.ghost_personality_applied, Toast.LENGTH_SHORT).show()
                        startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                        dialog.dismiss()
                    }
                }
            }
            // Reset to baseline defaults and persist/reload.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    val reset = repo.resetToBaseline()
                    withContext(Dispatchers.Main) {
                        mutableProfile = reset
                        refreshSliders()
                        Toast.makeText(activity, R.string.ghost_personality_baseline, Toast.LENGTH_SHORT).show()
                        startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                    }
                }
            }
            // Neutral: snapshot/rollback chooser.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    val snapshots = repo.recentSnapshots(20)
                    val names = snapshots.map { it.reason } + "+ New snapshot"
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(activity, R.style.Theme_Ghost_Dialog)
                            .setTitle(R.string.ghost_personality_snapshots)
                            .setItems(names.toTypedArray()) { _, which ->
                                if (which == names.lastIndex) {
                                    val name = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                        .format(Date())
                                    scope.launch(Dispatchers.IO) {
                                        repo.snapshot(name, mutableProfile)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(activity, "Snapshot saved: $name", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        val restored = repo.rollback(snapshots[which])
                                        withContext(Dispatchers.Main) {
                                            mutableProfile = restored
                                            refreshSliders()
                                            Toast.makeText(activity, "Rolled back to ${snapshots[which].reason}", Toast.LENGTH_SHORT).show()
                                            startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            // Dedicated snapshot button inside the dialog also creates a new snapshot.
            btnSnapshot.setOnClickListener {
                val name = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                scope.launch(Dispatchers.IO) {
                    repo.snapshot(name, mutableProfile)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Snapshot saved: $name", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            btnRollback.setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    val snapshots = repo.recentSnapshots(20)
                    withContext(Dispatchers.Main) { showRollbackPicker(snapshots) }
                }
            }
        }
        dialog.show()
    }

    /**
     * Phase 8: configure the Ollama Cloud brain — API key, on/off toggle, privacy filter, and
     * distillation logging toggle.
     */
}
