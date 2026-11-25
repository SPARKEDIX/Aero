package com.aero.voice

/** Shared state between the in-app orb and the floating overlay. */
object AeroState {
    @Volatile
    var micBoost = 0f

    @Volatile
    var aiBoost = 0f

    @Volatile
    var micMuted = false

    @Volatile
    var sessionActive = false

    /** True while the in-app Aero call UI is active (routes audio to earpiece/speaker). */
    @Volatile
    var inCall = false

    /** Bumped whenever audio routing should be re-applied (earpiece/speaker). */
    @Volatile
    var audioRoute = 0

    /** Set while a voice session runs, so the floating service can send frames. */
    @Volatile
    var liveClient: GeminiLiveClient? = null

    /** True once Aero has been granted the system assistant role. */
    @Volatile
    var assistantRoleGranted = false

    /** Wall-clock time (ms) of the last assistant-invoked wake. */
    @Volatile
    private var proxyWakeAt = 0L

    fun markProxyWake() {
        proxyWakeAt = System.currentTimeMillis()
    }

    /** True once shortly after an assistant wake; clears itself. */
    fun consumeProxyWake(): Boolean {
        val now = System.currentTimeMillis()
        if (now - proxyWakeAt < 3000L) {
            proxyWakeAt = 0L
            return true
        }
        return false
    }
}
