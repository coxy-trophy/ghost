package com.coxy.ghost.net

import android.content.Context
import android.util.Log
import com.coxy.ghost.action.Action
import com.coxy.ghost.action.ActionResult

/**
 * Runs the actions that reach out onto the home network.
 *
 * Every one of them starts the same way: resolve the device Coxy named, and refuse to proceed
 * without one. That is the whole safety model here, because UPnP and DIAL have no authentication —
 * anything that can reach a renderer can set its volume. A control that guessed its target would
 * be a control that occasionally reaches the wrong television, silently and with no way to tell
 * afterwards.
 *
 * So an ambiguous name is an error carrying the candidates, not a choice made on his behalf, and
 * "the TV" on a network with three of them comes back as a question.
 */
class LanExecutor(private val context: Context) {

    companion object {
        private const val TAG = "LanExecutor"

        val ACTION_IDS = setOf(
            "media_control", "set_device_volume", "mute_device", "launch_tv_app", "wake_device"
        )
    }

    suspend fun run(action: Action): ActionResult {
        val target = action.params["device"]?.trim().orEmpty()
        if (target.isEmpty()) {
            return fail(action, "name the device — there are several on this network")
        }

        val scan = LanScanner.scan(context)
        val devices = when (scan) {
            is LanScanner.Result.NotOnWifi ->
                return fail(action, "not on Wi-Fi, so there's no network to reach")
            is LanScanner.Result.Failed -> return fail(action, scan.reason)
            is LanScanner.Result.Scanned -> scan.devices
        }

        val device = when (val r = DeviceMatcher.resolve(target, devices)) {
            is DeviceMatcher.Resolution.Match -> r.device
            is DeviceMatcher.Resolution.Ambiguous -> return fail(
                action,
                "'$target' matches ${r.candidates.size}: " +
                    r.candidates.joinToString("; ") { DeviceMatcher.label(it) } + " — which one?"
            )
            is DeviceMatcher.Resolution.None -> {
                val hint = if (r.near.isEmpty()) ""
                else " (I can see: " + r.near.joinToString("; ") { DeviceMatcher.label(it) } + ")"
                return fail(action, "nothing on the network called '$target'$hint")
            }
        }

        return runCatching {
            when (action.id) {
                "media_control" -> mediaControl(device, action.params["command"].orEmpty())
                "set_device_volume" -> setVolume(device, action.params["value"].orEmpty())
                "mute_device" -> setMute(device, action.params["state"].orEmpty())
                "launch_tv_app" -> launchApp(device, action.params["app"].orEmpty())
                "wake_device" -> wake(device)
                else -> return fail(action, "not implemented in LanExecutor")
            }
        }.fold(
            onSuccess = { ActionResult(action.id, true, it) },
            onFailure = {
                Log.w(TAG, "${action.id} failed", it)
                fail(action, it.message ?: "network error")
            }
        )
    }

    private suspend fun avTransport(device: LanDevice): UpnpControl.Service =
        serviceOrThrow(device, UpnpControl.AV_TRANSPORT, "playback")

    private suspend fun renderingControl(device: LanDevice): UpnpControl.Service =
        serviceOrThrow(device, UpnpControl.RENDERING_CONTROL, "volume")

    private suspend fun serviceOrThrow(
        device: LanDevice,
        type: String,
        what: String
    ): UpnpControl.Service {
        val url = device.descriptionUrl
            ?: throw IllegalStateException("${nameOf(device)} doesn't publish a way to control it")
        return UpnpControl.services(url)[type]
            ?: throw IllegalStateException("${nameOf(device)} doesn't take $what commands")
    }

    private suspend fun mediaControl(device: LanDevice, command: String): String {
        val service = avTransport(device)
        // InstanceID 0 is the only instance a renderer ever has in practice; Speed is a string on
        // the wire even though it is a number, and devices reject it as anything else.
        val (soap, args) = when (command.lowercase().trim()) {
            "play", "resume", "start" -> "Play" to listOf("InstanceID" to "0", "Speed" to "1")
            "pause", "hold" -> "Pause" to listOf("InstanceID" to "0")
            "stop" -> "Stop" to listOf("InstanceID" to "0")
            "next", "skip" -> "Next" to listOf("InstanceID" to "0")
            "previous", "back" -> "Previous" to listOf("InstanceID" to "0")
            else -> throw IllegalArgumentException(
                "'$command' isn't a playback command — play, pause, stop, next or previous"
            )
        }
        UpnpControl.invoke(service, soap, args).getOrThrow()
        return "${nameOf(device)}: $soap"
    }

    private suspend fun setVolume(device: LanDevice, value: String): String {
        val level = value.trim().toIntOrNull()
            ?: throw IllegalArgumentException("'$value' isn't a volume — give a number 0 to 100")
        val clamped = level.coerceIn(0, 100)
        val service = renderingControl(device)
        UpnpControl.invoke(
            service, "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "$clamped")
        ).getOrThrow()
        return "${nameOf(device)}: volume $clamped"
    }

    private suspend fun setMute(device: LanDevice, state: String): String {
        val on = com.coxy.ghost.action.parseActionState(state)
        val service = renderingControl(device)
        UpnpControl.invoke(
            service, "SetMute",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (on) "1" else "0")
        ).getOrThrow()
        return "${nameOf(device)}: ${if (on) "muted" else "unmuted"}"
    }

    private suspend fun launchApp(device: LanDevice, app: String): String {
        if (app.isBlank()) throw IllegalArgumentException("name the app to open")
        val url = device.descriptionUrl
            ?: throw IllegalStateException("${nameOf(device)} doesn't publish a way to control it")
        val appUrl = DialControl.applicationUrl(url)
            ?: throw IllegalStateException("${nameOf(device)} can't open apps this way")
        val opened = DialControl.launch(appUrl, app).getOrThrow()
        return "${nameOf(device)}: opened $opened"
    }

    private suspend fun wake(device: LanDevice): String {
        val mac = device.mac
            ?: throw IllegalStateException("no hardware address known for ${nameOf(device)}")
        val localIp = LanScanner.wifiIpv4(context)
            ?: throw IllegalStateException("not on Wi-Fi")
        WakeOnLan.wake(mac, localIp).getOrThrow()
        // Deliberately not "woke it up": Wake-on-LAN has no acknowledgement, so this genuinely
        // only knows the packet left.
        return "${nameOf(device)}: wake packet sent — it has no way to confirm it woke"
    }

    private fun nameOf(device: LanDevice): String =
        device.name ?: device.kind ?: device.ip

    private fun fail(action: Action, reason: String) = ActionResult(action.id, false, reason)
}
