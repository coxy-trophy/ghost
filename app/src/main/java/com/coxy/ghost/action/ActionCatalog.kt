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
        "launch_app" to Entry(
            tier = ActionTier.TIER_1_REVERSIBLE,
            anyOfParams = listOf("name", "package"),
            description = "Open an installed app, named the way Coxy would say it"
        ),

        // Tier 2 — disruptive but recoverable
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
