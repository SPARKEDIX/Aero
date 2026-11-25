package com.aero.voice

import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * YouTube controls:
 * - Play / Pause / Toggle via the platform MediaSession API (works for the
 *   YouTube app without any special permission).
 * - Playback speed via the accessibility service (best effort): opens the
 *   player's "More options" menu → "Playback speed" → the requested rate.
 */
object YouTubeControl {

    private const val TAG = "AeroYoutube"
    private const val YT_PKG = "com.google.android.youtube"

    fun mediaPlayPause(ctx: Context, wantPlay: Boolean?): String {
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = try {
                msm.getActiveSessions(null)
            } catch (_: Throwable) {
                emptyList()
            }
            // Prefer a YouTube session; else any active session.
            val yt = sessions.firstOrNull { it.packageName == YT_PKG }
            val target = yt ?: sessions.firstOrNull()
            if (target == null) {
                return "YouTube ya koi media session active nahi mila. Pehle koi video chalao."
            }
            val controls = target.transportControls
            when (wantPlay) {
                true -> {
                    controls.play()
                    "YouTube video play kar diya."
                }
                false -> {
                    controls.pause()
                    "YouTube video pause kar diya."
                }
                else -> {
                    // Toggle: if playing → pause, else play.
                    val state = target.playbackState?.state
                    val playing = state == PlaybackState.STATE_PLAYING ||
                        state == PlaybackState.STATE_BUFFERING
                    if (playing) {
                        controls.pause()
                        "YouTube video pause kar diya."
                    } else {
                        controls.play()
                        "YouTube video play kar diya."
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "media control failed", t)
            "Media control kaam nahi kiya: ${t.message}"
        }
    }

    /**
     * Changes playback speed via accessibility. Sequence:
     * More options → Playback speed → <rate label>.
     */
    fun setSpeed(ctx: Context, rateLabel: String) {
        val handler = Handler(Looper.getMainLooper())
        if (!AeroAccessibilityService.isEnabled()) {
            Log.w(TAG, "accessibility not enabled for speed change")
            return
        }
        // Make sure YouTube is in the foreground.
        val hit = AppControl.findApp(ctx, "youtube")
        if (hit != null && hit.second == YT_PKG) {
            val pkg = AeroAccessibilityService.currentPackage()
            if (pkg != YT_PKG) {
                AppControl.openApp(ctx, "youtube")
            }
        }

        AeroAccessibilityService.requestWaitThenTap("More options", 6000L)
        handler.postDelayed({
            AeroAccessibilityService.requestWaitThenTap("Playback speed", 6000L)
        }, 1800L)
        handler.postDelayed({
            AeroAccessibilityService.requestWaitThenTap(rateLabel, 6000L)
        }, 3400L)
        Log.d(TAG, "speed sequence started for \"$rateLabel\"")
    }
}
