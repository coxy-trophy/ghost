package com.coxy.ghost.cloud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 routing-layer tests.
 *
 * Uses a fake local engine and a configurable fake cloud client to verify:
 *  - cloud-first when enabled + key present
 *  - instant local fallback when cloud is disabled, key missing, or cloud throws
 *  - privacy filter is applied before sending to cloud
 */
@RunWith(AndroidJUnit4::class)
class CloudRouterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun routes_to_cloud_when_enabled_and_key_present() = runBlocking {
        val fakeEngine = FakeInferenceEngine()
        val fakeClient = FakeOllamaCloudClient("cloud reply")
        val router = CloudRouter(
            context = context,
            engine = fakeEngine,
            clientFactory = { fakeClient },
            keyProvider = { "fake-key" },
            settingsProvider = { CloudSettings(enabled = true, privacyMode = CloudPromptFilter.Mode.FULL, loggingEnabled = false) }
        )

        val tokens = router.route("user message", "system prompt").toList()

        assertEquals(listOf("cloud reply"), tokens)
        assertEquals("local engine should not be used on cloud success", null, fakeEngine.lastPrompt)
    }

    @Test
    fun falls_back_to_local_when_cloud_disabled() = runBlocking {
        val fakeEngine = FakeInferenceEngine()
        val fakeClient = FakeOllamaCloudClient("cloud reply")
        val router = CloudRouter(
            context = context,
            engine = fakeEngine,
            clientFactory = { fakeClient },
            keyProvider = { "fake-key" },
            settingsProvider = { CloudSettings(enabled = false, privacyMode = CloudPromptFilter.Mode.FULL, loggingEnabled = false) }
        )

        val tokens = router.route("user message", "system prompt").toList()

        assertEquals(listOf("local reply"), tokens)
        assertEquals("user message", fakeEngine.lastPrompt)
    }

    @Test
    fun falls_back_to_local_when_key_missing() = runBlocking {
        val fakeEngine = FakeInferenceEngine()
        val router = CloudRouter(
            context = context,
            engine = fakeEngine,
            keyProvider = { null },
            settingsProvider = { CloudSettings(enabled = true, privacyMode = CloudPromptFilter.Mode.FULL, loggingEnabled = false) }
        )

        val tokens = router.route("user message", "system prompt").toList()

        assertEquals(listOf("local reply"), tokens)
    }

    @Test
    fun falls_back_to_local_when_cloud_throws() = runBlocking {
        val fakeEngine = FakeInferenceEngine()
        val router = CloudRouter(
            context = context,
            engine = fakeEngine,
            clientFactory = { throw RuntimeException("network down") },
            keyProvider = { "fake-key" },
            settingsProvider = { CloudSettings(enabled = true, privacyMode = CloudPromptFilter.Mode.FULL, loggingEnabled = false) }
        )

        val tokens = router.route("user message", "system prompt").toList()

        assertEquals(listOf("local reply"), tokens)
    }

    @Test
    fun applies_privacy_filter_before_cloud() = runBlocking {
        val fakeEngine = FakeInferenceEngine()
        val fakeClient = FakeOllamaCloudClient("cloud reply")
        val router = CloudRouter(
            context = context,
            engine = fakeEngine,
            clientFactory = { fakeClient },
            keyProvider = { "fake-key" },
            settingsProvider = { CloudSettings(enabled = true, privacyMode = CloudPromptFilter.Mode.CHAT_ONLY, loggingEnabled = false) }
        )

        router.route("[CONTEXT activity=still]\n[MEMORY | user: fact]\nuser message", "system prompt").toList()

        assertEquals("user message", fakeClient.capturedUserPrompt)
    }

    private class FakeInferenceEngine : InferenceEngine {
        var lastPrompt: String? = null
        override val state: MutableStateFlow<InferenceEngine.State> = MutableStateFlow(InferenceEngine.State.ModelReady)

        override suspend fun loadModel(pathToModel: String) {}
        override suspend fun setSystemPrompt(systemPrompt: String) {}
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> {
            lastPrompt = message
            return flowOf("local reply")
        }
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = ""
        override fun cleanUp() {}
        override fun destroy() {}
    }

    private class FakeOllamaCloudClient(private val reply: String) : CloudClient {
        var capturedUserPrompt: String? = null
        override fun chat(systemPrompt: String, userPrompt: String, predictLength: Int): Flow<String> {
            capturedUserPrompt = userPrompt
            return flow { emit(reply) }
        }
    }

    private suspend fun <T> Flow<T>.toList(): List<T> {
        val list = mutableListOf<T>()
        collect { list += it }
        return list
    }
}
