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
}
