package com.coxy.ghost.execution

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Drains both streams together and bounds retained output independently from process duration. */
internal object BoundedProcessRunner {
    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean, val truncated: Boolean)
    fun run(argv: List<String>, timeoutMs: Long = 15_000, outputLimit: Int = 32_768): Result {
        require(argv.isNotEmpty() && argv.all { '\u0000' !in it })
        require(timeoutMs in 1..120_000 && outputLimit in 1..262_144)
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val bytes = ByteArray(4096)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        synchronized(output) {
                            val keep = minOf(count, outputLimit - output.size())
                            if (keep > 0) output.write(bytes, 0, keep)
                            if (keep < count) truncated.set(true)
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* Expected when a timed-out process is torn down. */ }
        }, "ghost-command-output").apply { isDaemon = true; start() }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            reader.join(500)
            if (reader.isAlive) truncated.set(true)
            val text = synchronized(output) { output.toString("UTF-8") }
            return Result(if (finished) process.exitValue() else null, text, !finished, truncated.get())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
