package com.coxy.ghost.perception

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single process-wide holder of GHOST's [ContextState]. Every perception modality —
 * [PerceptionManager] (sensors/GMS), [MicListener] (mic), [CameraCapture] (camera) — writes here
 * via [update] and the chat path reads [state].value synchronously before [sendUserPrompt].
 *
 * Extracted from `PerceptionManager`'s companion in Phase 5 so the new modalities share one
 * [StateFlow] without binding to each other. Thread-safe: [MutableStateFlow] is atomic, and each
 * writer only mutates its own fields via a copy transform. No perception component ever touches
 * the InferenceEngine or its single-threaded `llamaDispatcher` — this store is the only bridge.
 */
object ContextStore {
    private val _state = MutableStateFlow(ContextState())

    /** Process-wide perception state. Read by MainActivity to prepend context + render status. */
    val state: StateFlow<ContextState> = _state.asStateFlow()

    /** The single write path. Callers apply a copy transform touching only their own fields. */
    fun update(transform: (ContextState) -> ContextState) {
        _state.value = transform(_state.value)
    }

    /** Reset to idle (timestampMs=0 → "Perception: idle"). Called when all perception stops. */
    fun reset() {
        _state.value = ContextState()
    }
}