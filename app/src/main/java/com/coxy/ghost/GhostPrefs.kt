package com.coxy.ghost

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coxy.ghost.cloud.CloudPromptFilter
import com.coxy.ghost.perception.HomePoint
import com.coxy.ghost.perception.TransitionKind
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
     * Whether GHOST may search the web.
     *
     * Separate from the cloud toggle on purpose. The cloud settings govern what reaches *Ollama's
     * chat model*; a search query reaches a search index as well, which the existing privacy modes
     * say nothing about. Defaults on, because it only fires when she is explicitly asked to look
     * something up.
     */
    private val KEY_WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")

    /**
     * Coxy's names for devices on the home network, as a JSON object keyed by `mac:`/`ip:`.
     *
     * A JSON blob rather than one key per device because DataStore Preferences has no map type and
     * the alternative is inventing a key-per-MAC scheme that nothing can enumerate.
     */
    private val KEY_DEVICE_NAMES = stringPreferencesKey("device_names")

    /** Addresses of televisions that have accepted GHOST's remote-control certificate. */
    private val KEY_PAIRED_TVS = stringPreferencesKey("paired_tvs")

    /** Whether GHOST speaks her replies aloud in Live mode. */
    private val KEY_VOICE_ENABLED = booleanPreferencesKey("voice_enabled")

    /**
     * Which one-off memory cleanups have already run.
     *
     * A number rather than a flag so a later cleanup can be added without re-running the earlier
     * ones over a database that no longer needs them.
     */
    private val KEY_MEMORY_CLEANUP = androidx.datastore.preferences.core.intPreferencesKey("memory_cleanup_version")

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

    // Phase 4: the home point behind location=home/away. Only these three values are stored;
    // ContextState never carries coordinates, so nothing that leaves the device can reconstruct
    // them — see HomePoint's KDoc.
    private val KEY_HOME_LAT = doublePreferencesKey("home_lat")
    private val KEY_HOME_LON = doublePreferencesKey("home_lon")
    private val KEY_HOME_RADIUS_M = floatPreferencesKey("home_radius_m")

    // Speaking first. Default OFF, and deliberately so: this app's history with ambient behaviour
    // that defaults on is the always-listening mic, which woke on room noise every 30 seconds. A
    // feature that can interrupt someone starts switched off and gets turned on on purpose.
    private val KEY_INITIATIVE_ENABLED = booleanPreferencesKey("initiative_enabled")
    private val KEY_LAST_PROACTIVE_MS = longPreferencesKey("initiative_last_ms")
    private val KEY_PROACTIVE_DAY = stringPreferencesKey("initiative_day")
    private val KEY_PROACTIVE_COUNT = intPreferencesKey("initiative_count_today")

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

    fun webSearchEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.ghostDataStore.data.map { it[KEY_WEB_SEARCH_ENABLED] ?: true }

    suspend fun isWebSearchEnabled(ctx: Context): Boolean = webSearchEnabledFlow(ctx).first()

    suspend fun setWebSearchEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_WEB_SEARCH_ENABLED] = enabled }
    }

    suspend fun getDeviceNames(ctx: Context): String? =
        ctx.ghostDataStore.data.map { it[KEY_DEVICE_NAMES] }.first()

    suspend fun setDeviceNames(ctx: Context, json: String) {
        ctx.ghostDataStore.edit { p -> p[KEY_DEVICE_NAMES] = json }
    }

    suspend fun getPairedTvs(ctx: Context): String? =
        ctx.ghostDataStore.data.map { it[KEY_PAIRED_TVS] }.first()

    suspend fun setPairedTvs(ctx: Context, json: String) {
        ctx.ghostDataStore.edit { p -> p[KEY_PAIRED_TVS] = json }
    }

    suspend fun isVoiceEnabled(ctx: Context): Boolean =
        ctx.ghostDataStore.data.map { it[KEY_VOICE_ENABLED] ?: true }.first()

    suspend fun setVoiceEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_VOICE_ENABLED] = enabled }
    }

    suspend fun memoryCleanupVersion(ctx: Context): Int =
        ctx.ghostDataStore.data.map { it[KEY_MEMORY_CLEANUP] ?: 0 }.first()

    suspend fun setMemoryCleanupVersion(ctx: Context, version: Int) {
        ctx.ghostDataStore.edit { p -> p[KEY_MEMORY_CLEANUP] = version }
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

    /**
     * The stored home point, or null when Coxy has never set one — in which case `location=` stays
     * `unknown`, exactly as it behaved before this existed.
     *
     * Both halves must be present to count. A store holding a latitude and no longitude is not a
     * place, and silently treating the missing half as 0.0 would put home in the Gulf of Guinea.
     */
    suspend fun homePoint(ctx: Context): HomePoint? {
        val prefs = ctx.ghostDataStore.data.first()
        val lat = prefs[KEY_HOME_LAT] ?: return null
        val lon = prefs[KEY_HOME_LON] ?: return null
        val radius = (prefs[KEY_HOME_RADIUS_M] ?: HomePoint.DEFAULT_RADIUS_M)
            .coerceIn(HomePoint.MIN_RADIUS_M, HomePoint.MAX_RADIUS_M)
        return HomePoint(lat, lon, radius)
    }

    /**
     * Store or clear the home point. Reachable from a root shell via `ACTION_SET_HOME`, so the
     * radius is clamped on write as well as on read.
     */
    /** Whether GHOST may start a conversation on her own. */
    suspend fun isInitiativeEnabled(ctx: Context): Boolean =
        ctx.ghostDataStore.data.first()[KEY_INITIATIVE_ENABLED] ?: false

    suspend fun setInitiativeEnabled(ctx: Context, enabled: Boolean) {
        ctx.ghostDataStore.edit { p -> p[KEY_INITIATIVE_ENABLED] = enabled }
    }

    /**
     * When she last spoke unprompted, how many times she has today, and when she last used each
     * kind of observation.
     *
     * Persisted rather than held in memory because the daemon restarts — on a kill, on a
     * `START_STICKY` recovery, on every reboot — and a rate limit that resets whenever the process
     * does is not a rate limit. [day] is the local calendar day the count belongs to; a count from
     * yesterday is discarded on read rather than needing a scheduled reset.
     */
    suspend fun initiativeHistory(ctx: Context, today: String): InitiativeHistory {
        val prefs = ctx.ghostDataStore.data.first()
        val storedDay = prefs[KEY_PROACTIVE_DAY]
        val count = if (storedDay == today) (prefs[KEY_PROACTIVE_COUNT] ?: 0) else 0
        val byKind = TransitionKind.entries.mapNotNull { kind ->
            prefs[longPreferencesKey(kindKey(kind))]?.let { kind to it }
        }.toMap()
        return InitiativeHistory(
            lastSpokeAtMs = prefs[KEY_LAST_PROACTIVE_MS] ?: 0L,
            spokenToday = count,
            lastSpokeByKind = byKind,
        )
    }

    /** Record that she spoke. [today] is the local calendar day, so the cap rolls over by itself. */
    suspend fun recordInitiativeSpoken(ctx: Context, kind: TransitionKind, atMs: Long, today: String) {
        ctx.ghostDataStore.edit { p ->
            val sameDay = p[KEY_PROACTIVE_DAY] == today
            p[KEY_PROACTIVE_DAY] = today
            p[KEY_PROACTIVE_COUNT] = (if (sameDay) (p[KEY_PROACTIVE_COUNT] ?: 0) else 0) + 1
            p[KEY_LAST_PROACTIVE_MS] = atMs
            p[longPreferencesKey(kindKey(kind))] = atMs
        }
    }

    private fun kindKey(kind: TransitionKind) = "initiative_last_${kind.name.lowercase()}"

    suspend fun setHomePoint(ctx: Context, lat: Double?, lon: Double?, radiusM: Float?) {
        ctx.ghostDataStore.edit { p ->
            if (lat == null || lon == null) {
                p.remove(KEY_HOME_LAT)
                p.remove(KEY_HOME_LON)
                p.remove(KEY_HOME_RADIUS_M)
            } else {
                p[KEY_HOME_LAT] = lat
                p[KEY_HOME_LON] = lon
                if (radiusM != null) {
                    p[KEY_HOME_RADIUS_M] =
                        radiusM.coerceIn(HomePoint.MIN_RADIUS_M, HomePoint.MAX_RADIUS_M)
                }
            }
        }
    }
}

/** A snapshot of the rate-limit state [GhostPrefs.initiativeHistory] reads in one pass. */
data class InitiativeHistory(
    val lastSpokeAtMs: Long,
    val spokenToday: Int,
    val lastSpokeByKind: Map<TransitionKind, Long>,
)
