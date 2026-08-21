package com.coxy.ghost.action

/**
 * Hardcoded allow-list of device actions GHOST can perform (Phase 7).
 *
 * This is the safety boundary: the parser only accepts action IDs present here, and the executor only
 * implements the whitelisted commands. Unknown action names are rejected with an error result.
 */
object ActionCatalog {

    data class Entry(
        val tier: ActionTier,
        val requiredParams: List<String> = emptyList(),
        val optionalParams: List<String> = emptyList(),
        /**
         * Params where at least one must be present, but which one is up to the caller.
         *
         * Added for `launch_app`, which takes either a `name` or a `package` — the model is meant
         * to send the name, but a cloud model that genuinely knows the package should not be
         * rejected for using it, and neither should be mandatory when the other is there.
         */
        val anyOfParams: List<String> = emptyList(),
        val description: String
    )

    private val catalog = mapOf(
        // Tier 0 — read-only
        "get_battery" to Entry(
            tier = ActionTier.TIER_0_READ_ONLY,
            description = "Read current battery level and temperature"
        ),
        "get_wifi_state" to Entry(
            tier = ActionTier.TIER_0_READ_ONLY,
            description = "Read whether Wi-Fi is enabled"
        ),
        "get_bluetooth_state" to Entry(
            tier = ActionTier.TIER_0_READ_ONLY,
            description = "Read whether Bluetooth is enabled"
        ),

        "device_status" to Entry(
            tier = ActionTier.TIER_0_READ_ONLY,
            requiredParams = listOf("device"),
            description = "Ask a device on the network what it is doing and how loud it is"
        ),

        // Tier 1 — reversible
        "toggle_wifi" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("state"),
            description = "Enable or disable Wi-Fi"
        ),
        "toggle_bluetooth" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("state"),
            description = "Enable or disable Bluetooth"
        ),
        "toggle_torch" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("state"),
            description = "Turn the camera LED torch on or off"
        ),
        "set_brightness" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("value"),
            description = "Set screen brightness 0-255"
        ),
        "set_volume" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("value"),
            optionalParams = listOf("stream"),
            description = "Set media volume 0-100"
        ),
        /**
         * The actions whose results are *input* rather than output. Everything else here ends a
         * turn; these start another pass — see [com.coxy.ghost.tool.LookupTurn].
         */
        "web_search" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("query"),
            description = "Look something up on the web and answer from what comes back"
        ),
        "web_fetch" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("url"),
            description = "Read one web page and answer from it"
        ),
        "scan_network" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            optionalParams = listOf("refresh"),
            description = "See what is on the home Wi-Fi network"
        ),
        "launch_app" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            anyOfParams = listOf("name", "package"),
            description = "Open an installed app, named the way Coxy would say it"
        ),

        // Tier 2 — disruptive but recoverable
        // Home-network control. Tier 2 rather than Tier 1 because these reach *off* the phone: a
        // paused film or a changed volume is trivially undone, but it happened to something in the
        // room rather than to a setting. Every one requires `device` — see [com.coxy.ghost.net.LanExecutor].
        "media_control" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("device", "command"),
            description = "Play, pause, stop, next or previous on a device on the network"
        ),
        "set_device_volume" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("device", "value"),
            description = "Set the volume (0-100) on a device on the network"
        ),
        "mute_device" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("device", "state"),
            description = "Mute or unmute a device on the network"
        ),
        "launch_tv_app" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("device", "app"),
            description = "Open an app (YouTube, Netflix) on a TV on the network"
        ),
        // Android TV remote. Pairing is two steps because the television shows a code that has to
        // be read out; see [com.coxy.ghost.net.atv.AtvPairingSession].
        "pair_tv" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("device"),
            description = "Start pairing with a TV so GHOST can use its remote"
        ),
        "pair_code" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("code"),
            description = "Give GHOST the code the TV is showing, to finish pairing"
        ),
        "tv_key" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("device", "key"),
            description = "Press a remote key on a paired TV — power, home, up, volume, mute"
        ),
        "name_device" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("device", "name"),
            description = "Remember what Coxy calls a device on the network"
        ),
        "wake_device" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            requiredParams = listOf("device"),
            description = "Send a wake-on-LAN packet to a device on the network"
        ),

        "force_stop_app" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("package"),
            description = "Force-stop an app"
        ),
        "disable_app" to Entry(
            tier = ActionTier.TIER_2_DISRUPTIVE,
            requiredParams = listOf("package"),
            description = "Disable (not uninstall) an app"
        ),

        // Tier 3 — destructive / outward-facing (requires confirmation)
        "uninstall_app" to Entry(
            tier = ActionTier.TIER_3_DESTRUCTIVE,
            requiredParams = listOf("package"),
            description = "Uninstall an app"
        ),
        "send_sms" to Entry(
            tier = ActionTier.TIER_3_DESTRUCTIVE,
            requiredParams = listOf("number", "message"),
            description = "Send an SMS to a phone number"
        )
    )

    /** Return the catalog entry for a known action, or null. */
    fun lookup(id: String): Entry? = catalog[id.lowercase()]

    /** True if the action name exists in the catalog. */
    fun contains(id: String): Boolean = id.lowercase() in catalog

    /** All known action IDs. */
    fun ids(): Set<String> = catalog.keys

    /** Validate that required params are present, and that any either/or group is satisfied. */
    fun validate(actionId: String, params: Map<String, String>): List<String> {
        val entry = lookup(actionId) ?: return listOf("unknown action '$actionId'")
        fun given(key: String) = !params[key].isNullOrBlank()
        val errors = entry.requiredParams.filterNot(::given)
            .map { "missing required param '$it'" }
            .toMutableList()
        if (entry.anyOfParams.isNotEmpty() && entry.anyOfParams.none(::given)) {
            errors += "missing one of ${entry.anyOfParams.joinToString("/") { "'$it'" }}"
        }
        return errors
    }

    /** Human-readable description of an action. */
    fun describe(actionId: String): String = lookup(actionId)?.description ?: actionId

    /**
     * Compact rendering of the catalog for the system prompt, so the model is told what its hands
     * can actually do instead of inferring it from the two inline examples. Required params are in
     * parentheses; Tier 3 entries are marked because they only count after Coxy says yes.
     */
    fun promptList(): String = catalog.entries.joinToString("; ") { (id, entry) ->
        val shown = entry.requiredParams + entry.anyOfParams.take(1)
        val params = if (shown.isEmpty()) "" else shown.joinToString(",", "(", ")")
        val confirm = if (entry.tier == ActionTier.TIER_3_DESTRUCTIVE) " [only after he says yes]" else ""
        "$id$params$confirm"
    }
}
