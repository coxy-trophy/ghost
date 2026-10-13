package com.coxy.ghost.action

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coxy.ghost.execution.ConfirmationStore
import org.json.JSONArray

/** Immutable notification intents carry a request ID; exact action data stays in private storage. */
class ActionConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM && intent.action != ACTION_DECLINE) return
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        if (intent.action == ACTION_CONFIRM) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            if (keyguard == null || power == null || keyguard.isDeviceLocked || !power.isInteractive) return
        }
        // Removal commits before any execution. A duplicate tap or restart cannot reuse approval.
        val payload = ConfirmationStore.consume(context, id) ?: return
        val nm = NotificationManagerCompat.from(context)
        nm.cancel("ghost_approval:$id", ActionExecutor.CONFIRM_NOTIF_ID)
        if (intent.action == ACTION_DECLINE) return
        val actions = parseActionsJson(payload)
        if (actions.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = try {
                    withTimeout(25_000) {
                        val executor = ActionExecutor(appContext)
                        actions.map { executor.runConfirmed(it) }.joinToString("\n") { it.toReplyLine() }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    "Execution timed out. Some actions may have completed; check before trying again."
                } catch (_: Exception) {
                    "Execution did not finish normally. Check the result before trying again."
                }
                val notification = NotificationCompat.Builder(appContext, ActionExecutor.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("GHOST action result")
                    .setContentText(result)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(result))
                    .setAutoCancel(true).build()
                runCatching { nm.notify("ghost_result:$id", 3, notification) }
            } finally { pending.finish() }
        }
    }
    private fun parseActionsJson(json: String): List<Action> = runCatching {
        val array = JSONArray(json)
        require(array.length() in 1..8)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val id = obj.getString("action").lowercase()
            val entry = requireNotNull(ActionCatalog.lookup(id))
            require(entry.tier == ActionTier.TIER_3_DESTRUCTIVE)
            val params = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key != "action") params[key] = obj.getString(key)
            }
            require(ActionCatalog.validate(id, params).isEmpty())
            Action(id, entry.tier, params)
        }
    }.getOrElse { emptyList() }
    companion object {
        const val ACTION_CONFIRM = "com.coxy.ghost.action.CONFIRM_ACTION"
        const val ACTION_DECLINE = "com.coxy.ghost.action.DECLINE_ACTION"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
