package com.aero.voice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Transparent activity that makes Aero a visible candidate in the system's
 * "Digital assistant app" picker (via the ASSIST/VOICE_ASSIST intent filters).
 *
 * When the system invokes the assistant (Home long-press / side button), this
 * activity opens invisibly, starts the background voice session and shows the
 * floating orb — the main app UI never appears.
 */
class AssistantProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "assistant invoked — starting background session")

        // Start (or resume) the invisible voice session and show the orb.
        if (!AeroState.sessionActive) {
            AeroState.sessionActive = true
            try {
                startForegroundService(Intent(this, BackgroundSessionService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start background session", t)
            }
        }
        FloatingOrbService.show(this)

        // This proxy is invisible; close immediately.
        finish()
    }

    companion object {
        private const val TAG = "AeroProxy"
    }
}
