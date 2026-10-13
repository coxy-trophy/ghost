package com.coxy.ghost.notifications

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coxy.ghost.R

/** Local, temporary display. Bodies never enter a model prompt, ActionResult, Intent or saved state. */
class NotificationViewerActivity : Activity() {
    private var content: LinearLayout? = null
    private var registered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { clearAndFinish() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val payload = NotificationViewer.handoff.take(intent.getStringExtra("ticket"), SystemClock.elapsedRealtime())
        if (payload == null || !NotificationClient.canRead(this)) {
            payload?.visible?.complete(false)
            finish(); return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isSaveEnabled = false
        }
        content = root
        fun line(value: CharSequence, size: Float = 16f) {
            root.addView(TextView(this).apply {
                text = value; textSize = size; isSaveEnabled = false
                setPadding(0, 10, 0, 10)
            })
        }
        line(getString(R.string.ghost_private_notifications), 22f)
        line(getString(R.string.ghost_private_notifications_detail), 13f)
        if (payload.snapshot.rows.isEmpty()) line(getString(R.string.ghost_notifications_empty))
        for (row in payload.snapshot.rows) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(row.packageName, 0)) }
                .getOrDefault(row.packageName)
            line(label, 14f)
            if (row.title.isNotBlank()) line(row.title, 18f)
            if (row.text.isNotBlank()) line(row.text)
        }
        root.addView(Button(this).apply { setText(R.string.ghost_notifications_done); setOnClickListener { clearAndFinish() } })
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(root) })
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        registered = true
        payload.visible.complete(true)
    }
    override fun onResume() {
        super.onResume()
        if (!NotificationClient.canRead(this)) clearAndFinish()
    }
    override fun onPause() { super.onPause(); clearAndFinish() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }
    private fun clearAndFinish() { content?.removeAllViews(); content = null; finish() }
    override fun onDestroy() {
        if (registered) unregisterReceiver(screenOff)
        content?.removeAllViews(); content = null
        super.onDestroy()
    }
}
