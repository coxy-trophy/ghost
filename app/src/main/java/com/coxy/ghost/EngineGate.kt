package com.coxy.ghost

/**
 * Process-wide single-flight guard for the InferenceEngine.
 *
 * The engine serialises native work on a single-threaded `llamaDispatcher`, but two concurrent
 * [InferenceEngine.sendUserPrompt] calls would still interleave tokens in the shared conversation
 * context and corrupt it. Two senders share the engine: [MainActivity] (typed messages) and
 * [GhostService] (wake-word voice commands). Both check [busy] before sending and set it for the
 * duration of their generation, so a voice command arriving while the user is typing (or vice versa)
 * is rejected instead of racing the engine.
 *
 * Volatile + JvmStatic so it is visible across the Activity and Service, which run in one process.
 */
object EngineGate {
    @Volatile
    @JvmStatic
    var busy: Boolean = false
}