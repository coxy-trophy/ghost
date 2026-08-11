package com.coxy.ghost.action

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles Confirm/Decline choices for Tier 3 actions surfaced by [ActionExecutor].
 */
class ActionConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra(EXTRA_ACTIONS_JSON) ?: return
        val actions = parseActionsJson(json)
        val nm = NotificationManagerCompat.from(context)

        when (intent.action) {
            ACTION_CONFIRM -> {
                val executor = ActionExecutor(context)
                val results = actions.map { executor.runConfirmed(it) }
                val lines = results.joinToString("\n") { it.toReplyLine() }
                Log.i(TAG, "confirmed Tier-3 actions: $lines")
                val notification = NotificationCompat.Builder(context, ActionExecutor.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("GHOST action result")
                    .setContentText(lines)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
                    .setAutoCancel(true)
                    .build()
                nm.notify(CONFIRM_NOTIF_ID, notification)
            }
            ACTION_DECLINE -> {
                Log.i(TAG, "Tier-3 action declined")
                nm.cancel(CONFIRM_NOTIF_ID)
            }
        }
    }

    private fun parseActionsJson(json: String): List<Action> {
        val actions = mutableListOf<Action>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("action", "").lowercase()
                val entry = ActionCatalog.lookup(id) ?: continue
                val params = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "action") params[key] = obj.optString(key, "")
                }
                if (ActionCatalog.validate(id, params).isEmpty()) {
                    actions += Action(id, entry.tier, params)
                }
            }
        }
        return actions
    }

    companion object {
        private const val TAG = "ActionConfirmReceiver"
        const val ACTION_CONFIRM = "com.coxy.ghost.action.CONFIRM_ACTION"
        const val ACTION_DECLINE = "com.coxy.ghost.action.DECLINE_ACTION"
        const val EXTRA_ACTIONS_JSON = "actions_json"
        private const val CONFIRM_NOTIF_ID = 3
    }
}
