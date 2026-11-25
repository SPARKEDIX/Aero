package com.aero.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Routes Aero call audio between the earpiece (default) and the loudspeaker.
 *
 * - Android 12+ (S): uses the official setCommunicationDevice() API which
 *   actually re-routes the communication stream.
 * - Older Android: setSpeakerphoneOn() + the AudioPlayer rebuilds its track
 *   after every route change so the new route is picked up.
 */
object CallAudio {

    private const val TAG = "AeroCallAudio"
    private var manager: AudioManager? = null

    private fun am(ctx: Context): AudioManager {
        return manager ?: (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also {
            manager = it
        }
    }

    fun enterCall(ctx: Context) {
        AeroState.inCall = true
        try {
            val audio = am(ctx)
            @Suppress("DEPRECATION")
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            routeTo(ctx, earpiece = true)
            Log.d(TAG, "call audio: earpiece (route rev ${AeroState.audioRoute})")
        } catch (t: Throwable) {
            Log.w(TAG, "enterCall failed", t)
        }
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        try {
            val audio = am(ctx)
            @Suppress("DEPRECATION")
            if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            routeTo(ctx, earpiece = !on)
            Log.d(TAG, "call audio: ${if (on) "speaker" else "earpiece"} (route rev ${AeroState.audioRoute})")
        } catch (t: Throwable) {
            Log.w(TAG, "setSpeaker failed", t)
        }
    }

    private fun routeTo(ctx: Context, earpiece: Boolean) {
        try {
            val audio = am(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wantedType = if (earpiece) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == wantedType }
                if (device != null) {
                    audio.setCommunicationDevice(device)
                    AeroState.audioRoute++
                    Log.d(TAG, "setCommunicationDevice -> ${if (earpiece) "earpiece" else "speaker"}")
                    return
                }
                Log.w(TAG, "wanted output device not found, falling back")
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !earpiece
            AeroState.audioRoute++
            Log.d(TAG, "setSpeakerphoneOn -> ${if (earpiece) "earpiece" else "speaker"}")
        } catch (t: Throwable) {
            Log.w(TAG, "routeTo failed", t)
        }
    }

    fun exitCall(ctx: Context) {
        AeroState.inCall = false
        try {
            val audio = am(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // setCommunicationDevice(null) resets routing; reflection avoids
                    // the non-null kotlin stub on some SDK levels.
                    val m = audio.javaClass.getMethod(
                        "setCommunicationDevice",
                        Class.forName("android.media.AudioDeviceInfo")
                    )
                    m.invoke(audio, null)
                } catch (_: Throwable) {
                }
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            audio.mode = AudioManager.MODE_NORMAL
            manager = null
            AeroState.audioRoute++
            Log.d(TAG, "call audio: normal mode (route rev ${AeroState.audioRoute})")
        } catch (_: Throwable) {
        }
    }
}
