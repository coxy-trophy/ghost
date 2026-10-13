package com.coxy.ghost.platform

/** Main-thread ownership, including completion delivered before creation returns. */
internal class ProbeLifetime : AutoCloseable {
    private var handle: AutoCloseable? = null
    var finished = false
        private set

    fun attach(value: AutoCloseable) {
        if (finished) runCatching { value.close() } else handle = value
    }

    override fun close() {
        if (finished) return
        finished = true
        val previous = handle
        handle = null
        runCatching { previous?.close() }
    }
}
