package com.coxy.ghost.web

import android.content.Context
import android.util.Log
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.action.Action
import com.coxy.ghost.action.ActionParser
import com.coxy.ghost.cloud.CloudKeyStorage

/**
 * The second pass.
 *
 * Every other action GHOST has is terminal: she says she is turning the torch on, the executor does
 * it, and `[ACTION ok toggle_torch: torch on]` is appended to what Coxy reads. The result is
 * *output*.
 *
 * Search inverts that. Results are *input* — nobody wants to read "[ACTION ok web_search: 5
 * results]", they want her to have read them. So a turn containing a search runs inference twice:
 * she asks, the search runs, the results are folded into the same prompt, and the second reply is
 * the one that gets shown. The first reply is discarded; it was a request for information, not an
 * answer.
 *
 * This object holds only the parts both the typed and the voice path must agree on — deciding a
 * search was asked for, running it, and building the block. Each path keeps its own streaming,
 * because that is genuinely different between a RecyclerView bubble and a spoken reply. Sharing the
 * decision is the point: prompt assembly on these two paths has drifted apart before.
 */
object WebSearchTurn {

    private const val TAG = "WebSearchTurn"

    const val ACTION_ID = "web_search"

    /**
     * The query a reply is asking to search for, or null if it is not asking.
     *
     * Only the first is honoured. A model that emits three searches in one reply gets one, because
     * each costs a network round trip and a full second inference, and three would be a way for a
     * single turn to take a minute on a 1.5B.
     */
    fun queryIn(actions: List<Action>): String? =
        actions.firstOrNull { it.id == ACTION_ID }
            ?.params?.get("query")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Convenience for callers holding raw reply text rather than parsed actions. */
    fun queryIn(reply: String): String? = queryIn(ActionParser.extract(reply).actions)

    /**
     * Run the search and render the block for the second-pass prompt.
     *
     * Never returns null once a query exists: a refused or failed search still produces a block
     * saying so. Returning nothing would leave GHOST answering from memory in a voice that sounds
     * like she looked it up, which is the exact failure the bedrock rule about not dressing a guess
     * up as knowledge exists to prevent.
     */
    suspend fun blockFor(context: Context, query: String): String {
        if (!GhostPrefs.isWebSearchEnabled(context)) {
            Log.i(TAG, "search suppressed: web search is switched off")
            return "[WEB search for \"$query\" was not run: web search is switched off. " +
                "Say you cannot look things up right now.]"
        }
        val key = CloudKeyStorage.getKey(context)
        val outcome = WebSearchClient.search(key.orEmpty(), query)
        return WebResults.toPromptBlock(outcome)
    }

    /**
     * Fold the block into the prompt that produced [basePrompt]'s reply.
     *
     * Prepended, not appended, so the user's message stays the final line. `[CONTEXT]` and
     * `[MEMORY]` sit in front of the text for the same reason, and
     * [com.coxy.ghost.cloud.CloudPromptFilter]'s CHAT_ONLY mode depends on it: it reconstructs the
     * turn as "the last non-blank line", so a block after the text would take the message's place.
     * The block is also a single line by construction, which is what keeps the other modes from
     * shredding it — see [WebResults].
     */
    fun secondPassPrompt(basePrompt: String, webBlock: String): String = "$webBlock\n$basePrompt"
}
