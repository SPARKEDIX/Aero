package com.aero.voice

import android.content.Context

/**
 * Rolling speaker context: consumes mic frames in the background (no extra
 * latency for the AI path) and tags the latest turn with a speaker identity.
 */
object SpeakerContext {

    private const val MAX_FRAMES = 140 // ~14 seconds of 100ms frames
    private const val TAG_COOLDOWN_MS = 25_000L

    private val frames = mutableListOf<FloatArray>()
    private var lastTagAt = 0L

    @Synchronized
    fun feed(samples: ShortArray) {
        if (samples.size < 160) return
        frames.add(VoiceFingerprint.frameFeatures(samples))
        while (frames.size > MAX_FRAMES) frames.removeAt(0)
    }

    @Synchronized
    fun reset() {
        frames.clear()
        lastTagAt = 0L
    }

    /** Returns a "[Speaker] …" tag to inject once per turn (or null). */
    @Synchronized
    fun tag(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - lastTagAt < TAG_COOLDOWN_MS) return null

        val profile = VoiceFingerprint.profile(frames.toList()) ?: return null
        val enrolled = VoiceStore.all(ctx)
        if (enrolled.isEmpty()) return null

        val hit = VoiceStore.identify(ctx, profile)
        lastTagAt = now
        frames.clear()

        return if (hit != null) {
            "[Speaker] ${hit.first.label}"
        } else {
            "[Speaker] UnknownPerson"
        }
    }
}
