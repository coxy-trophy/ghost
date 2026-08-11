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
            requiredParams = listOf("package"),
            description = "Launch an installed app by package name"
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

    /** Validate that required params are present. */
    fun validate(actionId: String, params: Map<String, String>): List<String> {
        val entry = lookup(actionId) ?: return listOf("unknown action '$actionId'")
        val missing = entry.requiredParams.filter { it !in params || params[it].isNullOrBlank() }
        return missing.map { "missing required param '$it'" }
    }

    /** Human-readable description of an action. */
    fun describe(actionId: String): String = lookup(actionId)?.description ?: actionId
}
