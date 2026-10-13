package com.coxy.ghost.ipc

/** One-way presentation handoff; recovery never creates a second voice interaction. */
class InteractionSurface {
    var session: String = ""
        private set
    var owner: String = "island"
        private set

    fun begin(sessionId: String, useIsland: Boolean) {
        session = sessionId
        owner = if (useIsland) "island" else "overlay"
    }

    fun recover(expectedSession: String, active: Boolean, islandAvailable: Boolean): Boolean {
        if (expectedSession.isEmpty() || session != expectedSession || !active ||
            owner != "island" || islandAvailable) return false
        owner = "overlay"
        return true
    }

    fun clear() = begin("", true)
}
