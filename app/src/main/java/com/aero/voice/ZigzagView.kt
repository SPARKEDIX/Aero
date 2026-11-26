package com.aero.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Red zigzag line that animates horizontally. Used to visually flag the API
 * key input when a test fails.
 */
class ZigzagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 68, 68)
        strokeWidth = dp(3)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 450L
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    private var phase = 0f
    private var animating = false

    fun startZigzag() {
        if (animating) return
        animating = true
        animator.start()
    }

    fun stopZigzag() {
        animating = false
        animator.cancel()
        phase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height / 2f
        val path = Path()
        var x = -w + phase * w * 2f
        var up = true
        path.moveTo(x, h)
        while (x <= w) {
            x += 18f * resources.displayMetrics.density
            up = !up
            path.lineTo(x, h + if (up) -8f * resources.displayMetrics.density else 8f * resources.displayMetrics.density)
        }
        canvas.drawPath(path, paint)
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
