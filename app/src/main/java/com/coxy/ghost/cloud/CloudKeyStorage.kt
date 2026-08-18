package com.coxy.ghost.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.coxy.ghost.BuildConfig

/**
 * Secure storage for the Ollama Cloud API key (Phase 8).
 *
 * Uses AndroidX Security's EncryptedSharedPreferences when the Keystore/Tink setup works. If the
 * device Keystore is flaky (some OEMs), falls back to a plain SharedPreferences inside the app's
 * private storage — still protected by Linux DAC (only this app's uid can read it), but not
 * encrypted at rest.
 */
object CloudKeyStorage {

    private const val PREFS_NAME = "ghost_cloud_key"
    private const val KEY_API_KEY = "ollama_api_key"
    private const val TAG = "CloudKeyStorage"

    private fun encryptedPrefs(context: Context): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", it)
        }.getOrNull()
    }

    private fun plainPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cached: SharedPreferences? = null

    /**
     * The prefs handle is built once per process. Each construction runs `MasterKey.Builder` and a
     * full Tink init, and this used to happen on *every* get and set — including twice per cloud
     * query from [CloudRouter]. SharedPreferences instances are already thread-safe and reflect
     * later writes, so there is nothing to invalidate.
     *
     * If the Keystore is flaky and we fall back to plain prefs, that decision is cached too: a
     * process that silently alternated between encrypted and plain storage would be worse than one
     * that consistently uses the fallback.
     */
    private fun prefs(context: Context): SharedPreferences =
        cached ?: synchronized(this) {
            cached ?: (encryptedPrefs(context) ?: plainPrefs(context)).also { cached = it }
        }

    /**
     * The stored key, or the one baked into the build if nothing is stored.
     *
     * The baked-in key exists because a priv-app deploy can reset `/data/data/com.coxy.ghost`
     * wholesale (GHOST.md §8, cause still unknown), taking `shared_prefs/` and the key with it.
     * Every such wipe silently demoted GHOST to the local 1.5B until someone noticed and typed the
     * key back in — the 2026-08-12 wipe went unnoticed for days exactly that way. Falling back to
     * [BuildConfig.OLLAMA_API_KEY] makes the cloud brain survive a wipe with no manual step.
     *
     * Stored wins over baked-in, so a key entered in the Cloud screen still overrides the build
     * without a rebuild.
     */
    fun getKey(context: Context): String? {
        val stored = prefs(context).getString(KEY_API_KEY, null)
        if (!stored.isNullOrBlank()) return stored

        val baked = BuildConfig.OLLAMA_API_KEY
        if (baked.isNotBlank()) {
            if (!loggedBakedUse) {
                loggedBakedUse = true
                Log.i(TAG, "no stored key — using the key baked in at build time")
            }
            return baked
        }
        return null
    }

    /** Log the fallback once per process rather than on every cloud query. */
    @Volatile
    private var loggedBakedUse: Boolean = false

    /** True when the key in use came from the build rather than from storage. */
    fun isUsingBakedKey(context: Context): Boolean =
        prefs(context).getString(KEY_API_KEY, null).isNullOrBlank() &&
            BuildConfig.OLLAMA_API_KEY.isNotBlank()

    fun setKey(context: Context, key: String?) {
        prefs(context).edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key.trim())
            apply()
        }
    }

    fun hasKey(context: Context): Boolean = !getKey(context).isNullOrBlank()
}
