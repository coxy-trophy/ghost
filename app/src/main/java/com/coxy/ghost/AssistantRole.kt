package com.coxy.ghost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.IOException

/**
 * Making GHOST the device's default assistant — the app the OS launches on the assist gesture.
 *
 * There is no third-party API to *take* the assistant role: `RoleManager.ROLE_ASSISTANT` is not
 * requestable through `createRequestRoleIntent`, and the picker sets it by writing `secure assistant`
 * itself. So there are two honest routes, and this exposes both:
 *
 *  - [setAsDefaultViaRoot] — GHOST is a rooted system app, so it writes the same secure settings the
 *    picker would, immediately and without a reboot. This is the reliable path on this device, and
 *    the same thing the Magisk module applies at every boot.
 *  - [openSystemAssistantSettings] — the ordinary Android route: send the user to the system's
 *    assistant/voice-input settings to pick GHOST by hand. Kept as a fallback and for verification.
 *
 * [AssistActivity] is what makes GHOST a *candidate* in the first place (it handles `ACTION_ASSIST`);
 * this only decides whether the candidate is the one in force.
 */
object AssistantRole {

    private const val TAG = "AssistantRole"

    /** The component the assist gesture resolves to. Must match the Magisk module's grant. */
    const val COMPONENT = "com.coxy.ghost/com.coxy.ghost.AssistActivity"

    /** True when GHOST is the current default assistant. */
    fun isDefault(context: Context): Boolean {
        val current = Settings.Secure.getString(context.contentResolver, SETTING_ASSISTANT)
        return current != null && current.startsWith(context.packageName)
    }

    /**
     * A short label naming who the assistant currently is, for the settings row — "GHOST", "Google",
     * a bare package name, or "none".
     */
    fun currentLabel(context: Context): String {
        val current = Settings.Secure.getString(context.contentResolver, SETTING_ASSISTANT)
        if (current.isNullOrBlank()) return "none"
        if (current.startsWith(context.packageName)) return "GHOST"
        val pkg = ComponentName.unflattenFromString(current)?.packageName ?: current
        return pkg.substringAfterLast('.').ifBlank { pkg }
    }

    /**
     * Write the secure settings that make GHOST the assistant, via the app's own root shell. Clears
     * `voice_interaction_service` because GHOST uses an assist Activity rather than a framework
     * `VoiceInteractionService`, and a lingering interaction service would take precedence.
     *
     * Blocking — call from a background dispatcher.
     */
    fun setAsDefaultViaRoot(): Result<Unit> = runCatching {
        exec("settings put secure assistant $COMPONENT")
        exec("settings put secure voice_interaction_service ''")
        Log.i(TAG, "GHOST set as default assistant via root")
    }

    /** Clear GHOST as the assistant (leaves the field empty; the OS falls back to its default). */
    fun clearViaRoot(): Result<Unit> = runCatching {
        exec("settings put secure assistant ''")
        Log.i(TAG, "assistant cleared via root")
    }

    /**
     * Open the system UI where the assistant is chosen. There is no single stable action across
     * versions/OEMs, so try the assist-and-voice-input screen first and fall back to this app's
     * details page, from which the user can reach default-apps settings.
     */
    fun openSystemAssistantSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}"),
            ),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        }
        Log.w(TAG, "no settings activity accepted the assistant intent")
    }

    private const val SETTING_ASSISTANT = "assistant"

    private fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) throw IOException("exit=$exit: ${(stderr.ifBlank { stdout }).trim()}")
        return stdout.trim()
    }
}
