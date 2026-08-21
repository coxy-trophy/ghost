package com.coxy.ghost.net.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The things GHOST can do to another Android phone, as a fixed list rather than a shell.
 *
 * A debug-bridge connection can run anything, and this deliberately does not expose that. The model
 * chooses from named actions and never composes a command line, for the same reason
 * [com.coxy.ghost.action.ActionCatalog] exists at all: a model that can write shell is a model that
 * can be talked into writing shell by a web page it just read. Every command below is assembled
 * here from a fixed template with the argument escaped.
 *
 * Scoped to what "I don't want to get up" actually means — media, volume, screen, navigation,
 * launching something, and asking how it is doing.
 */
object PhoneControl {

    private const val TAG = "PhoneControl"

    /** What a phone can be asked to do. */
    enum class Command(
        val words: Set<String>,
        /** True when the effect is on the far phone's screen or speaker rather than a reading. */
        val changesThings: Boolean = true
    ) {
        PLAY_PAUSE(setOf("play", "pause", "playpause", "resume")),
        NEXT(setOf("next", "skip")),
        PREVIOUS(setOf("previous", "back a track", "prev")),
        STOP(setOf("stop")),
        VOLUME_UP(setOf("volumeup", "louder", "turn it up")),
        VOLUME_DOWN(setOf("volumedown", "quieter", "turn it down")),
        MUTE(setOf("mute", "unmute", "silence")),
        SCREEN(setOf("screen", "power", "wake", "screenon", "screenoff")),
        HOME(setOf("home")),
        BACK(setOf("back")),
        RECENTS(setOf("recents", "apps", "switcher")),
        LOCK(setOf("lock", "locked")),
        RING(setOf("ring", "find", "where is it", "findit")),
        BATTERY(setOf("battery", "charge", "howmuchbattery"), changesThings = false),
        WHATS_PLAYING(setOf("whatsplaying", "playing", "nowplaying"), changesThings = false),
        STATUS(setOf("status", "state", "how is it"), changesThings = false);

        companion object {
            fun forWord(raw: String): Command? {
                val key = raw.lowercase().filter { it.isLetterOrDigit() }
                return entries.firstOrNull { c ->
                    c.words.any { it.filter { ch -> ch.isLetterOrDigit() } == key }
                }
            }
        }
    }

    // Android key codes, the same numbers a physical button generates.
    private const val KEY_HOME = 3
    private const val KEY_BACK = 4
    private const val KEY_VOLUME_UP = 24
    private const val KEY_VOLUME_DOWN = 25
    private const val KEY_POWER = 26
    private const val KEY_MEDIA_PLAY_PAUSE = 85
    private const val KEY_MEDIA_STOP = 86
    private const val KEY_MEDIA_NEXT = 87
    private const val KEY_MEDIA_PREVIOUS = 88
    private const val KEY_VOLUME_MUTE = 164
    private const val KEY_RECENTS = 187
    private const val KEY_SLEEP = 223

    /** Run [command] on the phone at [host], returning something worth saying back. */
    suspend fun run(host: String, command: Command, argument: String? = null): String =
        withContext(Dispatchers.IO) {
            AdbConnection.open(host).use { adb ->
                when (command) {
                    Command.PLAY_PAUSE -> key(adb, KEY_MEDIA_PLAY_PAUSE, "play/pause")
                    Command.NEXT -> key(adb, KEY_MEDIA_NEXT, "skipped")
                    Command.PREVIOUS -> key(adb, KEY_MEDIA_PREVIOUS, "went back")
                    Command.STOP -> key(adb, KEY_MEDIA_STOP, "stopped")
                    Command.VOLUME_UP -> key(adb, KEY_VOLUME_UP, "turned up")
                    Command.VOLUME_DOWN -> key(adb, KEY_VOLUME_DOWN, "turned down")
                    Command.MUTE -> key(adb, KEY_VOLUME_MUTE, "mute pressed — it toggles")
                    Command.SCREEN -> key(adb, KEY_POWER, "screen pressed — it toggles")
                    Command.HOME -> key(adb, KEY_HOME, "home")
                    Command.BACK -> key(adb, KEY_BACK, "back")
                    Command.RECENTS -> key(adb, KEY_RECENTS, "recents")
                    Command.LOCK -> key(adb, KEY_SLEEP, "locked")
                    Command.RING -> ring(adb)
                    Command.BATTERY -> battery(adb)
                    Command.WHATS_PLAYING -> nowPlaying(adb)
                    Command.STATUS -> status(adb)
                }
            }
        }

