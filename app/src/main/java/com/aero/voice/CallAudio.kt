package com.aero.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Routes Aero call audio between the earpiece (default) and the loudspeaker.
 * Uses the platform communication mode so VoIP-style routing works.
 */
object CallAudio {

    private const val TAG = "AeroCallAudio"
    private var manager: AudioManager? = null

    fun enterCall(ctx: Context) {
        AeroState.inCall = true
        try {
            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager = am
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            Log.d(TAG, "call audio: earpiece")
        } catch (t: Throwable) {
            Log.w(TAG, "enterCall failed", t)
        }
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        try {
            val am = manager
                ?: (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            manager = am
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            @Suppress("DEPRECATION")
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            Log.d(TAG, "call audio: ${if (on) "speaker" else "earpiece"}")
        } catch (t: Throwable) {
            Log.w(TAG, "setSpeaker failed", t)
        }
    }

    fun exitCall(ctx: Context) {
        AeroState.inCall = false
        try {
            val am = manager
                ?: (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_NORMAL
            manager = null
            Log.d(TAG, "call audio: normal mode")
        } catch (_: Throwable) {
        }
    }
}
