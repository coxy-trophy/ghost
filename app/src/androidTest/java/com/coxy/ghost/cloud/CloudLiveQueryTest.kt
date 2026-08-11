package com.coxy.ghost.cloud

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import com.coxy.ghost.GhostPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 live smoke test: sends a real prompt through Ollama Cloud and prints the reply.
 * Requires a stored API key (injected via [CloudKeyInjectionTest] or entered in the UI).
 */
@RunWith(AndroidJUnit4::class)
class CloudLiveQueryTest {

    @Test
    fun live_cloud_query_returns_nonempty_reply() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val key = CloudKeyStorage.getKey(ctx)
        require(!key.isNullOrBlank()) { "Ollama API key is missing — store it via the UI first or run CloudKeyInjectionTest." }

        val settings = GhostPrefs.cloudSettings(ctx)
        Log.i(TAG, "cloud settings: enabled=${settings.enabled}, privacy=${settings.privacyMode}, logging=${settings.loggingEnabled}")

        val engine = AiChat.getInferenceEngine(ctx)
        val router = CloudRouter(ctx, engine)
        val prompt = "What is the capital of France? Answer in one sentence."
        val systemPrompt = "You are a helpful assistant. Keep answers short."

        val reply = StringBuilder()
        withTimeout(120_000) {
            router.route(prompt, systemPrompt, predictLength = 128).collect { reply.append(it) }
        }

        val text = reply.toString().trim()
        Log.i(TAG, "LIVE_CLOUD_REPLY: $text")
        assertTrue("cloud returned empty reply", text.isNotBlank())
    }

    companion object {
        private const val TAG = "CloudLiveQueryTest"
    }
}
