package com.coxy.ghost.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Android supplies sender identity; extras never grant trust. */
class GhostControlReceiver : BroadcastReceiver() {
    companion object { private val starts = LinkedHashSet<String>() }
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 34 || intent.action != GhostIsland.CONTROL) return
        if (sentFromPackage != GhostIsland.PEER) return
        val uid = runCatching { context.packageManager.getApplicationInfo(GhostIsland.PEER, 0).uid }.getOrNull()
        if (uid == null || uid != sentFromUid) return
        if (intent.getIntExtra("protocol_version", 0) != 1) return
        val command = intent.getStringExtra("command") ?: return
        if (command !in setOf("START", "CANCEL", "QUERY")) return
        if (intent.hasExtra("surface_enabled")) GhostIsland.advertiseSurface(intent.getBooleanExtra("surface_enabled", false))
        val request = intent.getStringExtra("request_id").orEmpty()
        if (command == "START") {
            if (request.isEmpty() || request.length > 128) return
            if (!starts.add(request)) { GhostIsland.publish(); return }
            if (starts.size > 128) starts.remove(starts.first())
        }
        // A stopped Ghost stays stopped; opening Ghost gives the user the normal start controls.
        if (command == "QUERY") GhostIsland.publish()
        else GhostIsland.onControl?.invoke(command, intent.getStringExtra("session_id").orEmpty(), intent.getStringExtra("request_id").orEmpty().take(128))
    }
}
