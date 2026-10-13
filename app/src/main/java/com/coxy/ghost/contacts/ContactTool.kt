package com.coxy.ghost.contacts

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.coxy.ghost.action.ActionResult
import com.coxy.ghost.notifications.EphemeralSlot
import com.coxy.ghost.notifications.NotificationClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal object ContactTool {
    data class Request(val query: String, val visible: CompletableDeferred<Boolean>)
    val handoff = PendingRequests()
    class PendingRequests {
        private val slot = EphemeralSlot<Request>()
        fun put(id: String, request: Request) = slot.put(id, request, SystemClock.elapsedRealtime() + 3000)
        fun take(id: String?) = slot.take(id, SystemClock.elapsedRealtime())
        fun discard(id: String) = slot.discard(id)
    }
    suspend fun run(context: Context, query: String): ActionResult = withContext(Dispatchers.Main.immediate) {
        val normalized = ContactQuery.normalize(query)
            ?: return@withContext ActionResult("lookup_contact", false, "Provide a contact name or number, up to 100 characters.")
        if (!ContactAccess.enabled(context) || !ContactAccess.permission(context))
            return@withContext ActionResult("lookup_contact", false, "Enable local contacts in Ghost capabilities first.")
        if (!NotificationClient.canRead(context)) return@withContext ActionResult("lookup_contact", false, "Unlock the phone to view contacts.")
        val id = UUID.randomUUID().toString()
        val visible = CompletableDeferred<Boolean>()
        handoff.put(id, Request(normalized, visible))
        val shown = try {
            context.startActivity(Intent(context, ContactViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ticket", id))
            withTimeoutOrNull(3000) { visible.await() } == true
        } catch (_: RuntimeException) { false }
        finally { handoff.discard(id); visible.cancel() }
        ActionResult("lookup_contact", shown, if (shown)
            "Opened local contact lookup. Names and numbers are not supplied to the assistant model; no call or message was sent."
            else "Could not show local contact lookup. Open Ghost and try again.")
    }
}
