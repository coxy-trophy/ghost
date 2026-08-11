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

    private fun prefs(context: Context): SharedPreferences = encryptedPrefs(context) ?: plainPrefs(context)

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
