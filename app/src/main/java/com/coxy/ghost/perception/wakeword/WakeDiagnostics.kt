package com.coxy.ghost.perception.wakeword

import kotlin.math.abs
import kotlin.math.sqrt

/** Per-window aggregates only: never retains audio samples or recognized text. Worker-thread owned. */
internal class WakeDiagnostics {
    private var reads = 0L
    private var samples = 0L
    private var nonzero = 0L
    private var squares = 0.0
    private var peak = 0
    private var fed = 0L
    private var decoded = 0L
    private var hits = 0L
    private val suppressed = linkedMapOf<String, Long>()

    fun read(input: ShortArray, count: Int, suppression: String?) {
        require(count in 0..input.size)
        reads++
        samples += count
        for (i in 0 until count) {
            val value = input[i].toInt()
            if (value != 0) nonzero++
            squares += value.toDouble() * value
            peak = maxOf(peak, abs(value))
        }
        if (suppression == null) fed += count
        else suppressed[suppression] = (suppressed[suppression] ?: 0) + count
    }
    fun decoded() { decoded++ }
    fun detected() { hits++ }
    fun snapshot(): Snapshot {
        val result = Snapshot(reads, samples, nonzero, if (samples == 0L) 0 else sqrt(squares / samples).toInt(), peak,
            fed, decoded, hits, suppressed.toMap())
        reads = 0; samples = 0; nonzero = 0; squares = 0.0; peak = 0
        fed = 0; decoded = 0; hits = 0; suppressed.clear()
        return result
    }
    data class Snapshot(val reads: Long, val samples: Long, val nonzero: Long, val rms: Int, val peak: Int,
        val fed: Long, val decoded: Long, val hits: Long, val suppressed: Map<String, Long>)
}
