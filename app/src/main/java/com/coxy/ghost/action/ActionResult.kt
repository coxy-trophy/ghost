package com.coxy.ghost.action

/**
 * Outcome of attempting one action. Kept simple so it can be appended to the assistant reply as a
 * concise English sentence.
 */
data class ActionResult(
    val actionId: String,
    val success: Boolean,
    val message: String
) {
    fun toReplyLine(): String = "[ACTION ${if (success) "ok" else "fail"} $actionId: $message]"
}
