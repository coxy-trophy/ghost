package com.coxy.ghost.initiative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.coxy.ghost.EngineGate
import com.coxy.ghost.GhostPrefs
import com.coxy.ghost.MainActivity
import com.coxy.ghost.R
import com.coxy.ghost.cloud.CloudRouter
import com.coxy.ghost.memory.MemoryStore
import com.coxy.ghost.memory.toMemoryBlock
import com.coxy.ghost.perception.ContextState
import com.coxy.ghost.perception.ContextStore
import com.coxy.ghost.perception.GhostPrompts
import com.coxy.ghost.perception.Transition
import com.coxy.ghost.perception.TransitionDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The path by which GHOST can say something without being asked.
 *
 * Until this existed, every inference in the app began with something Coxy typed or spoke. The
 * perception layer fed a `[CONTEXT]` block onto prompts *he* initiated and could never act on its
 * own — which meant the premise in GHOST.md §1, "stop being something that has to be opened and
 * start being something that's just there", was not actually built. She perceived continuously and
 * spoke only when spoken to.
 *
 * The loop is: sample the world on a slow tick, ask [TransitionDetector] what changed, ask
 * [InitiativePolicy] whether that is worth interrupting someone over, and only then spend an
 * inference on it. Three properties are load-bearing:
 *
 *  - **Cloud only.** The prompt is synthetic, and synthetic prompts must not enter the local
 *    engine's single conversation context — see the note on [CloudRouter.local]. Offline, she stays
 *    quiet rather than talking into the context that v0.11.4 had to be repaired.
 *  - **The model keeps a veto.** It is asked to reply `NOTHING` when an event does not warrant a
 *    remark, and a veto costs no quota, so the rate limit is spent on things actually said.
 *  - **Only her side is remembered.** The synthetic prompt is never stored as a turn. Storing it
 *    would teach the model that Coxy says `[UNPROMPTED] ...`, which is precisely the mechanism that
 *    collapsed the role structure when fact extraction wrote its meta-turns into memory.
 */
class InitiativeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val router: CloudRouter,
    private val memoryStore: MemoryStore,
    /** Current value of the `initiative` personality trait, 0-100. */
    private val initiativeTrait: suspend () -> Int,
    /** The system prompt in force, so an unprompted turn is still spoken by the same GHOST. */
    private val systemPrompt: () -> String,
) {
    private var job: Job? = null

    /** The world as it looked at the previous tick, and when that tick was. */
    private var previous: ContextState = ContextState()
    private var lastEvaluatedMs: Long = 0L

    fun start() {
        if (job != null) return
        previous = ContextStore.state.value
        lastEvaluatedMs = System.currentTimeMillis()
        job = scope.launch {
            Log.i(TAG, "initiative loop started (tick ${CHECK_INTERVAL_MS / 60_000}m)")
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                runCatching { evaluate() }
                    .onFailure { Log.w(TAG, "initiative check failed", it) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One pass: what changed, may she speak about it, and if so what does she say.
     *
     * [previous] and [lastEvaluatedMs] advance on every tick regardless of the outcome. If they only
     * advanced when she spoke, a held transition would be re-detected on every tick until the
     * cooldown expired and then fire late, describing something that happened an hour ago.
     */
    private suspend fun evaluate() {
        val now = System.currentTimeMillis()
        val current = ContextStore.state.value
        val transitions = TransitionDetector.detect(previous, current, now, lastEvaluatedMs)

        val enabled = GhostPrefs.isInitiativeEnabled(context)
        val history = GhostPrefs.initiativeHistory(context, today())
        val decision = InitiativePolicy.decide(
            transitions = transitions,
            state = current,
            initiativeTrait = initiativeTrait(),
            enabled = enabled,
            now = now,
            localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            lastSpokeAtMs = history.lastSpokeAtMs,
            spokenToday = history.spokenToday,
            lastSpokeByKind = history.lastSpokeByKind,
        )

        previous = current
        lastEvaluatedMs = now

        when (decision) {
            is InitiativeDecision.Hold -> {
                // Only worth a line when something actually happened and was suppressed; otherwise
                // this would log "nothing changed" every five minutes forever.
                if (transitions.isNotEmpty()) {
                    Log.i(TAG, "held (${transitions.joinToString { it.kind.name }}): ${decision.reason}")
                }
            }
            is InitiativeDecision.Speak -> speak(decision.transition, current, now)
        }
    }

    /** Generate and deliver one unprompted remark, or discover the model had nothing to say. */
    private suspend fun speak(transition: Transition, state: ContextState, now: Long) {
        // A user query always outranks this. Skipping rather than queueing is deliberate: by the
        // time the engine frees up, the observation is stale.
        if (!EngineGate.tryAcquire()) {
            Log.i(TAG, "held (${transition.kind}): busy answering him")
            return
        }
        try {
            val memoryBlock = runCatching { memoryStore.recall(transition.description).toMemoryBlock() }
                .getOrDefault("")
            val prompt = buildPrompt(transition, state, memoryBlock)

            var reply = ""
            router.route(prompt, systemPrompt(), PREDICT_LENGTH, allowLocalFallback = false)
                .catch { Log.w(TAG, "unprompted generation failed", it) }
                .collect { reply = it }

            val text = reply.trim()
            if (text.isBlank()) {
                // Either the model declined or there was no cloud. Neither spends the quota.
                Log.i(TAG, "held (${transition.kind}): no cloud reply available")
                return
            }
            if (isDeclined(text)) {
                Log.i(TAG, "held (${transition.kind}): she judged it not worth saying")
                return
            }

            deliver(text)
            memoryStore.remember(role = "assistant", content = text, source = SOURCE_UNPROMPTED)
            GhostPrefs.recordInitiativeSpoken(context, transition.kind, now, today())
            Log.i(TAG, "spoke unprompted (${transition.kind}): $text")
        } finally {
            EngineGate.release()
        }
    }

    /**
     * The synthetic turn.
     *
     * `[UNPROMPTED]` is named in the system prompt the same way `[CONTEXT]` and `[MEMORY]` are, so
     * the model is not being tricked into thinking Coxy said this. The explicit `NOTHING` escape is
     * the model's veto, and mirrors the `NONE` that fact extraction already uses.
     */
    private fun buildPrompt(transition: Transition, state: ContextState, memoryBlock: String): String {
        val instruction = "[UNPROMPTED] No one asked you anything. You noticed: ${transition.description}. " +
            "If that is worth a word, say one short thing to him about it — one or two sentences, " +
            "the way someone in the room would. If it is not worth saying, reply with exactly NOTHING."
        return GhostPrompts.buildUserPrompt(state, memoryBlock, instruction)
    }

    /** Accepts a bare NOTHING, and the small variations a small model produces around it. */
    private fun isDeclined(text: String): Boolean {
        val stripped = text.trim().trim('.', '!', '"', '\'', ' ')
        return stripped.equals("NOTHING", ignoreCase = true) ||
            stripped.equals("NOTHING.", ignoreCase = true)
    }

    /**
     * Put the remark where it will be seen: a notification, since the app is usually closed, and
     * the shared context store so the Chat screen renders it like any other thing she said.
     */
    private fun deliver(text: String) {
        // Its own channel, not voiceResponse — that one belongs to the bubble the current
        // voiceCommandSeq opened, and writing here would have overwritten the last spoken reply.
        ContextStore.update { it.copy(unpromptedText = text, unpromptedSeq = it.unpromptedSeq + 1) }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GHOST speaks", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Things GHOST says without being asked" }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GHOST")
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
            .onFailure { Log.w(TAG, "could not post the notification", it) }
    }

    /** Local calendar day, so the daily cap rolls over at local midnight rather than at UTC. */
    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())

    companion object {
        private const val TAG = "Initiative"

        /**
         * Slow on purpose. Every tick is three DataStore reads and a state comparison — cheap — but
         * the events this watches for happen on the scale of a life, not a sensor.
         */
        const val CHECK_INTERVAL_MS = 5 * 60_000L

        /** Short. She is remarking, not answering. */
        const val PREDICT_LENGTH = 120

        /** Marks the turn in the memory DB so unprompted remarks stay distinguishable later. */
        const val SOURCE_UNPROMPTED = "unprompted"

        private const val CHANNEL_ID = "ghost_unprompted"
        private const val NOTIFICATION_ID = 4711
    }
}
