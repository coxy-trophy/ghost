package com.coxy.ghost.action

/**
 * A structured device action parsed from the model's reply (Phase 7).
 *
 * @param id the action name as declared in [ActionCatalog]
 * @param tier the risk tier from [ActionTier]
 * @param params key/value arguments for the action; the catalog defines which are required
 */
data class Action(
    val id: String,
    val tier: ActionTier,
    val params: Map<String, String> = emptyMap()
)

enum class ActionTier {
    /** Read-only: no side effects, no approval needed. */
    TIER_0_READ_ONLY,
    /** Reversible: safe to auto-execute. */
    TIER_1_REVERSIBLE,
    /** Disruptive but recoverable: auto-execute + log + notify. */
    TIER_2_DISRUPTIVE,
    /** Destructive / outward-facing: requires explicit user confirmation. */
    TIER_3_DESTRUCTIVE
}
