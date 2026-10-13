package com.coxy.ghost.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.coxy.ghost.notifications.NotificationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ContactAccess {
    fun enabled(context: Context) = context.getSharedPreferences("ghost_local_capabilities", Context.MODE_PRIVATE)
        .getBoolean("contacts_enabled", false)
    fun permission(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("ghost_local_capabilities", Context.MODE_PRIVATE).edit().putBoolean("contacts_enabled", value).apply()
    }
    suspend fun search(context: Context, query: String): ContactSnapshot = withContext(Dispatchers.IO) {
        val normalized = ContactQuery.normalize(query) ?: return@withContext ContactSnapshot("INVALID_QUERY")
        if (!enabled(context) || !permission(context)) return@withContext ContactSnapshot("DISABLED")
        if (!NotificationClient.canRead(context)) return@withContext ContactSnapshot("LOCKED")
        try {
            val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(normalized))
            val result = mutableListOf<ContactMatch>()
            val seen = HashSet<Pair<Long, String>>()
            context.contentResolver.query(uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { cursor ->
                var inspected = 0
                while (result.size < 20 && inspected++ < 100 && cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty().take(200)
                    val number = cursor.getString(2).orEmpty().take(100)
                    if (number.isNotBlank() && seen.add(id to number)) result += ContactMatch(id, name, number)
                }
            } ?: return@withContext ContactSnapshot("UNAVAILABLE")
            if (!enabled(context) || !permission(context)) ContactSnapshot("DISABLED")
            else if (!NotificationClient.canRead(context)) ContactSnapshot("LOCKED")
            else ContactSnapshot("OK", result)
        } catch (_: SecurityException) { ContactSnapshot("DISABLED") }
        catch (_: RuntimeException) { ContactSnapshot("UNAVAILABLE") }
    }
}
