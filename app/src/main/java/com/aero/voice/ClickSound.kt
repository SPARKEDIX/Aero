package com.aero.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import java.util.concurrent.atomic.AtomicBoolean

/** Tiny bubble click sound for the orb/overlay buttons. */
object ClickSound {

    private val ready = AtomicBoolean(false)
    private var tone: ToneGenerator? = null

    private fun ensure(ctx: Context) {
        if (ready.compareAndSet(false, true)) {
            try {
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 58)
            } catch (_: Throwable) {
                tone = null
            }
        }
    }

    fun play(ctx: Context) {
        ensure(ctx)
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 60)
        } catch (_: Throwable) {
        }
    }
}
