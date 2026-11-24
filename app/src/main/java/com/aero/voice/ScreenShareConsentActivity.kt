package com.aero.voice

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline that shows ONLY the system screen-share consent dialog.
 *
 * The floating orb uses this so that (re)starting screen sharing never brings
 * the main app to the front — the user just sees the consent dialog, accepts,
 * and lands right back where they were.
 */
class ScreenShareConsentActivity : ComponentActivity() {

    private var finished = false

    private val picker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d(TAG, "consent OK, starting ScreenShareService")
                val intent = Intent(this, ScreenShareService::class.java).apply {
                    putExtra(ScreenShareService.EXTRA_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_DATA, result.data)
                }
                try {
                    startForegroundService(intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to start projection service", t)
                }
            } else {
                Log.w(TAG, "consent cancelled result=${result.resultCode}")
            }
            finishAndReturn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MediaProjectionManager::class.java)
        Log.d(TAG, "launching consent")
        picker.launch(mgr.createScreenCaptureIntent())
    }

    private fun finishAndReturn() {
        if (finished) return
        finished = true
        // Drop straight back to whatever was underneath (home / the orb).
        moveTaskToBack(true)
        finish()
    }

    companion object {
        private const val TAG = "AeroScreen"
    }
}
