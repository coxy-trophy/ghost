package com.coxy.ghost.notifications

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal object NotificationViewer {
    data class Payload(val snapshot: NotificationSnapshot, val visible: CompletableDeferred<Boolean>)
    val handoff = EphemeralSlot<Payload>()
    suspend fun open(context: Context, snapshot: NotificationSnapshot): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!NotificationClient.canRead(context)) return@withContext false
        val id = UUID.randomUUID().toString()
        val visible = CompletableDeferred<Boolean>()
        handoff.put(id, Payload(snapshot, visible), SystemClock.elapsedRealtime() + 3000)
        try {
            context.startActivity(Intent(context, NotificationViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ticket", id))
            withTimeoutOrNull(3000) { visible.await() } == true
        } catch (_: RuntimeException) { false }
        finally { handoff.discard(id); visible.cancel() }
    }
}
