package com.coxy.ghost.net.atv

/**
 * Android key codes, mapped from what Coxy would say.
 *
 * These are the platform's own `KeyEvent` constants — the remote protocol carries the same numbers
 * a physical remote would generate, which is why this reaches things UPnP cannot: power is a key on
 * a remote, and was never a media-transport command.
 */
object AtvKeys {

    const val POWER = 26
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val VOLUME_MUTE = 164
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val TV_INPUT = 178

    private val BY_WORD = mapOf(
        "power" to POWER, "off" to POWER, "on" to POWER, "poweroff" to POWER, "poweron" to POWER,
        "home" to HOME, "back" to BACK,
        "up" to DPAD_UP, "down" to DPAD_DOWN, "left" to DPAD_LEFT, "right" to DPAD_RIGHT,
        "ok" to DPAD_CENTER, "select" to DPAD_CENTER, "enter" to DPAD_CENTER,
        "volumeup" to VOLUME_UP, "louder" to VOLUME_UP,
        "volumedown" to VOLUME_DOWN, "quieter" to VOLUME_DOWN,
        "mute" to VOLUME_MUTE, "unmute" to VOLUME_MUTE,
        "play" to MEDIA_PLAY_PAUSE, "pause" to MEDIA_PLAY_PAUSE, "playpause" to MEDIA_PLAY_PAUSE,
        "stop" to MEDIA_STOP, "next" to MEDIA_NEXT, "previous" to MEDIA_PREVIOUS,
        "rewind" to MEDIA_REWIND, "forward" to MEDIA_FAST_FORWARD,
        "channelup" to CHANNEL_UP, "channeldown" to CHANNEL_DOWN,
        "input" to TV_INPUT, "source" to TV_INPUT
    )

    /**
     * The key code for a spoken word, or null.
     *
     * Note "on" and "off" both map to POWER, because that is what the key does: a television's
     * power button toggles. GHOST has to say so rather than claiming she turned it off, since the
     * protocol gives back no state either way.
     */
    fun forWord(word: String): Int? {
        val key = word.lowercase().filter { it.isLetterOrDigit() }
        return BY_WORD[key] ?: key.toIntOrNull()?.takeIf { it in 1..300 }
    }

    /** True if this key toggles rather than sets, so a reply must not claim a direction. */
    fun isToggle(code: Int): Boolean = code == POWER || code == VOLUME_MUTE
}
