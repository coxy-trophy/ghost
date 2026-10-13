package com.coxy.ghost.execution

import org.junit.Assert.*
import org.junit.Test

class BoundedProcessRunnerTest {
    @Test fun mergedOutputCannotDeadlockOnFullStderrPipe() {
        val result = BoundedProcessRunner.run(listOf("sh", "-c", "i=0; while [ \"\u0024i\" -lt 6000 ]; do echo diagnostic >&2; i=\u0024((i+1)); done; echo finished"), 5000, 256)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.truncated)
        assertTrue(result.output.toByteArray().size <= 256)
    }
    @Test fun timeoutDoesNotReportSuccess() {
        val start = System.nanoTime()
        val result = BoundedProcessRunner.run(listOf("sh", "-c", "exec sleep 10"), 100)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue((System.nanoTime()-start)/1_000_000 < 3000)
    }
    @Test fun argumentBoundariesArePreserved() {
        val result = BoundedProcessRunner.run(listOf("printf", "%s", "literal; \u0024(unexecuted)"))
        assertEquals("literal; \u0024(unexecuted)", result.output)
        assertEquals(0, result.exitCode)
    }
}
