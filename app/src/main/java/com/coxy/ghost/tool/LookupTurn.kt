package com.coxy.ghost.tool

import android.content.Context
import android.util.Log
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.action.Action
import com.coxy.ghost.action.ActionParser
import com.coxy.ghost.cloud.CloudKeyStorage
import com.coxy.ghost.net.LanBlocks
import com.coxy.ghost.net.LanScanner
import com.coxy.ghost.web.WebClient
import com.coxy.ghost.web.WebResults

/**
 * The extra passes: every action whose result is *input* rather than output.
 *
 * Most of GHOST's actions are terminal. She says she is turning the torch on, the executor does it,
 * and `[ACTION ok toggle_torch: torch on]` is appended to what Coxy reads — the result is something
 * he reads, and the turn ends.
 *
 * Looking something up inverts that. Nobody wants to read "[ACTION ok web_search: 5 results]" or a
 * raw list of forty IP addresses; they want her to have read them. So a turn that looks something
 * up runs inference again: she asks, the lookup happens, what came back folds into the same prompt,
 * and the newest reply is the one shown. Earlier replies are discarded — showing them would mean
 * Coxy reads "let me check" and then watches it be replaced.
 *
 * Search, fetch and the network scan are all this shape, which is why they share one loop rather
 * than each getting a mechanism. It also gives the useful chains for free: search then read the one
 * result that mattered; or scan the network, find the television, then act on it.
 *
 * This object holds only the parts the typed and voice paths must agree on — what was asked for,
 * making the call, and building the block. Each path keeps its own streaming, because that is
 * genuinely different between a RecyclerView bubble and a spoken reply. Sharing the decision is the
 * point: prompt assembly on these two paths has drifted apart before.
 */
object LookupTurn {

    private const val TAG = "LookupTurn"

    const val SEARCH_ID = "web_search"
    const val FETCH_ID = "web_fetch"
    const val LAN_ID = "scan_network"

    /**
     * Lookups allowed per turn.
     *
     * Two, so a chain fits and nothing longer does. Each step costs a round trip and a full
     * generation, and on the local 1.5B at roughly five tokens a second a third would make one
     * question take minutes. It is also the loop guard: a model that keeps asking runs out of steps
     * rather than out of battery.
     */
    const val MAX_STEPS = 2

    /** What a reply is asking to look up. */
    sealed interface Request {
        data class Search(val query: String) : Request
        data class Fetch(val url: String) : Request
        /** [force] re-scans instead of taking the cached result; see [LanScanner]. */
        data class Lan(val force: Boolean = false) : Request
    }

    /**
     * The lookup a reply is asking for, or null if it is not asking for one.
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
                LAN_ID -> Request.Lan(
                    force = action.params["refresh"]?.lowercase()?.trim() in
                        setOf("true", "yes", "1", "again")
                )
                else -> null
            }
        }

    /** Convenience for callers holding raw reply text rather than parsed actions. */
    fun requestIn(reply: String): Request? = requestIn(ActionParser.extract(reply).actions)

    /**
     * Do the lookup and render the block for the next pass.
     *
     * Never returns null: a refused or failed lookup still produces a block saying so. Returning
     * nothing would leave GHOST answering from memory in a voice that sounds like she checked,
     * which is the exact failure the bedrock rule about not dressing a guess up as knowledge exists
     * to prevent.
     */
    suspend fun blockFor(context: Context, request: Request): String = when (request) {
        is Request.Lan -> LanBlocks.toPromptBlock(LanScanner.scan(context, force = request.force))

        is Request.Search, is Request.Fetch -> {
            val subject = when (request) {
                is Request.Search -> request.query
                is Request.Fetch -> request.url
                else -> ""
            }
            if (!GhostPrefs.isWebSearchEnabled(context)) {
                Log.i(TAG, "web lookup suppressed: web access is switched off")
                "[WEB \"$subject\" was not looked up: web access is switched off. " +
                    "Say you cannot reach the web right now.]"
            } else {
                val key = CloudKeyStorage.getKey(context).orEmpty()
                val outcome = when (request) {
                    is Request.Search -> WebClient.search(key, request.query)
                    is Request.Fetch -> WebClient.fetch(key, request.url)
                    else -> WebClient.Outcome.Failed(subject, "unreachable")
                }
                WebResults.toPromptBlock(outcome)
            }
        }
    }

    /**
     * Fold a block into the prompt for the next pass.
     *
     * Prepend lookup results so the original user message remains an intact suffix. Cloud privacy
     * filtering receives that message separately and filters only the metadata before it.
     * Chained steps stack newest-first.
     */
    fun withBlock(prompt: String, block: String): String = "$block\n$prompt"

    /** What Coxy sees while a lookup is in flight. */
    fun placeholderFor(context: Context, request: Request): String = context.getString(
        when (request) {
            is Request.Search -> com.coxy.ghost.R.string.ghost_searching
            is Request.Fetch -> com.coxy.ghost.R.string.ghost_reading
            is Request.Lan -> com.coxy.ghost.R.string.ghost_scanning
        }
    )
}
