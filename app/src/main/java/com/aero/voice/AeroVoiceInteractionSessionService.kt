package com.aero.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Session service companion to [AeroVoiceInteractionService]. When the system
 * invokes the assistant (Home long-press / Side button), Aero silently starts
 * the background voice session and shows the floating orb — no app UI.
 */
class AeroVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle): VoiceInteractionSession {
        Log.d(TAG, "assistant invoked via system session")
        if (!AeroState.sessionActive) {
            AeroState.sessionActive = true
            try {
                startForegroundService(android.content.Intent(this, BackgroundSessionService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start background session", t)
            }
        }
        FloatingOrbService.show(this)
        return object : VoiceInteractionSession(this) {}
    }

    companion object {
        private const val TAG = "AeroProxy"
    }
}
