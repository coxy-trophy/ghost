package com.coxy.ghost.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * When a query should make GHOST's eye blink, and how long to wait for the frame to land.
 *
 * Shared by the typed path ([com.coxy.ghost.MainActivity]) and the voice path
 * ([com.coxy.ghost.GhostService]). Both previously held identical private copies of the keyword
 * list, the wait, and the permission check — the same duplication that let their prompt assembly
 * drift apart unnoticed.
 */
object VisionTrigger {

    private val KEYWORDS = listOf("see", "look", "camera", "frame", "in front", "what's there")

    /** How long to let the async Camera2 capture land in [ContextStore] before the state is read. */
    const val CAPTURE_WAIT_MS = 1500L

    fun wantsVision(text: String): Boolean = KEYWORDS.any { text.contains(it, ignoreCase = true) }

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
