package com.coxy.ghost.platform

/** Module visibility is capability evidence, never proof of enrollment or working recognition. */
internal object PlatformVoiceCapability {
    fun assessment(active: Boolean, moduleCount: Int?, failure: String?): String = when {
        !active -> "NOT_SELECTED"
        failure != null -> "PROBE_BLOCKED"
        moduleCount == null -> "UNKNOWN"
        moduleCount == 0 -> "NO_DSP_MODULES"
        moduleCount > 0 -> "MODULES_PRESENT_ENROLLMENT_UNVERIFIED"
        else -> "UNKNOWN"
    }
}