    /** Launch an app by package name. Resolved on the far phone, not guessed here. */
    suspend fun launch(host: String, query: String): String = withContext(Dispatchers.IO) {
        val safe = query.trim()
        require(safe.matches(Regex("^[a-zA-Z0-9._]+$"))) {
            "'$query' isn't a package name — I need something like com.spotify.music"
        }
        AdbConnection.open(host).use { adb ->
            val out = adb.shell("shell:monkey -p $safe -c android.intent.category.LAUNCHER 1")
            if (out.contains("No activities found", ignoreCase = true) ||
                out.contains("Error", ignoreCase = true)
            ) {
                throw IOException("that phone doesn't have $safe")
            }
            "opened $safe"
        }
    }

    /** The packages installed over there, so a name can be matched against something real. */
    suspend fun packages(host: String): List<String> = withContext(Dispatchers.IO) {
        AdbConnection.open(host).use { adb ->
            adb.shell("shell:pm list packages -3")
                .lineSequence()
                .mapNotNull { it.substringAfter("package:", "").trim().takeIf(String::isNotEmpty) }
                .toList()
        }
    }

    private fun key(adb: AdbConnection, code: Int, said: String): String {
        adb.shell("shell:input keyevent $code")
        return said
    }

    /**
     * Make the phone findable: full volume, then a long ring on the alarm stream.
     *
     * The alarm stream on purpose — it is the one that still sounds when the phone is on silent,
     * which is the state a lost phone is usually in.
     */
    private fun ring(adb: AdbConnection): String {
        adb.shell("shell:media volume --stream 4 --set 7")
        adb.shell("shell:am start -a android.intent.action.SET_ALARM --ez android.intent.extra.alarm.SKIP_UI true --ei android.intent.extra.alarm.HOUR 0 --ei android.intent.extra.alarm.MINUTES 0")
        return "set it ringing — alarm stream, so it sounds even on silent"
    }

    private fun battery(adb: AdbConnection): String {
        val out = adb.shell("shell:dumpsys battery")
        val level = Regex("level: (\\d+)").find(out)?.groupValues?.get(1)
        val plugged = Regex("powered: (true|false)").find(out)?.groupValues?.get(1) == "true" ||
            Regex("AC powered: true").containsMatchIn(out) ||
            Regex("USB powered: true").containsMatchIn(out)
        return when {
            level == null -> "couldn't read its battery"
            plugged -> "$level%, charging"
            else -> "$level%"
        }
    }

    private fun nowPlaying(adb: AdbConnection): String {
        val out = adb.shell("shell:dumpsys media_session")
        val pkg = Regex("package=([a-zA-Z0-9._]+)").find(out)?.groupValues?.get(1)
        val playing = out.contains("state=PlaybackState {state=3") || out.contains("PLAYING")
        return when {
            pkg == null -> "nothing seems to be playing"
            playing -> "$pkg is playing"
            else -> "$pkg has something queued but paused"
        }
    }

    private fun status(adb: AdbConnection): String {
        val screen = adb.shell("shell:dumpsys power | grep -m1 'mWakefulness='")
            .substringAfter("mWakefulness=", "").trim().ifEmpty { "unknown" }
        val model = adb.shell("shell:getprop ro.product.model")
        return "$model, screen $screen, ${battery(adb)}"
    }
}
