package com.coxy.ghost.perception.wakeword

import android.content.Context

/**
 * Pluggable wake-word detector. Implementations receive raw 16-bit PCM [samples] at 16 kHz mono
 * and return a probability (0.0–1.0) that the phrase "Hey Ghost" is present in the most recent
 * 1-second window.
 */
interface WakeWordDetector {
    /** Load model/resources. Return true if the detector is usable. */
    fun init(context: Context): Boolean

    /**
     * Process the next chunk of audio. [samples] are 16-bit mono PCM at 16 kHz.
     * Returns a wake-word probability in [0.0, 1.0].
     */
    fun process(samples: ShortArray): Float

    /**
     * Discard buffered audio without unloading the model, so the next [process] scores only audio
     * captured after this call.
     *
     * Needed whenever the stream is interrupted — after a command capture, the ring still holds the
     * second of audio that triggered it, and scoring that again would immediately re-fire. Before
     * this existed, [MicListener] re-ran [init] to get the same effect, which tore down and rebuilt
     * the TFLite interpreter (file stat + interpreter construction) on the mic thread on every wake.
     */
    fun reset()

    /** Release native/model resources. */
    fun release()
}
