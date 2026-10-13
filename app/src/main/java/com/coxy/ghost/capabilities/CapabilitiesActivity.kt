package com.coxy.ghost.capabilities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.coxy.ghost.AssistantRole
import com.coxy.ghost.contacts.ContactAccess
import com.coxy.ghost.contacts.ContactViewerActivity
import com.coxy.ghost.notifications.NotificationTool
import kotlinx.coroutines.launch

/** Local capability choices, separate from cloud/model settings. No data access on screen entry. */
class CapabilitiesActivity : ComponentActivity() {
    private lateinit var contacts: CheckBox
    private lateinit var status: TextView
    private var rendering = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p,p,p,p)
        }
        root.addView(TextView(this).apply { text = "Ghost capabilities"; textSize = 22f })
        status = TextView(this); root.addView(status)
        contacts = CheckBox(this).apply {
            text = "Local contact lookup"
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    if (checked && !ContactAccess.permission(this@CapabilitiesActivity)) {
                        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 10)
                    } else { ContactAccess.setEnabled(this@CapabilitiesActivity, checked); refresh() }
                }
            }
        }
        root.addView(contacts)
        root.addView(TextView(this).apply { text = "Contact names and numbers stay in the local viewer. They are not sent to the assistant model or saved in chat." })
        fun button(label: String, action: () -> Unit) { root.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        button("Find a contact") {
            if (ContactAccess.enabled(this) && ContactAccess.permission(this)) startActivity(Intent(this, ContactViewerActivity::class.java))
            else Toast.makeText(this, "Enable local contact lookup first.", Toast.LENGTH_SHORT).show()
        }
        button("View notifications") {
            lifecycleScope.launch {
                val result = NotificationTool.run(this@CapabilitiesActivity)
                if (!result.success) Toast.makeText(this@CapabilitiesActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }
        button("Choose notification apps in Nocturne") {
            val target = packageManager.getLaunchIntentForPackage("com.coxy.nocturne.ui")
            if (target != null) startActivity(target)
            else Toast.makeText(this, "Nocturne is unavailable.", Toast.LENGTH_SHORT).show()
        }
        button("Assistant settings") { AssistantRole.openSystemAssistantSettings(this) }
        root.addView(TextView(this).apply { text = "System hotword support is still being verified. Software wake remains the working path. Calls, messages and remote jobs are not enabled by granting local data access." })
        button("Done") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun refresh() {
        rendering = true
        contacts.isChecked = ContactAccess.enabled(this) && ContactAccess.permission(this)
        rendering = false
        status.text = "Default assistant: ${AssistantRole.currentLabel(this)}\nContacts: ${if (contacts.isChecked) "local access enabled" else "off"}\nNotifications: app access is controlled in Nocturne"
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) { ContactAccess.setEnabled(this, grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED); refresh() }
    }
}
