package com.coxy.ghost.personality

import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a noticed correction into an actual change in who GHOST is.
 *
 * Phase 6.4's applying half, wired to [TraitSignalDetector]'s noticing half.
 */
object TraitNudger {

    /**
     * How far toward the bound one correction moves a trait.
     *
     * The roadmap asks for "a weighted moving average, not an instant jump", so a correction closes
     * a quarter of the distance to the edge of the drift budget rather than slamming the slider to
     * it. That gives the useful property for free: the first "less sarcastic" moves her a lot, and
     * the fifth barely moves her at all, because there is hardly any distance left to close. Saying
     * a thing once should change her; saying it five times should not erase her.
     */
    const val WEIGHT = 0.25

    /**
     * How far an *inferred* correction moves a trait.
     *
     * A twenty-fifth of the distance rather than a quarter, because this one was never actually
     * said. One shrug should be almost invisible; a fortnight of shrugs should be unmistakable.
     * That is the "smaller, gradual nudges" the personality doc asks for, and it is what keeps a
     * single terse evening from rewriting who she is.
     */
    const val IMPLICIT_WEIGHT = 0.04

    /**
     * Apply [signal] to [profile] and return the new profile, or null when it would change nothing.
     *
     * Null happens when the trait is already pinned against its drift budget — the correction was
     * understood and there is simply no room left to move, which is what the budget is for.
     */
    fun apply(
        profile: PersonalityProfile,
        signal: TraitSignal,
        weight: Double = WEIGHT,
    ): PersonalityProfile? {
        // Kept free of logging so it stays a pure function testable in the fast JVM tier — the
        // arithmetic here is the part worth pinning, and android.util.Log is not available there.
        val (min, max) = profile.budgetFor(signal.trait)
        val current = profile.traits().firstOrNull { it.first == signal.trait }?.second ?: return null

        val bound = if (signal.direction > 0) max else min
        val distance = bound - current
        if (distance == 0) return null

        // At least one point, so a correction is never a no-op while there is any room left.
        val step = (distance * weight).roundToInt().let {
            if (it == 0) if (distance > 0) 1 else -1 else it
        }

        val moved = PersonalityTranslator.nudge(profile, signal.trait, step)
        val after = moved.traits().firstOrNull { it.first == signal.trait }?.second ?: return null
        if (after == current) return null
        return moved
    }

    /**
     * A short, plain acknowledgement of a change she just made to herself.
     *
     * Worth saying out loud rather than adjusting silently: a personality that shifts without
     * comment is indistinguishable from one that ignored you, and this is the one kind of change
     * Coxy asked for in so many words.
     */
    fun acknowledgement(signal: TraitSignal, before: Int, after: Int): String {
        val direction = if (after > before) "more" else "less"
        val word = when (signal.trait) {
            "humor" -> "playful"
            "sarcasm" -> "sarcastic"
            "bluntness" -> "direct"
            "warmth" -> "warm"
            "formality" -> "formal"
            "verbosity" -> "detailed"
            "curiosity" -> "curious"
            "initiative" -> "forward"
            else -> signal.trait
        }
        return "(noted — $direction $word, ${abs(after - before)} points)"
    }

    /**
     * The whole path: notice, apply, persist, and put the new personality in force.
     *
     * Returns a short acknowledgement when something actually changed, null otherwise — so a
     * message that merely resembles feedback costs nothing and says nothing.
     *
     * [onPromptRefresh] receives the new `[PERSONALITY ...]` block. It exists so this stays free of
     * any dependency on the service: the caller decides what "in force" means. In practice the
     * cloud brain picks the change up on the very next request, including the reply to the
     * correction itself; the local engine's `setSystemPrompt` is once-only per model load, so it
     * catches up when the model is next loaded rather than immediately.
     */
    suspend fun applyFromText(
        repo: PersonalityRepository,
        text: String,
        onPromptRefresh: (String) -> Unit,
    ): String? {
        val signal = TraitSignalDetector.detect(text) ?: return null
        val profile = runCatching { repo.activeProfile() }.getOrNull() ?: return null
        val before = profile.traits().firstOrNull { it.first == signal.trait }?.second ?: return null

        val moved = apply(profile, signal) ?: run {
            Log.i(TAG, "trait nudge: ${signal.trait} already at its budget edge — nothing to move")
            return null
        }
        val after = moved.traits().first { it.first == signal.trait }.second
        val (min, max) = profile.budgetFor(signal.trait)
        Log.i(
            TAG,
            "trait nudge: ${signal.trait} $before -> $after " +
                "(budget $min..$max, heard \"${signal.matched}\")",
        )

        runCatching { repo.save(moved, reason = "explicit_signal:${signal.trait}") }
            .onFailure {
                Log.w(TAG, "trait nudge could not be saved", it)
                return null
            }
        onPromptRefresh(PersonalityTranslator.profileToPromptBlock(moved))
        return acknowledgement(signal, before, after)
    }

    /**
     * Apply an inferred correction. Returns true when something moved.
     *
     * Silent by design — nothing is returned to say into the chat. An explicit correction is a
     * request and deserves an answer; an inference is a guess, and a guess that announces itself
     * ("I noticed you seemed bored") is both presumptuous and wrong often enough to be irritating.
     * It shows up in the logs and in the personality dialog, which is where a slow drift belongs.
     */
    suspend fun applyImplicit(
        repo: PersonalityRepository,
        signal: TraitSignal,
        onPromptRefresh: (String) -> Unit,
    ): Boolean {
        val profile = runCatching { repo.activeProfile() }.getOrNull() ?: return false
        val before = profile.traits().firstOrNull { it.first == signal.trait }?.second ?: return false

        val moved = apply(profile, signal, IMPLICIT_WEIGHT) ?: return false
        val after = moved.traits().first { it.first == signal.trait }.second
        Log.i(TAG, "implicit drift: ${signal.trait} $before -> $after (${signal.matched})")

        runCatching { repo.save(moved, reason = "implicit_signal:${signal.trait}") }
            .onFailure {
                Log.w(TAG, "implicit drift could not be saved", it)
                return false
            }
        onPromptRefresh(PersonalityTranslator.profileToPromptBlock(moved))
        return true
    }

    private const val TAG = "TraitNudger"
}
