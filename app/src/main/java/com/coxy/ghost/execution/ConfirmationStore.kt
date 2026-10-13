package com.coxy.ghost.execution

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

internal object ConfirmationStore {
    private val lock = Any()
    private fun ledger(context: Context): ApprovalLedger {
        val prefs = context.getSharedPreferences("ghost_action_approvals", Context.MODE_PRIVATE)
        return ApprovalLedger(object : ApprovalLedger.Store {
            override fun entries(): Map<String, ApprovalLedger.Entry> = prefs.all.mapNotNull { (id, value) ->
                runCatching {
                    val json = JSONObject(value as String)
                    id to ApprovalLedger.Entry(json.getString("payload"), json.getLong("expires"), json.getInt("boot"))
                }.getOrNull()
            }.toMap()
            override fun put(id: String, entry: ApprovalLedger.Entry): Boolean = prefs.edit().putString(id,
                JSONObject().put("payload", entry.payload).put("expires", entry.expiresAt).put("boot", entry.boot).toString()).commit()
            override fun remove(id: String): Boolean = prefs.edit().remove(id).commit()
        })
    }
    fun create(context: Context, payload: String): String? = synchronized(lock) {
        ledger(context).create(payload, SystemClock.elapsedRealtime(), boot(context))
    }
    fun consume(context: Context, id: String): String? = synchronized(lock) {
        ledger(context).consume(id, SystemClock.elapsedRealtime(), boot(context))
    }
    private fun boot(context: Context): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
