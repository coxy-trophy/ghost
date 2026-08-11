package com.coxy.ghost.cloud

/**
 * Runtime settings for the Phase 8 cloud brain.
 */
data class CloudSettings(
    val enabled: Boolean,
    val privacyMode: CloudPromptFilter.Mode,
    val loggingEnabled: Boolean
)
