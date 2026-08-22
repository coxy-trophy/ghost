package com.coxy.ghost.net.ios

import android.content.Context
import android.util.Log
import com.coxy.ghost.action.Action
import com.coxy.ghost.action.ActionResult
import com.coxy.ghost.memory.GhostDatabase
import com.coxy.ghost.net.LanScanner

/**
 * Runs the actions that reach an iPhone.
 *
 * Two rules, both there because a Shortcut that does not exist fails silently on the far phone and
 * GHOST would otherwise report success into the void:
 *
 * 1. **The phone is named, never guessed.** Same rule as everything else on the network.
 * 2. **The action must be one Coxy registered.** An unregistered name is refused here, with the
 *    registered ones listed, rather than posted at a relay that will cheerfully accept it and do
 *    nothing.
 */
class IosExecutor(private val context: Context) {

    companion object {
        private const val TAG = "IosExecutor"

        val ACTION_IDS = setOf("iphone_control", "iphone_ask", "iphone_actions")
    }

    private fun dao() = GhostDatabase.get(context).iosDao()

    suspend fun run(action: Action): ActionResult {
        val target = action.params["device"]?.trim().orEmpty()
        if (target.isEmpty()) return fail(action, "name the iPhone")

        val phones = runCatching { dao().phones() }.getOrDefault(emptyList())
        if (phones.isEmpty()) {
            return fail(
                action,
                "no iPhone is set up yet — add one on the Devices screen with its relay address"
            )
        }
        val phone = resolve(target, phones)
            ?: return fail(
                action,
                "nothing called '$target' — I know: " + phones.joinToString { it.nickname }
            )

        return runCatching {
            when (action.id) {
                "iphone_actions" -> listActions(phone)
                "iphone_control" -> control(phone, action.params["command"].orEmpty(),
                    action.params["value"], expectReply = false)
                "iphone_ask" -> control(phone, action.params["command"].orEmpty(),
                    action.params["value"], expectReply = true)
                else -> return fail(action, "not implemented in IosExecutor")
            }
        }.fold(
            onSuccess = { ActionResult(action.id, true, it) },
            onFailure = {
                Log.w(TAG, "${action.id} failed", it)
                fail(action, it.message ?: "couldn't reach that iPhone")
            }
        )
    }

    private fun resolve(query: String, phones: List<IosPhone>): IosPhone? {
        val key = IosPhone.normalise(query)
        return phones.firstOrNull { IosPhone.normalise(it.nickname) == key }
            ?: phones.firstOrNull { IosPhone.normalise(it.nickname).contains(key) && key.length >= 3 }
            // One iPhone and an unrecognised name is almost always that iPhone; several is not, and
            // guessing between them would be the same fault the LAN executor refuses to commit.
            ?: phones.singleOrNull()
    }

    private fun listActions(phone: IosPhone): String {
        val actions = phone.actionSet()
        return if (actions.isEmpty()) {
            "${phone.nickname} has no actions set up yet — each one is a Shortcut on that phone, " +
                "registered on the Devices screen"
        } else {
            "${phone.nickname} answers to: ${actions.joinToString(", ")}"
        }
    }

    private suspend fun control(
        phone: IosPhone,
        spoken: String,
        value: String?,
        expectReply: Boolean
    ): String {
        if (spoken.isBlank()) throw IllegalArgumentException("say what to do")
        val resolved = phone.resolveAction(spoken)
            ?: throw IllegalArgumentException(
                "${phone.nickname} has no \"$spoken\" — it answers to: " +
                    phone.actionSet().joinToString(", ").ifEmpty { "nothing yet" }
            )

        // A reply needs somewhere to arrive. Off Wi-Fi the relay still works — it goes out over the
        // internet — but the Shortcut has no route back to this phone, so asking is refused rather
        // than left to time out looking like the iPhone ignored it.
        val pending = if (expectReply) {
            val ip = LanScanner.wifiIpv4(context)
                ?: throw IllegalStateException(
                    "I can send that, but not hear back — the reply needs us both on Wi-Fi"
                )
            IosCallback.expect(ip)
        } else null

        IosRelay.send(phone, resolved, value, pending?.replyUrl)
        runCatching { dao().touch(phone.id, System.currentTimeMillis()) }

        if (pending == null) return "${phone.nickname}: sent \"$resolved\""

        val answer = IosCallback.await(pending)
        return when {
            answer.isNullOrBlank() ->
                "${phone.nickname}: sent \"$resolved\" but nothing came back — the Shortcut may " +
                    "not be posting to the reply address"
            else -> "${phone.nickname}: $answer"
        }
    }

    private fun fail(action: Action, reason: String) = ActionResult(action.id, false, reason)
}
