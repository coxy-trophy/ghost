package com.coxy.ghost.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun getKey(context: Context): String? {
        val value = prefs(context).getString(KEY_API_KEY, null)
        return if (value.isNullOrBlank()) null else value
    }

    fun setKey(context: Context, key: String?) {
        prefs(context).edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key.trim())
            apply()
        }
    }

    fun hasKey(context: Context): Boolean = !getKey(context).isNullOrBlank()
}
