package com.coxy.ghost.perception

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The single process-wide holder of GHOST's [ContextState]. Every perception modality —
 * [PerceptionManager] (sensors/GMS), [MicListener] (mic), [CameraCapture] (camera) — writes here
 * via [update] and the chat path reads [state].value synchronously before [sendUserPrompt].
 *
 * Extracted from `PerceptionManager`'s companion in Phase 5 so the new modalities share one
 * [StateFlow] without binding to each other. Thread-safe: [update] is a compare-and-set loop, so
 * writers touching different fields cannot clobber each other. No perception component ever touches
 * the InferenceEngine or its single-threaded `llamaDispatcher` — this store is the only bridge.
 */
object ContextStore {
    private val _state = MutableStateFlow(ContextState())

    /** Process-wide perception state. Read by MainActivity to prepend context + render status. */
    val state: StateFlow<ContextState> = _state.asStateFlow()

    /**
     * The single write path. Callers apply a copy transform touching only their own fields.
     *
     * [MutableStateFlow.update] retries the transform on conflict. The previous
     * `_state.value = transform(_state.value)` was a read-modify-write, and six threads write here
     * — sensors, mic, camera, main, and the service coroutine streaming `voiceResponse` once per
     * token — so concurrent writers silently lost each other's fields. The transform can therefore
     * run more than once: keep it pure, no side effects.
     *
     * [ContextState.stampDwell] runs after every transform so the activity dwell clock is stamped
     * centrally. Doing it here rather than in each writer is deliberate: a writer that changed the
     * activity and forgot to stamp would leave a duration attached to the wrong state, which reads
     * as a confident wrong answer rather than a missing one.
     */
    fun update(transform: (ContextState) -> ContextState) {
        _state.update { previous -> transform(previous).stampDwell(previous) }
    }

    /** Reset to idle (timestampMs=0 → "Perception: idle"). Called when all perception stops. */
    fun reset() {
        _state.value = ContextState()
    }
}