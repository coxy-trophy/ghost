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
            "media_control", "set_device_volume", "mute_device", "launch_tv_app", "wake_device",
            "name_device", "device_status", "pair_tv", "pair_code", "tv_key"
        )
    }

    suspend fun run(action: Action): ActionResult {
        // The only action with no device: the code belongs to whichever pairing is already open,
        // and asking Coxy to name the television again while it is showing him a code would be
        // asking him to repeat himself.
        if (action.id == "pair_code") return pairCode(action)

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
                "device_status" -> status(device)
                "media_control" -> mediaControl(device, action.params["command"].orEmpty())
                "set_device_volume" -> setVolume(device, action.params["value"].orEmpty())
                "mute_device" -> setMute(device, action.params["state"].orEmpty())
                "launch_tv_app" -> launchApp(device, action.params["app"].orEmpty())
                "wake_device" -> wake(device)
                "name_device" -> name(device, action.params["name"].orEmpty())
                "pair_tv" -> pairStart(device)
                "tv_key" -> tvKey(device, action.params["key"].orEmpty())
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
        if (device.descriptionUrls.isEmpty()) {
            throw IllegalStateException("${nameOf(device)} doesn't publish a way to control it")
        }
        return UpnpControl.findService(device.descriptionUrls, type)
            ?: throw IllegalStateException("${nameOf(device)} doesn't take $what commands")
    }

    /**
     * Read-only: what the device is doing, and how loud.
     *
     * Tier 0, and the only network action that changes nothing — which also makes it the one that
     * can be run on a device to find out whether it will take commands at all, without being the
     * reason someone's film stopped.
     */
    private suspend fun status(device: LanDevice): String {
        val parts = mutableListOf<String>()

        UpnpControl.findService(device.descriptionUrls, UpnpControl.RENDERING_CONTROL)?.let { rc ->
            UpnpControl.invoke(
                rc, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")
            ).getOrNull()?.let { body ->
                UpnpControl.valueIn(body, "CurrentVolume")?.let { parts += "volume $it" }
            }
            UpnpControl.invoke(
                rc, "GetMute", listOf("InstanceID" to "0", "Channel" to "Master")
            ).getOrNull()?.let { body ->
                if (UpnpControl.valueIn(body, "CurrentMute") == "1") parts += "muted"
            }
        }

        UpnpControl.findService(device.descriptionUrls, UpnpControl.AV_TRANSPORT)?.let { av ->
            UpnpControl.invoke(av, "GetTransportInfo", listOf("InstanceID" to "0"))
                .getOrNull()?.let { body ->
                    UpnpControl.valueIn(body, "CurrentTransportState")?.let {
                        parts += when (it) {
                            "PLAYING" -> "playing"
                            "PAUSED_PLAYBACK" -> "paused"
                            "STOPPED" -> "stopped"
                            "NO_MEDIA_PRESENT" -> "nothing playing"
                            else -> it.lowercase()
                        }
                    }
                }
        }

        if (parts.isEmpty()) {
            throw IllegalStateException("${nameOf(device)} didn't answer any status question")
        }
        return "${nameOf(device)}: ${parts.joinToString(", ")}"
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
        if (device.descriptionUrls.isEmpty()) {
            throw IllegalStateException("${nameOf(device)} doesn't publish a way to control it")
        }
        // Same reason as serviceOrThrow: the Application-URL header is on the DIAL description,
        // which may not be the first one this device published.
        val appUrl = device.descriptionUrls.firstNotNullOfOrNull { DialControl.applicationUrl(it) }
            ?: throw IllegalStateException("${nameOf(device)} can't open apps this way")
        val opened = DialControl.launch(appUrl, app).getOrThrow()
        return "${nameOf(device)}: opened $opened"
    }

    // --- Android TV remote -------------------------------------------------------------------

    private suspend fun pairStart(device: LanDevice): String {
        if (device.services.none { it.contains("androidtvremote") }) {
            throw IllegalStateException(
                "${nameOf(device)} isn't an Android TV — it doesn't offer a remote to pair with"
            )
        }
        val session = com.coxy.ghost.net.atv.AtvPairing(device.ip)
        runCatching { session.begin() }.getOrElse {
            session.close()
            throw IllegalStateException(
                it.message ?: "couldn't start pairing with ${nameOf(device)}"
            )
        }
        com.coxy.ghost.net.atv.AtvPairingSession.start(device.ip, session)
        return "${nameOf(device)} is showing a six-character code — read it out and I'll finish"
    }

    private suspend fun pairCode(action: Action): ActionResult {
        val code = action.params["code"]?.trim().orEmpty()
        val pending = com.coxy.ghost.net.atv.AtvPairingSession.take()
            ?: return ActionResult(
                action.id, false,
                "no pairing is waiting — the code has to follow straight after starting one"
            )
        val (host, session) = pending
        return try {
            session.finish(code)
            com.coxy.ghost.net.atv.AtvPairingSession.done()
            session.close()
            com.coxy.ghost.net.atv.AtvPaired.remember(context, host)
            ActionResult(action.id, true, "paired with $host — the remote works now")
        } catch (e: Exception) {
            // A wrong code is worth another try on the same session; a dead socket is not, and
            // saying which it was saves reading the code out again for nothing.
            val recoverable = e is IllegalArgumentException ||
                e.message?.contains("doesn't match") == true
            if (!recoverable) {
                com.coxy.ghost.net.atv.AtvPairingSession.abandon()
            }
            ActionResult(action.id, false, e.message ?: "the code was not accepted")
        }
    }

    private suspend fun tvKey(device: LanDevice, key: String): String {
        val code = com.coxy.ghost.net.atv.AtvKeys.forWord(key)
            ?: throw IllegalArgumentException("'$key' isn't a remote key I know")
        com.coxy.ghost.net.atv.AtvRemote.sendKeys(device.ip, listOf(code))
        // Power and mute toggle; the protocol reports no state back either way, so claiming a
        // direction would be inventing the half of the answer that matters.
        return if (com.coxy.ghost.net.atv.AtvKeys.isToggle(code)) {
            "${nameOf(device)}: pressed $key — it toggles, so look and see"
        } else {
            "${nameOf(device)}: pressed $key"
        }
    }

    private suspend fun name(device: LanDevice, name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("what should I call it?")
        val key = DeviceNames.set(context, device, trimmed)
        // Invalidated so the very next command sees the new name; without this the cached scan
        // would keep the old label for up to two minutes and "turn up the office TV" would still
        // come back as a three-way question immediately after naming it.
        LanScanner.invalidate()
        val stability =
            if (key.startsWith("mac:")) "that'll stick even if its address changes"
            else "only by address, so it may not stick if the router moves it"
        return "${nameOf(device)} is now \"$trimmed\" — $stability"
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
        device.bestName ?: device.kind ?: device.ip

    private fun fail(action: Action, reason: String) = ActionResult(action.id, false, reason)
}
