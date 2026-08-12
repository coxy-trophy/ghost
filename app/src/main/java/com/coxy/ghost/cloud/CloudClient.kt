package com.coxy.ghost.cloud

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a cloud LLM client (Phase 8).
 *
 * Implemented by [OllamaCloudClient] in production; tests supply a fake implementation.
 */
interface CloudClient {
    fun chat(
        systemPrompt: String,
        userPrompt: String,
        predictLength: Int = 512
    ): Flow<String>

    /**
     * The model that actually produced the last reply, or null if none has yet.
     *
     * The client walks a fallback chain, so "the cloud answered" is not the same as "the model we
     * asked for answered" — without this, a retired or misspelled primary model looks identical to
     * a working one from the outside.
     */
    val lastModelUsed: String? get() = null
}
