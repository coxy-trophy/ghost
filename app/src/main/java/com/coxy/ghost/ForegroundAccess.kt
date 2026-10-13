package com.coxy.ghost

/** Only a successful platform promotion grants capture access; permission grants alone do not. */
internal class ForegroundAccess(private val base: Int) {
    @Volatile var accepted: Int = 0
        private set

    fun has(type: Int): Boolean = accepted and type == type

    fun promote(requested: List<Int>, start: (Int) -> Unit, denied: (Int, SecurityException) -> Unit) {
        if (accepted == 0) {
            start(base) // Failure of the base service is not a capture-permission failure.
            accepted = base
        }
        for (type in requested) {
            if (has(type)) continue
            val candidate = accepted or type
            try {
                start(candidate)
                accepted = candidate
            } catch (failure: SecurityException) {
                denied(type, failure)
            }
        }
    }
}
