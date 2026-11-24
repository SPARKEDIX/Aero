package com.aero.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Session service companion to [AeroVoiceInteractionService]. Required by the
 * system for a default-assistant replacement.
 */
class AeroVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {}
    }
}
