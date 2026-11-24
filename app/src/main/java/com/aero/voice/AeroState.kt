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
}
