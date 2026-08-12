package com.coxy.ghost.action

/**
 * Parse a model reply, execute what may run immediately, and fold the outcome back into the text
 * GHOST shows.
 *
 * Shared by the typed path ([com.coxy.ghost.MainActivity]) and the voice path
 * ([com.coxy.ghost.GhostService]), which previously held byte-identical private copies of this —
 * the same duplication that let their prompt assembly quietly drift apart.
 */
object ActionRunner {

    suspend fun run(executor: ActionExecutor, reply: String): String {
        val parsed = ActionParser.extract(reply)
        if (parsed.actions.isEmpty() && parsed.errors.isEmpty()) return reply

        val batch = executor.runImmediate(parsed.actions)
        if (batch.pending.isNotEmpty()) {
            executor.requestConfirmation(batch.pending)
        }

        val resultLines = buildList {
            addAll(parsed.errors.map { "[ACTION fail parser: $it]" })
            addAll(batch.results.map { it.toReplyLine() })
            if (batch.pending.isNotEmpty()) {
                add("[ACTION pending ${batch.pending.joinToString { ActionCatalog.describe(it.id) }}: requires confirmation]")
            }
        }

        val stripped = parsed.cleanedReply.trim()
        return if (resultLines.isEmpty()) stripped
               else stripped + "\n" + resultLines.joinToString("\n")
    }
}

/**
 * Interpret an action's `state` parameter. Anything not explicitly affirmative is off — a model
 * that emits something unexpected must not be read as "turn it on".
 */
internal fun parseActionState(value: String?): Boolean = when (value?.lowercase()?.trim()) {
    "on", "true", "1", "enable", "enabled" -> true
    else -> false
}
