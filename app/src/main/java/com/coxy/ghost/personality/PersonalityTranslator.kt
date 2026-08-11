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
        val parts = mutableListOf<String>()
        val p = clamp(profile)

        // Humor
        when {
            p.humor >= 70 -> parts += "You joke often, and your humor can be dry."
            p.humor <= 30 -> parts += "You keep a straight tone; avoid jokes."
        }
        // Sarcasm
        when {
            p.sarcasm >= 70 -> parts += "You may be wry or gently cutting when it fits the moment."
            p.sarcasm <= 30 -> parts += "You are earnest, not sarcastic."
        }
        // Bluntness
        when {
            p.bluntness >= 70 -> parts += "You are direct and plain-spoken; disagree openly when you think it's right."
            p.bluntness <= 30 -> parts += "You soften disagreement and phrase feedback carefully."
        }
        // Warmth
        when {
            p.warmth >= 70 -> parts += "Your tone is warm and supportive."
            p.warmth <= 30 -> parts += "Your tone is neutral and matter-of-fact."
        }
        // Formality
        when {
            p.formality >= 70 -> parts += "You use measured, formal language."
            p.formality <= 30 -> parts += "You use casual, conversational language."
        }
        // Verbosity
        when {
            p.verbosity >= 70 -> parts += "You give longer, exploratory answers."
            p.verbosity <= 30 -> parts += "You keep answers short and clipped."
        }
        // Curiosity
        when {
            p.curiosity >= 70 -> parts += "You ask follow-up questions when useful."
            p.curiosity <= 30 -> parts += "You only ask questions when necessary."
        }
        // Initiative
        when {
            p.initiative >= 70 -> parts += "You proactively suggest next steps when you see something useful to do."
            p.initiative <= 30 -> parts += "You wait to be asked before suggesting actions."
        }

        return if (parts.isEmpty()) {
            "[PERSONALITY default: balanced voice.]"
        } else {
            "[PERSONALITY " + parts.joinToString(" ") + "]"
        }
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
