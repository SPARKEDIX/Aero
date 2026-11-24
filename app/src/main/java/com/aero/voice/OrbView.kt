package com.aero.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ChatGPT Voice-style orb.
 *
 * On Android 13+ the orb is a single GPU fragment shader (AGSL). Instead of
 * evaluating procedural noise per pixel (expensive on budget GPUs), the smoke
 * comes from a pre-baked seamless fractal texture that is sampled with
 * domain-warped UVs. That is only a handful of texture fetches per pixel, so
 * the animation stays locked to vsync at 60fps.
 *
 * Older devices fall back to a gradient sphere with soft drifting blobs.
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Raw targets set from audio threads. */
    @Volatile
    var micBoost = 0f

    @Volatile
    var aiBoost = 0f

    // Smoothed values used for drawing (no abrupt jumps).
    private var micSmooth = 0f
    private var aiSmooth = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var cx = 0f
    private var cy = 0f
    private var baseR = 0f

    private var runtimeShader: RuntimeShader? = null
    private var noiseShader: BitmapShader? = null

    private val startAt = SystemClock.elapsedRealtime()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 10000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { postInvalidateOnAnimation() }
    }

    init {
        // The smoke texture is only needed by the AGSL path.
        if (Build.VERSION.SDK_INT >= 33) buildNoiseTexture()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h * 0.48f
        baseR = min(w, h) * 0.30f

        basePaint.shader = LinearGradient(
            cx, cy - baseR, cx, cy + baseR,
            intArrayOf(
                0xFF5B5BF0.toInt(),
                0xFF7B86FF.toInt(),
                0xFF9AA5FF.toInt(),
                0xFFB7C0FF.toInt(),
                0xFFD6DCFF.toInt()
            ),
            floatArrayOf(0f, 0.34f, 0.62f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )

        glowPaint.shader = RadialGradient(
            cx, cy + baseR * 0.18f, baseR * 1.30f,
            intArrayOf(0x1E5B5BF0, 0x0A5B5BF0, 0x00000000),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        if (Build.VERSION.SDK_INT >= 33) ensureRuntimeShader()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseR <= 0f) return

        // Decay raw targets, then low-pass them for buttery-smooth motion.
        micBoost *= 0.90f
        aiBoost *= 0.93f
        micSmooth += (micBoost.coerceIn(0f, 1f) - micSmooth) * 0.10f
        aiSmooth += (aiBoost.coerceIn(0f, 1f) - aiSmooth) * 0.10f
        val mic = micSmooth.coerceIn(0f, 1f)
        val ai = aiSmooth.coerceIn(0f, 1f)
        val activity = maxOf(mic, ai)

        // Relative time keeps float precision high (uptime values flatten noise).
        val now = (SystemClock.elapsedRealtime() - startAt) / 1000f

        // Gentle breathing + clear voice-reactive vibration.
        val breathe = 0.008f * sin(now * 0.55f)
        val twoPi = (2f * Math.PI).toFloat()
        // AI speech: pronounced fast wobble. User speech: slightly softer.
        val aiVib = 0.045f * ai * sin(now * 5.4f * twoPi)
        val micVib = 0.030f * mic * sin(now * 4.2f * twoPi)
        val r = baseR * (1f + breathe + aiVib + micVib)
        // A slight squash/stretch makes the vibration read clearly.
        val squash = 0.014f * activity * sin(now * 5.4f * twoPi + 0.9f)

        // Soft halo
        canvas.drawCircle(
            cx, cy + baseR * 0.18f,
            baseR * 1.26f * (1f + 0.03f * activity),
            glowPaint
        )

        val shader = runtimeShader
        canvas.save()
        canvas.scale(1f + squash, 1f - squash, cx, cy)
        if (Build.VERSION.SDK_INT >= 33 && shader != null && noiseShader != null) {
            drawShaderOrb(canvas, shader, r, now, activity)
        } else {
            drawFallbackOrb(canvas, r, now)
        }

        // Soft inner rim for depth
        rimPaint.color = 0x16FFFFFF
        rimPaint.strokeWidth = r * 0.012f
        canvas.drawCircle(cx, cy, r * 0.995f, rimPaint)
        canvas.restore()
    }

    @RequiresApi(33)
    private fun drawShaderOrb(
        canvas: Canvas,
        shader: RuntimeShader,
        r: Float,
        now: Float,
        activity: Float
    ) {
        shader.setFloatUniform("uCenter", cx, cy)
        shader.setFloatUniform("uRadius", r)
        shader.setFloatUniform("uTime", now)
        shader.setFloatUniform("uEnergy", activity)
        shaderPaint.shader = shader
        canvas.drawCircle(cx, cy, r, shaderPaint)
    }

    private fun drawFallbackOrb(canvas: Canvas, r: Float, now: Float) {
        canvas.drawCircle(cx, cy, r, basePaint)
        for (i in 0..4) {
            val phase = i * 1.7f
            val ang = now * (0.10f + 0.02f * i) + phase
            val bx = cx + cos(ang) * r * (0.10f + 0.06f * i)
            val by = cy + sin(ang * 0.8f + phase) * r * (0.12f + 0.05f * i)
            val br = r * (0.55f + 0.10f * (i % 3))
            blobPaint.shader = RadialGradient(
                bx, by, br,
                intArrayOf(0x66FFFFFF, 0x26FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(bx, by, br, blobPaint)
        }
    }

    @RequiresApi(33)
    private fun ensureRuntimeShader() {
        if (runtimeShader == null) {
            runtimeShader = try {
                RuntimeShader(ORB_AGSL)
            } catch (t: Throwable) {
                null
            }
        }
        val noise = noiseShader
        val shader = runtimeShader
        if (shader != null && noise != null) {
            shader.setInputShader("uNoise", noise)
        }
    }

    @RequiresApi(33)
    private fun buildNoiseTexture() {
        Thread({
            val bmp = makeNoiseTexture(256)
            val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            post {
                noiseShader = shader
                ensureRuntimeShader()
                invalidate()
            }
        }, "orb-smoke-tex").start()
    }

    /**
     * Seamless (tileable) fractal value-noise texture. Low base frequency keeps
     * large soft billows; extra octaves add fine detail.
     */
    private fun makeNoiseTexture(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rnd = Random(11)
        val grids = intArrayOf(3, 6, 12, 24)
        val lattices = Array(grids.size) { gi ->
            FloatArray(grids[gi] * grids[gi]) { rnd.nextFloat() }
        }

        for (y in 0 until size) {
            val fy = y.toFloat() / size
            for (x in 0 until size) {
                val fx = x.toFloat() / size
                var v = 0f
                var amp = 0.60f
                var norm = 0f
                for (o in grids.indices) {
                    v += amp * vnoise(lattices[o], grids[o], fx, fy)
                    norm += amp
                    amp *= 0.55f
                }
                v /= norm
                val c = (v.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    private fun vnoise(lat: FloatArray, g: Int, x: Float, y: Float): Float {
        val fx = x * g
        val fy = y * g
        val ix = fx.toInt()
        val iy = fy.toInt()
        val tx = fx - ix
        val ty = fy - iy

        val x0 = ((ix % g) + g) % g
        val y0 = ((iy % g) + g) % g
        val x1 = (x0 + 1) % g
        val y1 = (y0 + 1) % g

        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)

        val v00 = lat[y0 * g + x0]
        val v10 = lat[y0 * g + x1]
        val v01 = lat[y1 * g + x0]
        val v11 = lat[y1 * g + x1]

        val a = v00 + (v10 - v00) * sx
        val b = v01 + (v11 - v01) * sx
        return a + (b - a) * sy
    }

    companion object {
        /**
         * AGSL: blue-violet sphere with soft, always-flowing smoke.
         * Four texture fetches per pixel — cheap enough for 60fps on budget GPUs.
         */
        private const val ORB_AGSL = """
uniform float2 uCenter;
uniform float uRadius;
uniform float uTime;
uniform float uEnergy;
uniform shader uNoise;

float2 rot(float2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(p.x * c - p.y * s, p.x * s + p.y * c);
}

float sampleN(float2 p) {
    return float(uNoise.eval(p * 256.0).r);
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - uCenter) / uRadius;
    float d = length(uv);

    // Base sphere: saturated blue-violet with a soft lighter core.
    float g = clamp(uv.y * 0.5 + 0.5, 0.0, 1.0);
    float3 top = float3(0.345, 0.345, 0.945);
    float3 bot = float3(0.565, 0.600, 1.000);
    float3 col = mix(top, bot, smoothstep(0.0, 1.0, g));
    col += float3(0.055, 0.060, 0.095) * (1.0 - smoothstep(0.0, 0.95, d));

    // Time: slow always-on drift, gently faster while the AI speaks.
    float t = uTime * (1.0 + 0.9 * uEnergy);
    float2 p = uv * 1.05;

    // Two counter-moving warp fields + one detail field = billowing smoke.
    float w1 = sampleN(rot(p, t * 0.055) + float2(t * 0.030, -t * 0.040));
    float w2 = sampleN(rot(p * 2.05 + 3.7, -t * 0.075) + float2(-t * 0.034, t * 0.024));
    float2 q = p + (float2(w1, w2) - 0.5) * 0.62;
    float s = sampleN(rot(q, t * 0.040) * 1.25 + float2(t * 0.018, -t * 0.014));
    s = smoothstep(0.36, 0.78, s);

    float3 smokeCol = mix(float3(0.70, 0.76, 1.0), float3(1.0), smoothstep(0.15, 0.95, s));
    col = mix(col, smokeCol, (0.72 + 0.16 * uEnergy) * s);

    // Extra soft light patches for depth.
    float h = sampleN(rot(p * 0.62 + 11.3, t * 0.022) + float2(-t * 0.010, t * 0.014));
    h = smoothstep(0.58, 0.92, h);
    col += float3(0.15, 0.16, 0.19) * h * (0.45 + 0.35 * uEnergy);

    // Sphere shading + rim light.
    float shade = 1.0 - smoothstep(0.15, 1.05, d);
    col *= 0.86 + 0.14 * shade;
    col += float3(0.10, 0.11, 0.18) * smoothstep(0.86, 1.0, d);

    return half4(half3(min(col, float3(1.0))), 1.0);
}
"""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
