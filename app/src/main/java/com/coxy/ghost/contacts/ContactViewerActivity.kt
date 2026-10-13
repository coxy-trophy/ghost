package com.coxy.ghost.contacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.coxy.ghost.notifications.NotificationClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local search and ambiguity selection only. No call/send/clipboard/model integration. */
class ContactViewerActivity : ComponentActivity() {
    private var root: LinearLayout? = null
    private var results: LinearLayout? = null
    private var search: Job? = null
    private var registered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { clearAndFinish() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val request = ContactTool.handoff.take(intent.getStringExtra("ticket"))
        if (!NotificationClient.canRead(this) || !ContactAccess.enabled(this) || !ContactAccess.permission(this)) {
            request?.visible?.complete(false); finish(); return
        }
        if (intent.hasExtra("ticket") && request == null) { finish(); return }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
            isSaveEnabled = false
        }
        root = content
        content.addView(TextView(this).apply { text = "Find a contact"; textSize = 22f })
        content.addView(TextView(this).apply { text = "Local only. Choose the right match; no call or message is sent." })
        val input = EditText(this).apply { hint = "Name or number"; isSaveEnabled = false; setSingleLine(); setText(request?.query.orEmpty()) }
        content.addView(input)
        content.addView(Button(this).apply { text = "Search"; setOnClickListener { lookup(input.text.toString()) } })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false }
        content.addView(results)
        content.addView(Button(this).apply { text = "Done"; setOnClickListener { clearAndFinish() } })
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(content) })
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED); registered = true
        request?.visible?.complete(true)
        if (request != null) lookup(request.query)
    }
    private fun lookup(query: String) {
        search?.cancel()
        results?.removeAllViews()
        search = lifecycleScope.launch {
            val snapshot = ContactAccess.search(this@ContactViewerActivity, query)
            if (!NotificationClient.canRead(this@ContactViewerActivity) || isFinishing) { clearAndFinish(); return@launch }
            val container = results ?: return@launch
            container.removeAllViews()
            if (snapshot.status != "OK" || snapshot.matches.isEmpty()) {
                container.addView(TextView(this@ContactViewerActivity).apply {
                    text = when (snapshot.status) {
                        "DISABLED" -> "Enable local contact access in Ghost capabilities."
                        "INVALID_QUERY" -> "Enter a name or number up to 100 characters."
                        "OK" -> "No matching contacts."
                        else -> "Contacts are unavailable."
                    }
                })
            } else for (match in snapshot.matches) {
                container.addView(Button(this@ContactViewerActivity).apply {
                    isSaveEnabled = false; text = "${match.name}\n${match.number}"
                    setOnClickListener {
                        container.removeAllViews()
                        container.addView(TextView(this@ContactViewerActivity).apply {
                            isSaveEnabled = false
                            text = "Selected locally:\n${match.name}\n${match.number}\n\nNo call or message sent."
                        })
                    }
                })
            }
        }
    }
    override fun onResume() { super.onResume(); if (!NotificationClient.canRead(this)) clearAndFinish() }
    override fun onPause() { super.onPause(); clearAndFinish() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }
    private fun clearAndFinish() { search?.cancel(); results?.removeAllViews(); root?.removeAllViews(); results = null; root = null; finish() }
    override fun onDestroy() { if (registered) unregisterReceiver(screenOff); super.onDestroy() }
}
