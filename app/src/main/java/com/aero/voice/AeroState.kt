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

    /** Set while a voice session runs, so the floating service can send frames. */
    @Volatile
    var liveClient: GeminiLiveClient? = null
}
