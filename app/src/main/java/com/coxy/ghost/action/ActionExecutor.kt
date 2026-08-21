package com.coxy.ghost.action

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Facade for Phase 7 action execution.
 *
 * - Tier 0/1/2 actions run immediately on [Dispatchers.IO].
 * - Tier 3 actions are surfaced as a notification with Confirm/Decline buttons; they are never
 *   auto-executed. [ActionConfirmReceiver] handles the user choice.
 *
 * The executor is safe by construction: unknown action IDs are rejected, all shell args are
 * sanitized, and Tier 3 requires an explicit user tap.
 */
class ActionExecutor(private val context: Context) {

    private val framework = FrameworkExecutor(context)

    /**
     * Run all actions that do not require confirmation. Returns the results plus a list of Tier 3
     * actions that are pending user confirmation.
     */
    suspend fun runImmediate(actions: List<Action>): ExecutionBatch {
        return withContext(Dispatchers.IO) {
            val immediate = mutableListOf<ActionResult>()
            val pending = mutableListOf<Action>()
            for (action in actions) {
                when (action.tier) {
                    ActionTier.TIER_3_DESTRUCTIVE -> pending += action
                    else -> immediate += runSingle(action)
                }
            }
            ExecutionBatch(immediate, pending)
        }
    }

    /** Execute a single action regardless of tier (used by the confirmation receiver). */
    fun runConfirmed(action: Action): ActionResult = runSingle(action)

    private fun runSingle(action: Action): ActionResult {
        return when (action.id) {
            "toggle_wifi", "toggle_bluetooth",
            "force_stop_app", "disable_app", "uninstall_app", "send_sms" -> SuExecutor.run(action)
            "get_battery", "get_wifi_state", "get_bluetooth_state",
            "toggle_torch", "set_brightness", "set_volume", "launch_app" -> framework.run(action)
            // Handled before this point, by com.coxy.ghost.web.WebSearchTurn — a search feeds the
            // model rather than the reply, so it cannot run from here. Reaching this line means the
            // *second* pass asked to search again; one per turn is the cap, and saying so is better
            // than silently dropping it.
            "web_search" -> ActionResult(action.id, false, "already searched this turn")
            else -> ActionResult(action.id, false, "action not wired to an executor")
        }
    }

    /** Surface pending Tier 3 actions as a heads-up notification. */
    fun requestConfirmation(actions: List<Action>) {
        if (actions.isEmpty()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "GHOST actions",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Actions that require your confirmation" }
            )
        }

        val descriptions = actions.map { ActionCatalog.describe(it.id) }
        val content = descriptions.joinToString("; ")

        val confirmIntent = Intent(context, ActionConfirmReceiver::class.java).apply {
            action = ActionConfirmReceiver.ACTION_CONFIRM
            putExtra(ActionConfirmReceiver.EXTRA_ACTIONS_JSON, actionsToJson(actions))
        }
        val confirmPi = PendingIntent.getBroadcast(
            context, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, ActionConfirmReceiver::class.java).apply {
            action = ActionConfirmReceiver.ACTION_DECLINE
            putExtra(ActionConfirmReceiver.EXTRA_ACTIONS_JSON, actionsToJson(actions))
        }
        val declinePi = PendingIntent.getBroadcast(
            context, 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("GHOST wants to act")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_media_play, "Confirm", confirmPi)
            .addAction(android.R.drawable.ic_delete, "Decline", declinePi)
            .setAutoCancel(true)
            .build()

        nm.notify(CONFIRM_NOTIF_ID, notification)
        Log.i(TAG, "Tier-3 confirmation requested for: $descriptions")
    }

    private fun actionsToJson(actions: List<Action>): String {
        val array = org.json.JSONArray()
        for (action in actions) {
            val obj = JSONObject()
            obj.put("action", action.id)
            for ((k, v) in action.params) obj.put(k, v)
            array.put(obj)
        }
        return array.toString()
    }

    data class ExecutionBatch(
        val results: List<ActionResult>,
        val pending: List<Action>
    )

    companion object {
        private const val TAG = "ActionExecutor"
        internal const val CHANNEL_ID = "ghost_actions"
        private const val CONFIRM_NOTIF_ID = 2
    }
}
