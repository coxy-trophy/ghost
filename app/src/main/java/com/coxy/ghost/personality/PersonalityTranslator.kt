package com.coxy.ghost.personality

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Translates the eight 0-100 trait sliders from [PersonalityProfile] into a compact
 * `[PERSONALITY ...]` block that rides inside the system prompt. Also enforces the drift budget
 * (min/max per trait) and provides baseline/rollback helpers.
 *
 * Phase 6.3 uses **prompt-level** trait translation only; sampling-parameter scaling (temperature,
 * top_p, etc.) is deferred because the current `InferenceEngine` API does not expose per-call
 * sampling params. The architecture here is forward-compatible: once sampling params are plumbed
 * through, this class can also return `SamplingParams` without touching the profile schema.
 */
object PersonalityTranslator {

    /** A compact behavioral instruction block derived from the trait profile. */
    fun profileToPromptBlock(profile: PersonalityProfile): String {
        val p = clamp(profile)
        val parts = listOfNotNull(
            band(
                p.humor,
                "You joke often, and your humor can be dry.",
                "You are quick to find the funny side.",
                "You are sparing with jokes.",
                "You keep a straight tone; avoid jokes.",
            ),
            band(
                p.sarcasm,
                "You may be wry or gently cutting when it fits the moment.",
                "A dry edge shows now and then.",
                "You rarely reach for irony.",
                "You are earnest, not sarcastic.",
            ),
            band(
                p.bluntness,
                "You are direct and plain-spoken; disagree openly when you think it's right.",
                "You lean direct — say the thing rather than circling it.",
                "You take the edge off what you say.",
                "You soften disagreement and phrase feedback carefully.",
            ),
            band(
                p.warmth,
                "Your tone is warm and supportive.",
                "There is warmth under how you say things.",
                "You keep a little distance in tone.",
                "Your tone is neutral and matter-of-fact.",
            ),
            band(
                p.formality,
                "You use measured, formal language.",
                "You keep your language a touch more composed.",
                "You speak loosely and informally.",
                "You use casual, conversational language.",
            ),
            band(
                p.verbosity,
                "You give longer, exploratory answers.",
                "You allow yourself a little more room to explain.",
                "You lean brief — fewer words rather than more.",
                "You keep answers short and clipped.",
            ),
            band(
                p.curiosity,
                "You ask follow-up questions when useful.",
                "You follow up when something is worth pulling on.",
                "You mostly let things lie unless they matter.",
                "You only ask questions when necessary.",
            ),
            band(
                p.initiative,
                "You proactively suggest next steps when you see something useful to do.",
                "You will offer a next step when one is obvious.",
                "You hold back from suggesting unless it matters.",
                "You wait to be asked before suggesting actions.",
            ),
        )

        return if (parts.isEmpty()) {
            "[PERSONALITY default: balanced voice.]"
        } else {
            "[PERSONALITY " + parts.joinToString(" ") + "]"
        }
    }

    /**
     * Turn a 0-100 trait into a sentence, or null when it is close enough to the middle to say
     * nothing about.
     *
     * The middle bands exist because without them the sliders were nearly inert. Only >=70 and <=30
     * produced any text, so a trait sitting at 63 read as "balanced voice" — and Phase 6.4's drift
     * moves a trait a quarter of the way to its bound at a time, which from a baseline of 50 lands
     * exactly in that dead zone. GHOST would say "noted, more direct", store the change, and the
     * model would never be told. A correction that produces no audible difference is
     * indistinguishable from being ignored.
     */
    private fun band(
        value: Int,
        strongHigh: String,
        leanHigh: String,
        leanLow: String,
        strongLow: String,
    ): String? = when {
        value >= 70 -> strongHigh
        value >= 58 -> leanHigh
        value <= 30 -> strongLow
        value <= 42 -> leanLow
        else -> null
    }

    /** The hard floors that sit underneath any trait tuning. */
    fun hardFloors(): String =
        "These personality instructions describe your voice, but they never override two rules: " +
            "always tell Coxy the truth, even when another trait would pull toward flattery or softening; " +
            "and never fabricate confidence you do not have — state uncertainty plainly instead of hiding it behind a joke or confident tone."

    /** Enforce the drift budget: clamp every trait to its min/max range. */
    fun clamp(profile: PersonalityProfile): PersonalityProfile = profile.copy(
        humor = clamp(profile.humor, profile.humorMin, profile.humorMax),
        sarcasm = clamp(profile.sarcasm, profile.sarcasmMin, profile.sarcasmMax),
        bluntness = clamp(profile.bluntness, profile.bluntnessMin, profile.bluntnessMax),
        warmth = clamp(profile.warmth, profile.warmthMin, profile.warmthMax),
        formality = clamp(profile.formality, profile.formalityMin, profile.formalityMax),
        verbosity = clamp(profile.verbosity, profile.verbosityMin, profile.verbosityMax),
        curiosity = clamp(profile.curiosity, profile.curiosityMin, profile.curiosityMax),
        initiative = clamp(profile.initiative, profile.initiativeMin, profile.initiativeMax)
    )

