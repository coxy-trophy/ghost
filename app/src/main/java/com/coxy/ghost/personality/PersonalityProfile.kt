package com.coxy.ghost.personality

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The active trait profile for GHOST (Phase 6.3). Eight 0-100 sliders plus a drift budget (min/max
 * allowed value) per slider. The budget is the safety net: evolution can move within the budget, but
 * never outside it. A single active profile (`id = 1`) is used for now; the schema already supports
 * multiple named profiles if needed later.
 */
@Entity(tableName = "personality_profile")
data class PersonalityProfile(
    @PrimaryKey val id: Long = 1L,
    val name: String = "default",

    val humor: Int = 50,
    val sarcasm: Int = 50,
    val bluntness: Int = 50,
    val warmth: Int = 50,
    val formality: Int = 50,
    val verbosity: Int = 50,
    val curiosity: Int = 50,
    val initiative: Int = 50,

    // Drift budget — the safety net. Defaults allow the full 0-100 range; a user (or future drift
    // mechanism) can narrow it to keep the personality from drifting somewhere unintended.
    val humorMin: Int = 0, val humorMax: Int = 100,
    val sarcasmMin: Int = 0, val sarcasmMax: Int = 100,
    val bluntnessMin: Int = 0, val bluntnessMax: Int = 100,
    val warmthMin: Int = 0, val warmthMax: Int = 100,
    val formalityMin: Int = 0, val formalityMax: Int = 100,
    val verbosityMin: Int = 0, val verbosityMax: Int = 100,
    val curiosityMin: Int = 0, val curiosityMax: Int = 100,
    val initiativeMin: Int = 0, val initiativeMax: Int = 100,

    val updatedMs: Long = 0
) {
    /** All eight traits in declaration order, useful for iteration and UI. */
    fun traits(): List<Pair<String, Int>> = listOf(
        "humor" to humor,
        "sarcasm" to sarcasm,
        "bluntness" to bluntness,
        "warmth" to warmth,
        "formality" to formality,
        "verbosity" to verbosity,
        "curiosity" to curiosity,
        "initiative" to initiative
    )

    /** Min/max budget for a named trait. */
    fun budgetFor(trait: String): Pair<Int, Int> = when (trait.lowercase()) {
        "humor" -> humorMin to humorMax
        "sarcasm" -> sarcasmMin to sarcasmMax
        "bluntness" -> bluntnessMin to bluntnessMax
        "warmth" -> warmthMin to warmthMax
        "formality" -> formalityMin to formalityMax
        "verbosity" -> verbosityMin to verbosityMax
        "curiosity" -> curiosityMin to curiosityMax
        "initiative" -> initiativeMin to initiativeMax
        else -> 0 to 100
    }
}
