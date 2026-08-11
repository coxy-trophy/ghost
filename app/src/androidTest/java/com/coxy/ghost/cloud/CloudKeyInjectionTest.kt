package com.coxy.ghost.cloud

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * One-time secure setup: injects the Ollama Cloud API key into the app's encrypted storage.
 *
 * The key is read from a file path supplied via instrumentation arguments (e.g. /data/local/tmp/ghost_key.txt),
 * written into [CloudKeyStorage], and verified. The temporary source file should be deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CloudKeyInjectionTest {

    @Test
    fun inject_ollama_api_key() {
        val args = InstrumentationRegistry.getArguments()
        val keyFile = args.getString("keyFile")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("Pass -e keyFile /path/to/key.txt to inject a key", !keyFile.isNullOrBlank())

        runBlocking {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val key = File(keyFile!!).readText().trim()
            require(key.isNotBlank()) { "Key file is empty: $keyFile" }

            CloudKeyStorage.setKey(ctx, key)
            val stored = CloudKeyStorage.getKey(ctx)
            assertTrue("key was not stored", !stored.isNullOrBlank())
            Log.i(TAG, "Ollama API key stored securely (${stored!!.takeLast(4)})")
        }
    }

    companion object {
        private const val TAG = "CloudKeyInjectionTest"
    }
}
