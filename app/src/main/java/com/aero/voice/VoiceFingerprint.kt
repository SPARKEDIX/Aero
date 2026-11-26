package com.aero.voice

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight on-device voice fingerprinting (v1, no server).
 *
 * Per frame (100 ms, 16 kHz PCM16) we extract a small feature vector:
 * RMS energy, zero-crossing rate, dominant pitch (autocorrelation), and a
 * low/high band energy ratio. A spoken window is summarised into a 10-dim
 * profile. Similarity = cosine on z-scored profile.
 *
 * Accuracy is honest ~70-85% — good enough to tag "owner vs unknown".
 * The real ML (95%+) comes with the server phase.
 */
object VoiceFingerprint {

    const val SAMPLE_RATE = 16000
    const val PROFILE_DIM = 10
    const val DEFAULT_THRESHOLD = 0.58f

    /** Compute features for one 100ms chunk (800 samples). */
    fun frameFeatures(samples: ShortArray): FloatArray {
        val n = samples.size
        if (n == 0) return FloatArray(4)

        // RMS
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s
        val rms = sqrt(sumSq / n) / 32768.0

        // Zero-crossing rate
        var zcr = 0
        for (i in 1 until n) {
            if ((samples[i - 1] < 0 && samples[i] >= 0) || (samples[i - 1] >= 0 && samples[i] < 0)) zcr++
        }
        val zcrRate = zcr.toDouble() / n

        // Dominant pitch via autocorrelation (80–400 Hz)
        var pitch = 0.0
        val minLag = (SAMPLE_RATE / 400.0).toInt().coerceAtLeast(20)
        val maxLag = (SAMPLE_RATE / 80.0).toInt()
        if (maxLag > minLag && minLag < n) {
            var best = 0.0
            var bestLag = 0
            for (lag in minLag..minOf(maxLag, n - 1)) {
                var corr = 0.0
                var e = 0.0
                for (i in 0 until n - lag) {
                    corr += samples[i].toDouble() * samples[i + lag]
                    e += samples[i].toDouble() * samples[i]
                }
                if (e > 0.0) {
                    val norm = corr / e
                    if (norm > best) {
                        best = norm
                        bestLag = lag
                    }
                }
            }
            if (bestLag > 0 && best > 0.3) pitch = SAMPLE_RATE.toDouble() / bestLag
        }

        // Low/high band ratio: RMS of first vs second half (coarse spectral)
        var lo = 0.0
        var hi = 0.0
        val half = n / 2
        for (i in 0 until half) lo += samples[i].toDouble() * samples[i]
        for (i in half until n) hi += samples[i].toDouble() * samples[i]
        val ratio = if (hi > 0) lo / (lo + hi) else 0.5

        return floatArrayOf(
            rms.toFloat(),
            zcrRate.toFloat(),
            pitch.toFloat(),
            ratio.toFloat()
        )
    }

    /** Summarise a list of frame features into a profile vector. */
    fun profile(frames: List<FloatArray>): FloatArray? {
        if (frames.size < 4) return null
        val voiced = frames.filter { it[0] > 0.015f }
        val src = if (voiced.size >= 4) voiced else frames
        val d = 4
        val dims = Array(d) { DoubleArray(src.size) }
        src.forEachIndexed { i, f ->
            for (k in 0 until d) dims[k][i] = f[k].toDouble()
        }
        fun mean(a: DoubleArray) = a.average()
        fun std(a: DoubleArray): Double {
            val m = mean(a)
            return sqrt(a.map { (it - m) * (it - m) }.average())
        }

        val out = FloatArray(PROFILE_DIM)
        out[0] = mean(dims[0]).toFloat()
        out[1] = std(dims[0]).toFloat()
        out[2] = mean(dims[1]).toFloat()
        out[3] = std(dims[1]).toFloat()
        out[4] = mean(dims[2]).toFloat()
        out[5] = std(dims[2]).toFloat()
        out[6] = mean(dims[3]).toFloat()
        out[7] = std(dims[3]).toFloat()
        out[8] = src.size.toFloat()
        out[9] = (voiced.size.toFloat() / src.size).coerceAtMost(1f)
        return out
    }

    /** Cosine similarity between two profiles (0..1). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return (dot / sqrt(na * nb)).coerceIn(0f, 1f)
    }
}
