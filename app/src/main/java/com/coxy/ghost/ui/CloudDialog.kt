package com.coxy.ghost.ui

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.coxy.ghost.R
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.cloud.CloudKeyStorage
import com.coxy.ghost.cloud.CloudPromptFilter
import com.coxy.ghost.cloud.CloudSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Phase 8 cloud-brain settings dialog: API key, enable toggle, privacy mode and logging.
 *
 * Lifted out of [com.coxy.ghost.MainActivity] alongside [PersonalityDialog]; a pure move.
 */
class CloudDialog(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {

    fun show() {
        scope.launch(Dispatchers.IO) {
            val settings = GhostPrefs.cloudSettings(activity.applicationContext)
            val currentKey = CloudKeyStorage.getKey(activity.applicationContext) ?: ""
            withContext(Dispatchers.Main) { build(currentKey, settings) }
        }
    }

    private fun build(currentKey: String, settings: com.coxy.ghost.cloud.CloudSettings) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val keyInput = EditText(activity).apply {
            hint = activity.getString(R.string.ghost_cloud_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(currentKey)
        }
        container.addView(keyInput)

        val enabledSwitch = android.widget.Switch(activity).apply {
            text = activity.getString(R.string.ghost_cloud_enabled)
            isChecked = settings.enabled
        }
        container.addView(enabledSwitch)

        val loggingSwitch = android.widget.Switch(activity).apply {
            text = activity.getString(R.string.ghost_cloud_logging)
            isChecked = settings.loggingEnabled
        }
        container.addView(loggingSwitch)

        val modes = com.coxy.ghost.cloud.CloudPromptFilter.Mode.entries.map { it.name.lowercase() }
        val modeLabel = TextView(activity).apply {
            text = activity.getString(R.string.ghost_cloud_privacy)
            setPadding(0, 16, 0, 8)
        }
        container.addView(modeLabel)
        val modeSpinner = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                modes
            )
            setSelection(modes.indexOf(settings.privacyMode.name.lowercase()).coerceAtLeast(0))
        }
        container.addView(modeSpinner)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.ghost_cloud_title))
            .setView(container)
            .setPositiveButton(R.string.ghost_cloud_save) { _, _ ->
                val newKey = keyInput.text.toString().trim()
                val newMode = com.coxy.ghost.cloud.CloudPromptFilter.Mode.entries[modeSpinner.selectedItemPosition]
                scope.launch(Dispatchers.IO) {
                    CloudKeyStorage.setKey(activity.applicationContext, newKey)
                    GhostPrefs.setCloudEnabled(activity.applicationContext, enabledSwitch.isChecked)
                    GhostPrefs.setCloudLoggingEnabled(activity.applicationContext, loggingSwitch.isChecked)
                    GhostPrefs.setCloudPrivacyMode(activity.applicationContext, newMode)
                    withContext(Dispatchers.Main) {
                        val status = if (enabledSwitch.isChecked && newKey.isNotBlank()) "enabled"
                                     else if (enabledSwitch.isChecked) "enabled (key missing)"
                                     else "disabled"
                        Toast.makeText(activity, "Cloud brain $status", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
