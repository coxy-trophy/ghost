package com.coxy.ghost.action

import android.util.Log
import java.io.IOException

/**
 * Executes whitelisted device actions via root shell (`su -c ...`).
 *
 * This is intentionally a small, hardcoded command builder. The only values that reach the shell are
 * action IDs from [ActionCatalog] and parameters that are validated or sanitized. Unknown actions are
 * rejected by the caller.
 */
object SuExecutor {

    private const val TAG = "SuExecutor"

    fun run(action: Action): ActionResult {
        return runCatching {
            val output = when (action.id) {
                "toggle_wifi" -> svcWifi(parseState(action.params["state"]))
                "toggle_bluetooth" -> svcBluetooth(parseState(action.params["state"]))
                "force_stop_app" -> forceStopApp(sanitizePackage(action.params["package"]))
                "disable_app" -> disableApp(sanitizePackage(action.params["package"]))
                "uninstall_app" -> uninstallApp(sanitizePackage(action.params["package"]))
                "send_sms" -> sendSms(action.params["number"] ?: "", action.params["message"] ?: "")
                else -> return ActionResult(action.id, false, "not implemented in SuExecutor")
            }
            ActionResult(action.id, true, output)
        }.getOrElse {
            Log.w(TAG, "action ${action.id} failed", it)
            ActionResult(action.id, false, it.message ?: "shell error")
        }
    }

    private fun svcWifi(state: Boolean): String {
        val cmd = if (state) "svc wifi enable" else "svc wifi disable"
        return exec(cmd).ifBlank { "Wi-Fi ${if (state) "enabled" else "disabled"}" }
    }

    private fun svcBluetooth(state: Boolean): String {
        val cmd = if (state) "svc bluetooth enable" else "svc bluetooth disable"
        return exec(cmd).ifBlank { "Bluetooth ${if (state) "enabled" else "disabled"}" }
    }

    private fun forceStopApp(pkg: String): String {
        return exec("am force-stop $pkg").ifBlank { "$pkg force-stopped" }
    }

    private fun disableApp(pkg: String): String {
        return exec("pm disable-user --user 0 $pkg").ifBlank { "$pkg disabled" }
    }

    private fun uninstallApp(pkg: String): String {
        return exec("pm uninstall --user 0 $pkg").ifBlank { "$pkg uninstalled" }
    }

    private fun sendSms(number: String, message: String): String {
        // Tier 3: this only opens the SMS composer with the fields pre-filled. Actual sending
        // without user interaction is intentionally not implemented in Phase 7.
        val cmd = "am start -a android.intent.action.SENDTO -d sms:${shellEscape(number)} --es sms_body ${shellEscape(message)}"
        return exec(cmd).ifBlank { "SMS composer opened for $number" }
    }

    /** Escape a string for safe use inside a single-quoted shell argument. */
    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"


    private fun parseState(value: String?): Boolean {
        return when (value?.lowercase()?.trim()) {
            "on", "true", "1", "enable", "enabled" -> true
            else -> false
        }
    }

    // Package names are validated by [PackageNames] — the shared shell-injection boundary.
    private fun sanitizePackage(pkg: String?): String = PackageNames.sanitize(pkg)

    private fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            val err = (stderr.ifBlank { stdout }).trim()
            throw IOException("exit=$exit: $err")
        }
        return stdout.trim()
    }
}
