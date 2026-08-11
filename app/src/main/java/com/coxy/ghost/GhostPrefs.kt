package com.coxy.ghost

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coxy.ghost.cloud.CloudPromptFilter
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

    // Phase 8.1 mic kill-switch: the continuous on-device STT restarts every few hundred ms and
    // makes an audible on/off blip on some HALs. Let the user turn the mic off entirely.
    private val KEY_MIC_ENABLED = booleanPreferencesKey("mic_enabled")

    fun modelPathFlow(ctx: Context): Flow<String?> =
        ctx.ghostDataStore.data.map { it[KEY_MODEL_PATH] }

    fun enabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_ENABLED] ?: false }

    fun factExtractionEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_FACT_EXTRACTION_ENABLED] ?: true }

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

    /** Phase 8.1: mic enabled toggle (default true so existing behaviour is preserved). */
    fun micEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_MIC_ENABLED] ?: true }

    suspend fun isMicEnabled(ctx: Context): Boolean = micEnabledFlow(ctx).first()

    suspend fun setMicEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_MIC_ENABLED] = enabled }
    }
}