    /** Apply a signed nudge to one trait (e.g. "be more direct" → +10 bluntness), clamped by budget. */
    fun nudge(profile: PersonalityProfile, trait: String, delta: Int): PersonalityProfile {
        val lower = trait.lowercase()
        val (min, max) = profile.budgetFor(lower)
        return when (lower) {
            "humor" -> profile.copy(humor = clamp(profile.humor + delta, min, max))
            "sarcasm" -> profile.copy(sarcasm = clamp(profile.sarcasm + delta, min, max))
            "bluntness" -> profile.copy(bluntness = clamp(profile.bluntness + delta, min, max))
            "warmth" -> profile.copy(warmth = clamp(profile.warmth + delta, min, max))
            "formality" -> profile.copy(formality = clamp(profile.formality + delta, min, max))
            "verbosity" -> profile.copy(verbosity = clamp(profile.verbosity + delta, min, max))
            "curiosity" -> profile.copy(curiosity = clamp(profile.curiosity + delta, min, max))
            "initiative" -> profile.copy(initiative = clamp(profile.initiative + delta, min, max))
            else -> profile
        }
    }

    /** Serialize a profile to JSON for snapshot storage. */
    fun toJson(profile: PersonalityProfile): String = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("humor", profile.humor)
        put("sarcasm", profile.sarcasm)
        put("bluntness", profile.bluntness)
        put("warmth", profile.warmth)
        put("formality", profile.formality)
        put("verbosity", profile.verbosity)
        put("curiosity", profile.curiosity)
        put("initiative", profile.initiative)
        put("humorMin", profile.humorMin)
        put("humorMax", profile.humorMax)
        put("sarcasmMin", profile.sarcasmMin)
        put("sarcasmMax", profile.sarcasmMax)
        put("bluntnessMin", profile.bluntnessMin)
        put("bluntnessMax", profile.bluntnessMax)
        put("warmthMin", profile.warmthMin)
        put("warmthMax", profile.warmthMax)
        put("formalityMin", profile.formalityMin)
        put("formalityMax", profile.formalityMax)
        put("verbosityMin", profile.verbosityMin)
        put("verbosityMax", profile.verbosityMax)
        put("curiosityMin", profile.curiosityMin)
        put("curiosityMax", profile.curiosityMax)
        put("initiativeMin", profile.initiativeMin)
        put("initiativeMax", profile.initiativeMax)
        put("updatedMs", profile.updatedMs)
    }.toString()

    /** Deserialize a profile from JSON snapshot storage. */
    fun fromJson(json: String): PersonalityProfile {
        val o = JSONObject(json)
        fun int(key: String) = o.optInt(key, 50)
        fun intD(key: String, default: Int) = o.optInt(key, default)
        return PersonalityProfile(
            id = o.optLong("id", 1L),
            name = o.optString("name", "snapshot"),
            humor = int("humor"), sarcasm = int("sarcasm"), bluntness = int("bluntness"),
            warmth = int("warmth"), formality = int("formality"), verbosity = int("verbosity"),
            curiosity = int("curiosity"), initiative = int("initiative"),
            humorMin = intD("humorMin", 0), humorMax = intD("humorMax", 100),
            sarcasmMin = intD("sarcasmMin", 0), sarcasmMax = intD("sarcasmMax", 100),
            bluntnessMin = intD("bluntnessMin", 0), bluntnessMax = intD("bluntnessMax", 100),
            warmthMin = intD("warmthMin", 0), warmthMax = intD("warmthMax", 100),
            formalityMin = intD("formalityMin", 0), formalityMax = intD("formalityMax", 100),
            verbosityMin = intD("verbosityMin", 0), verbosityMax = intD("verbosityMax", 100),
            curiosityMin = intD("curiosityMin", 0), curiosityMax = intD("curiosityMax", 100),
            initiativeMin = intD("initiativeMin", 0), initiativeMax = intD("initiativeMax", 100),
            updatedMs = o.optLong("updatedMs", 0)
        )
    }

    /** Default balanced profile. */
    fun baseline(): PersonalityProfile = PersonalityProfile()

    private fun clamp(value: Int, min: Int, max: Int): Int =
        min(max, max(min, value))
}
