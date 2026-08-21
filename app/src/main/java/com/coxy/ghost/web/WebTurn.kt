package com.coxy.ghost.web

import android.content.Context
import android.util.Log
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.action.Action
import com.coxy.ghost.action.ActionParser
import com.coxy.ghost.cloud.CloudKeyStorage

/**
 * The extra passes.
 *
 * Every other action GHOST has is terminal: she says she is turning the torch on, the executor does
 * it, and `[ACTION ok toggle_torch: torch on]` is appended to what Coxy reads. The result is
 * *output*.
 *
 * The web inverts that. Results are *input* — nobody wants to read "[ACTION ok web_search: 5
 * results]", they want her to have read them. So a turn that reaches the web runs inference again:
 * she asks, the call happens, what came back folds into the same prompt, and the newest reply is
 * the one shown. Earlier replies are discarded; showing them would mean Coxy reads "let me check"
 * and then watches it be replaced.
 *
 * Search and fetch are the same shape, which is why they share this loop rather than each getting
 * their own branch. It also gives the useful chain for free: search, see which result actually
 * holds the answer, read that page, then answer — three inference passes and two round trips, all
 * inside one turn.
 *
 * This object holds only the parts the typed and voice paths must agree on: what was asked for,
 * making the call, and building the block. Each path keeps its own streaming, because that is
 * genuinely different between a RecyclerView bubble and a spoken reply. Sharing the decision is the
 * point — prompt assembly on these two paths has drifted apart before.
 */
object WebTurn {

    private const val TAG = "WebTurn"

    const val SEARCH_ID = "web_search"
    const val FETCH_ID = "web_fetch"

    /**
     * Round trips allowed per turn.
     *
     * Two, so the search-then-read chain fits and nothing longer does. Each step costs a network
     * call plus a full generation, and on the local 1.5B at roughly 5 tokens a second a third would
     * make a single question take minutes. It is also the loop guard: a model that keeps asking
     * runs out of steps rather than out of battery.
     */
    const val MAX_STEPS = 2

    /** What a reply is asking the web for. */
    sealed interface Request {
        data class Search(val query: String) : Request
        data class Fetch(val url: String) : Request
    }

    /**
     * The web request a reply is making, or null if it is not making one.
     *
     * Only the first is honoured. A model that emits three searches in one reply gets one, because
     * each costs a round trip and a full generation.
     */
    fun requestIn(actions: List<Action>): Request? =
        actions.firstNotNullOfOrNull { action ->
            when (action.id) {
                SEARCH_ID -> action.params["query"]?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { Request.Search(it) }
                FETCH_ID -> action.params["url"]?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { Request.Fetch(it) }
                else -> null
            }
        }

    /** Convenience for callers holding raw reply text rather than parsed actions. */
    fun requestIn(reply: String): Request? = requestIn(ActionParser.extract(reply).actions)

    /**
     * Make the call and render the block for the next pass.
     *
     * Never returns null: a refused or failed call still produces a block saying so. Returning
     * nothing would leave GHOST answering from memory in a voice that sounds like she looked it up,
     * which is the exact failure the bedrock rule about not dressing a guess up as knowledge exists
     * to prevent.
     */
    suspend fun blockFor(context: Context, request: Request): String {
        val subject = when (request) {
            is Request.Search -> request.query
            is Request.Fetch -> request.url
        }
        if (!GhostPrefs.isWebSearchEnabled(context)) {
            Log.i(TAG, "web step suppressed: web access is switched off")
            return "[WEB \"$subject\" was not looked up: web access is switched off. " +
                "Say you cannot reach the web right now.]"
        }
        val key = CloudKeyStorage.getKey(context).orEmpty()
        val outcome = when (request) {
            is Request.Search -> WebClient.search(key, request.query)
            is Request.Fetch -> WebClient.fetch(key, request.url)
        }
        return WebResults.toPromptBlock(outcome)
    }

    /**
     * Fold a block into the prompt for the next pass.
     *
     * Prepended, not appended, so the user's message stays the final line. `[CONTEXT]` and
     * `[MEMORY]` sit in front of the text for the same reason, and
     * [com.coxy.ghost.cloud.CloudPromptFilter]'s CHAT_ONLY mode depends on it: it reconstructs the
     * turn as "the last non-blank line", so a block after the text would take the message's place.
     * Chained steps therefore stack newest-first, which is also the order they are most useful in.
     */
    fun withBlock(prompt: String, block: String): String = "$block\n$prompt"

    /** What Coxy sees while a step is in flight. */
    fun placeholderFor(context: Context, request: Request): String = context.getString(
        when (request) {
            is Request.Search -> com.coxy.ghost.R.string.ghost_searching
            is Request.Fetch -> com.coxy.ghost.R.string.ghost_reading
        }
    )
}
