package com.coxy.ghost

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coxy.ghost.cloud.CloudPromptFilter
import com.coxy.ghost.perception.wakeword.WakeTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Single DataStore instance for the GHOST app. */
private val Context.ghostDataStore by preferencesDataStore(name = "ghost_prefs")

/**
 * Persists the two pieces of state GHOST needs to restart itself after a kill or reboot:
 * the on-device model path and whether the daemon should be running (kill-switch flag).
 */
object GhostPrefs {
    private val KEY_MODEL_PATH = stringPreferencesKey("model_path")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_FACT_EXTRACTION_ENABLED = booleanPreferencesKey("fact_extraction_enabled")

    // Phase 8 cloud brain settings.
    private val KEY_CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
    private val KEY_CLOUD_PRIVACY_MODE = stringPreferencesKey("cloud_privacy_mode")
    private val KEY_CLOUD_LOGGING_ENABLED = booleanPreferencesKey("cloud_logging_enabled")

    /**
     * The "leave the mic on" latch for Live mode.
     *
     * Deliberately a **new key**, not a new default on `mic_enabled`. That key meant "ambient
     * wake-word listening is enabled" and is `true` on this device from the always-on era; a
     * default only applies when a key is absent, so reusing it would have resurrected the exact
     * behaviour being removed — the mic came up OPEN on the first run after the redesign and
     * immediately dispatched a fragment of room noise as a command. A renamed key starts unset, so
     * every install begins with nothing listening and the latch is something Coxy turns on.
     */
    private val KEY_MIC_OPEN_LATCH = booleanPreferencesKey("mic_open_latch")

    // Phase 5.2 wake-word tuning. These are runtime-tunable on purpose: closing the live gate means
    // sweeping the threshold against a real human voice, and baking it into a constant made every
    // step of that sweep a rebuild + priv-app deploy + reboot (~20 min, and a deploy can wipe app
    // data — see GHOST.md §8). ACTION_SET_KWS_TUNING writes these from a root shell in a second.
    private val KEY_KWS_THRESHOLD = floatPreferencesKey("kws_threshold")
    private val KEY_KWS_FRAMES = intPreferencesKey("kws_frames")

    fun modelPathFlow(ctx: Context): Flow<String?> =
        ctx.ghostDataStore.data.map { it[KEY_MODEL_PATH] }

    fun enabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_ENABLED] ?: false }

    /**
     * Default flipped to `false` on 2026-08-13.
     *
     * `FactExtractor` drives the same single engine context as normal chat, so its
     * `[FACT_EXTRACTION]` meta-turns land in the model's own history and it starts replying to
     * ordinary messages with `FACT: GHOST did X`. That is what made GHOST address Coxy as "Ghost"
     * — see [com.coxy.ghost.memory.MemoryStore.looksLikeExtractionOutput]. Until extraction can run
     * on a context of its own (a `:lib` change) or on the cloud model, the feature costs more than
     * it returns.
     */
    fun factExtractionEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_FACT_EXTRACTION_ENABLED] ?: false }

    suspend fun setModelPath(ctx: Context, path: String?) {
        ctx.ghostDataStore.edit { p ->
            if (path == null) p.remove(KEY_MODEL_PATH) else p[KEY_MODEL_PATH] = path
        }
    }

    suspend fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_ENABLED] = enabled }
    }

    suspend fun setFactExtractionEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_FACT_EXTRACTION_ENABLED] = enabled }
    }

    suspend fun getModelPath(ctx: Context): String? = modelPathFlow(ctx).first()

    suspend fun isEnabled(ctx: Context): Boolean = enabledFlow(ctx).first()

    suspend fun isFactExtractionEnabled(ctx: Context): Boolean =
        factExtractionEnabledFlow(ctx).first()

    /** Phase 8: snapshot of cloud-brain runtime settings. */
    fun cloudSettingsFlow(ctx: Context): Flow<com.coxy.ghost.cloud.CloudSettings> =
        ctx.ghostDataStore.data.map { prefs ->
            com.coxy.ghost.cloud.CloudSettings(
                enabled = prefs[KEY_CLOUD_ENABLED] ?: true,
                privacyMode = CloudPromptFilter.fromString(prefs[KEY_CLOUD_PRIVACY_MODE] ?: "full"),
                loggingEnabled = prefs[KEY_CLOUD_LOGGING_ENABLED] ?: true
            )
        }

    suspend fun cloudSettings(ctx: Context): com.coxy.ghost.cloud.CloudSettings =
        cloudSettingsFlow(ctx).first()

    suspend fun setCloudEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_CLOUD_ENABLED] = enabled }
    }

    suspend fun setCloudPrivacyMode(ctx: Context, mode: CloudPromptFilter.Mode) {
        ctx.ghostDataStore.edit { p -> p[KEY_CLOUD_PRIVACY_MODE] = mode.name.lowercase() }
    }

    suspend fun setCloudLoggingEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_CLOUD_LOGGING_ENABLED] = enabled }
    }

    /**
     * Whether the mic is latched open for Live mode. Default `false`: nothing comes back from a
     * reboot already listening — the mic opens because Coxy opened it.
     */
    fun micEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_MIC_OPEN_LATCH] ?: false }

    suspend fun isMicEnabled(ctx: Context): Boolean = micEnabledFlow(ctx).first()

    suspend fun setMicEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_MIC_OPEN_LATCH] = enabled }
    }

    /**
     * Phase 5.2: wake-word trigger tuning, defaulting to [WakeTrigger]'s own constants so an unset
     * store behaves exactly as the hardcoded values did.
     *
     * Both are clamped on read, not just on write: these are reachable from a shell broadcast, and a
     * threshold of 0 would turn every frame into a trigger — a mic that hands itself to the
     * recognizer 20 times a second.
     */
    suspend fun kwsThreshold(ctx: Context): Float =
        ctx.ghostDataStore.data.first()[KEY_KWS_THRESHOLD]
            ?.coerceIn(0.05f, 0.999f)
            ?: WakeTrigger.DEFAULT_THRESHOLD

    suspend fun kwsFrames(ctx: Context): Int =
        ctx.ghostDataStore.data.first()[KEY_KWS_FRAMES]
            ?.coerceIn(1, 40)
            ?: WakeTrigger.DEFAULT_FRAMES

    suspend fun setKwsTuning(ctx: Context, threshold: Float?, frames: Int?) {
        ctx.ghostDataStore.edit { p ->
            if (threshold != null) p[KEY_KWS_THRESHOLD] = threshold.coerceIn(0.05f, 0.999f)
            if (frames != null) p[KEY_KWS_FRAMES] = frames.coerceIn(1, 40)
        }
    }
}