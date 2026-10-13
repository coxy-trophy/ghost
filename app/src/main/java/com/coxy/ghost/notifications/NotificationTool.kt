package com.coxy.ghost.notifications

import android.content.Context
import com.coxy.ghost.action.ActionResult

/** Only fixed status text crosses into the model/chat pipeline. Never return notification bodies. */
internal object NotificationTool {
    suspend fun run(context: Context): ActionResult {
        val snapshot = NotificationClient.read(context)
        if (snapshot.status != "OK") return ActionResult("read_notifications", false, when (snapshot.status) {
            "DISABLED" -> "Enable local Ghost notification access and choose apps in Nocturne settings."
            "LOCKED" -> "Unlock the phone to view notifications."
            else -> "The local notification bridge is unavailable."
        })
        val shown = NotificationViewer.open(context, snapshot)
        return ActionResult("read_notifications", shown, if (shown)
            "Opened the private local notification viewer. Contents are not available to the assistant model."
            else "Could not show the private viewer. Open Ghost while the phone is unlocked and try again.")
    }
}
