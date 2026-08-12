package com.coxy.ghost.action

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

/**
 * Executes whitelisted device actions through Android framework APIs (Phase 7).
 *
 * Used for operations where a framework call is more reliable than a root shell command, or where
 * shell access is unnecessary.
 */
class FrameworkExecutor(private val context: Context) {

    fun run(action: Action): ActionResult {
        return runCatching {
            val output = when (action.id) {
                "get_battery" -> getBattery()
                "get_wifi_state" -> getWifiState()
                "get_bluetooth_state" -> getBluetoothState()
                "toggle_torch" -> toggleTorch(parseActionState(action.params["state"]))
                "set_brightness" -> setBrightness(parseInt(action.params["value"], 0, 255))
                "set_volume" -> setVolume(
                    parseInt(action.params["value"], 0, 100),
                    action.params["stream"] ?: "music"
                )
                "launch_app" -> launchApp(sanitizePackage(action.params["package"]))
                else -> return ActionResult(action.id, false, "not implemented in FrameworkExecutor")
            }
            ActionResult(action.id, true, output)
        }.getOrElse {
            Log.w(TAG, "action ${action.id} failed", it)
            ActionResult(action.id, false, it.message ?: "framework error")
        }
    }

    private fun getBattery(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "unknown"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val pct = if (scale > 0) (level * 100 / scale) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "${pct}%${if (charging) " charging" else ""}, ${temp / 10f}°C"
    }

    private fun getWifiState(): String {
        val on = Settings.Global.getInt(context.contentResolver, "wifi_on", 0) == 1
        return if (on) "Wi-Fi on" else "Wi-Fi off"
    }

    private fun getBluetoothState(): String {
        val on = Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) == 1
        return if (on) "Bluetooth on" else "Bluetooth off"
    }

    private fun toggleTorch(state: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull() ?: throw IllegalStateException("no camera")
        cm.setTorchMode(cameraId, state)
        return "torch ${if (state) "on" else "off"}"
    }

    private fun setBrightness(value: Int): String {
        // Requires WRITE_SETTINGS; as a system/priv-app this is usually granted. If not, the
        // caller sees the framework SecurityException and can fall back to SuExecutor.
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return "brightness set to $value"
    }

    private fun setVolume(value: Int, streamName: String): String {
        val stream = when (streamName.lowercase()) {
            "music", "media" -> AudioManager.STREAM_MUSIC
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "voice_call", "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        val idx = (value * max / 100).coerceIn(0, max)
        am.setStreamVolume(stream, idx, 0)
        return "${streamName.lowercase()} volume set to $value%"
    }

    private fun launchApp(pkg: String): String {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: throw IllegalArgumentException("no launcher intent for $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "launched $pkg"
    }


    private fun parseInt(value: String?, min: Int, max: Int): Int {
        val raw = value?.trim() ?: "0"
        return raw.toIntOrNull()?.coerceIn(min, max)
            ?: throw IllegalArgumentException("'$raw' is not an integer")
    }

    private fun sanitizePackage(pkg: String?): String = PackageNames.sanitize(pkg)

    companion object {
        private const val TAG = "FrameworkExecutor"
    }
}
