package com.coxy.ghost

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide single-flight guard for the InferenceEngine.
 *
 * The engine serialises native work on a single-threaded `llamaDispatcher`, but two concurrent
 * [InferenceEngine.sendUserPrompt] calls would still interleave tokens in the shared conversation
 * context and corrupt it. Three paths share the engine: [MainActivity] (typed messages),
 * [GhostService] (wake-word voice commands) and [com.coxy.ghost.memory.FactExtractor] (post-turn
 * extraction). Each takes the gate for the duration of its generation, so a voice command arriving
 * while the user is typing (or vice versa) is rejected instead of racing the engine.
 *
 * [tryAcquire] is a compare-and-set, not a read followed by a write. The three callers run on three
 * different threads (main, `Dispatchers.Default`, `GlobalScope`), so the previous
 * `if (busy) return; busy = true` let two of them both observe false and both proceed.
 *
 * Every successful [tryAcquire] must be paired with exactly one [release], from a `finally` or a
 * job-completion handler — a leaked gate wedges the engine for the life of the process.
 */
object EngineGate {

    private val inFlight = AtomicBoolean(false)

    /** True while one of the paths holds the engine. Advisory only — use [tryAcquire] to take it. */
    @JvmStatic
    val busy: Boolean
        get() = inFlight.get()

    /** Atomically take the engine. Returns false if another path already holds it. */
    @JvmStatic
    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    /** Release the engine. Only the caller whose [tryAcquire] returned true may call this. */
    @JvmStatic
    fun release() {
        inFlight.set(false)
    }
}
