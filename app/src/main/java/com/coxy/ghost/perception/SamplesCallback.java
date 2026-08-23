package com.coxy.ghost.perception;

import kotlin.jvm.functions.Function1;

/**
 * Piper's audio callback — the one file in GHOST written in Java, on purpose.
 *
 * sherpa-onnx streams generated audio by calling back into the JVM from C++, and its JNI looks the
 * method up by exact name and descriptor: {@code invoke([F)Ljava/lang/Integer;}. Nothing written in
 * Kotlin produces that method. A lambda erases to {@code invoke(Object)Object} and D8 rewrites it
 * into a synthetic class; an explicit Kotlin class implementing {@code Function1<FloatArray, Int>}
 * is worse, because Kotlin specialises the return to a primitive and emits {@code invoke([F)I}.
 * Java, implementing the same interface with a boxed {@code Integer}, emits the descriptor the JNI
 * is asking for.
 *
 * This is not a style preference and should not be "cleaned up" into Kotlin. When the lookup fails
 * the result is {@code JNI DETECTED ERROR IN APPLICATION}, which aborts the process rather than
 * throwing — there is no exception to catch and no fallback path to take, so the signature has to be
 * right the first time.
 *
 * Returning non-zero keeps generation running; zero asks it to stop.
 */
public final class SamplesCallback implements Function1<float[], Integer> {

    /** A Kotlin lambda gets converted to this, so callers still read like callers. */
    public interface OnChunk {
        void onChunk(float[] samples);
    }

    private final OnChunk onChunk;

    public SamplesCallback(OnChunk onChunk) {
        this.onChunk = onChunk;
    }

    @Override
    public Integer invoke(float[] samples) {
        onChunk.onChunk(samples);
        return 1;
    }
}
