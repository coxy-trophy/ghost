package com.coxy.ghost.net.ios

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An iPhone GHOST can reach, and how.
 *
 * Configured rather than discovered, which is the whole difference from [com.coxy.ghost.net.LanDevice].
 * There is no adb for iOS and no inbound trigger of any kind: a Shortcut runs when the phone decides
 * to run it, so reaching one means going through something on it that listens — a relay app with a
 * webhook. GHOST cannot find that by scanning; Coxy has to say where it is.
 *
 * Deliberately provider-agnostic. Pushcut, the Home Assistant companion and anything else with a URL
 * all look the same from here: a POST, an optional header, and a JSON body. Hardcoding one vendor
 * would mean a rebuild the day he changes his mind about which app to pay for.
 *
 * [actions] is the extensible half and the reason this is a table rather than a fixed catalog entry.
 * What an iPhone can do is whatever Shortcuts Coxy has built, so adding a capability is adding a
 * Shortcut and registering its name here — never a new version of GHOST.
 */
@Entity(tableName = "ios_phone", indices = [Index(value = ["nickname"], unique = true)])
data class IosPhone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What Coxy calls it. The only way it is ever addressed. */
    val nickname: String,
    /**
     * Where to POST. May contain `{action}`, which is substituted before the request — Pushcut puts
     * the notification name in the path, while Home Assistant puts everything in the body.
     */
    val relayUrl: String,
    /** e.g. `API-Key` or `Authorization`. Null when the secret is in the URL, as Pushcut does it. */
    val authHeader: String? = null,
    val authValue: String? = null,
    /**
     * Action names this phone answers to, one per line.
     *
     * A list rather than an open string because the model must not invent them: a Shortcut that
     * does not exist fails silently on the far phone, and "I sent it" would be a lie with nothing
     * to contradict it. Unknown names are refused here, and GHOST says which ones exist.
     */
    val actions: String = "",
    val addedMs: Long,
    val lastUsedMs: Long? = null
) {
    fun actionSet(): List<String> =
        actions.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Case- and space-insensitive lookup, returning the registered spelling. */
    fun resolveAction(spoken: String): String? {
        val key = normalise(spoken)
        return actionSet().firstOrNull { normalise(it) == key }
            ?: actionSet().firstOrNull { normalise(it).startsWith(key) && key.length >= 3 }
    }

    companion object {
        fun normalise(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }
    }
}
