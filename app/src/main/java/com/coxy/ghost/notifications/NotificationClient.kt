package com.coxy.ghost.notifications

import android.app.BroadcastOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal object NotificationClient {
    fun canRead(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

    suspend fun read(context: Context): NotificationSnapshot = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < 34) return@withContext NotificationSnapshot("UNAVAILABLE")
        if (!canRead(context)) return@withContext NotificationSnapshot("LOCKED")
        val app = context.applicationContext
        val expectedUid = runCatching { app.packageManager.getApplicationInfo(NotificationProtocol.PEER, 0).uid }.getOrNull()
            ?: return@withContext NotificationSnapshot("UNAVAILABLE")
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<NotificationSnapshot>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (result.isCompleted || intent.action != NotificationProtocol.RESULT) return
                if (!NotificationProtocol.authenticated(sentFromPackage, sentFromUid, expectedUid)) return
                try {
                    if (intent.getIntExtra("protocol_version", 0) != 1 || intent.getStringExtra("request_id") != id) return
                    if (!canRead(app)) { result.complete(NotificationSnapshot("LOCKED")); return }
                    val raw = intent.getStringExtra("result_json") ?: return
                    val snapshot = runCatching { NotificationProtocol.parse(raw) }.getOrNull() ?: return
                    result.complete(snapshot)
                } catch (_: RuntimeException) { /* Malformed data never grants access and is never logged. */ }
            }
        }
        var registered = false
        try {
            app.registerReceiver(receiver, IntentFilter(NotificationProtocol.RESULT), Context.RECEIVER_EXPORTED)
            registered = true
            app.sendBroadcast(Intent(NotificationProtocol.QUERY).setPackage(NotificationProtocol.PEER)
                .putExtra("protocol_version", 1).putExtra("request_id", id), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            val snapshot = withTimeoutOrNull(10_000) { result.await() } ?: NotificationSnapshot("UNAVAILABLE")
            if (canRead(app)) snapshot else NotificationSnapshot("LOCKED")
        } catch (_: SecurityException) {
            NotificationSnapshot("UNAVAILABLE")
        } finally {
            result.cancel()
            if (registered) app.unregisterReceiver(receiver)
        }
    }
}
