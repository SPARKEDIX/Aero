package com.aero.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dark pill with a soft blue-white glow at the bottom — the AI visual inside
 * the expanded floating overlay. Same gentle floating motion as the orb.
 */
class CylinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clipPath = Path()
    private val startAt = SystemClock.elapsedRealtime()

    private val palette = MoodPalette.forKey(Prefs.mood(context))

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 10000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { postInvalidateOnAnimation() }
        start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        val radius = minOf(w * 0.5f, h * 0.22f)
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // Deep slate body, lighter toward the bottom like the reference pill.
        body.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF2A2D38.toInt(), 0xFF20232C.toInt(), 0xFF15171E.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val radius = minOf(w * 0.5f, h * 0.22f)
        canvas.drawRoundRect(rect, radius, radius, body)

        val t = (SystemClock.elapsedRealtime() - startAt) / 1000f
        val energy = maxOf(AeroState.micBoost, AeroState.aiBoost).coerceIn(0f, 1f)
        val tint = palette.smoke and 0x00FFFFFF

        canvas.save()
        canvas.clipPath(clipPath)

        // Soft blue-white smoke pooled at the bottom, gently drifting.
        for (i in 0..2) {
            val dx = cos(t * 0.5f + i * 2f) * w * 0.12f
            val dy = sin(t * 0.4f + i * 1.6f) * h * 0.05f
            val cx = w * 0.5f + dx
            val cy = h * (0.78f + i * 0.06f) + dy
            val r = w * (0.55f + 0.08f * i)
            glow.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    0xC0000000.toInt() or tint,
                    0x55000000.toInt() or tint,
                    0x00FFFFFF
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, glow)
        }

        // A bright core that breathes with the voice level.
        val coreR = w * (0.22f + 0.10f * energy)
        glow.shader = RadialGradient(
            w * 0.5f, h * 0.82f, coreR,
            intArrayOf(0xE6FFFFFF.toInt(), 0x66000000.toInt() or tint, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.5f, h * 0.82f, coreR, glow)
        canvas.restore()
    }
}
