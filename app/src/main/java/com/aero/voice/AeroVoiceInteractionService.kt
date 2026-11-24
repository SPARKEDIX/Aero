package com.aero.voice

import android.service.voice.VoiceInteractionService

/**
 * Lets Android treat Aero as a digital assistant (replacing the default one,
 * e.g. Gemini). The actual conversation runs in the app / background session;
 * this service just makes Aero selectable as the system assistant.
 */
class AeroVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Hotword listening is already handled by HotwordService.
    }
}
