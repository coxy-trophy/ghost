package com.coxy.ghost.notifications

import org.json.JSONObject

internal data class NotificationRow(val packageName: String, val title: String, val text: String, val postTime: Long)
internal data class NotificationSnapshot(val status: String, val rows: List<NotificationRow> = emptyList())

internal object NotificationProtocol {
    const val PEER = "com.coxy.nocturne.ui"
    const val QUERY = "$PEER.ghost.NOTIFICATIONS_QUERY"
    const val RESULT = "$PEER.ghost.NOTIFICATIONS_RESULT"
    fun authenticated(pkg: String?, uid: Int, expectedUid: Int) = pkg == PEER && expectedUid >= 0 && uid == expectedUid

    fun parse(raw: String): NotificationSnapshot {
        require(raw.toByteArray(Charsets.UTF_8).size <= 32_000)
        val obj = JSONObject(raw)
        val status = obj.getString("status")
        require(status in setOf("OK", "DISABLED", "LOCKED", "UNAVAILABLE", "INVALID_REQUEST"))
        val rows = obj.getJSONArray("rows")
        require(rows.length() <= 20)
        require(status == "OK" || rows.length() == 0)
        val result = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val pkg = row.get("package") as? String ?: error("Invalid package")
            val title = row.get("title") as? String ?: error("Invalid title")
            val body = row.get("text") as? String ?: error("Invalid text")
            val stamp = row.get("postTime")
            require(stamp is Long || stamp is Int)
            val time = (stamp as Number).toLong()
            require(time >= 0 && pkg.length <= 255 && pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")))
            require(title.length <= 200 && body.length <= 500)
            NotificationRow(pkg, title, body, time)
        }
        return NotificationSnapshot(status, result)
    }
}
