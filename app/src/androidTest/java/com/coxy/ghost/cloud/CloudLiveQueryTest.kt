package com.coxy.ghost.cloud

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import com.coxy.ghost.GhostPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 live smoke test: sends a real prompt through Ollama Cloud and prints the reply.
 * Requires a stored API key (injected via [CloudKeyInjectionTest] or entered in the UI).
 *
 * **This is an operational script, not a unit test** — it spends real money on the account behind
 * the stored key. It therefore opts out by default and only runs when asked, the same way
 * [CloudKeyInjectionTest] gates on `-e keyFile`. Without that, a plain `connectedAndroidTest` run
 * billed the account every time.
 */
@RunWith(AndroidJUnit4::class)
class CloudLiveQueryTest {

    @Test
    fun live_cloud_query_returns_nonempty_reply() = runBlocking {
        Assume.assumeTrue(
            "Skipped: calls the real Ollama Cloud API and bills the account. Pass -e cloudLive true to run it.",
            InstrumentationRegistry.getArguments().getString("cloudLive")?.toBoolean() == true
        )
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
