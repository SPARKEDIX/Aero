package com.aero.voice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Clean, self-contained screen streaming for Gemini Live.
 *
 * Owns the MediaProjection, a dedicated capture thread, and the JPEG frame
 * pump. The caller only supplies [onFrame] (feed to the Live WebSocket) and
 * [onStopped] (cleanup UI). No main-thread work, no black frames.
 */
class ScreenShareManager(private val app: Context) {

    private val thread = HandlerThread("aero-screen-capture").also { it.start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var running = false
    private var frameNo = 0

    var onFrame: ((jpeg: ByteArray, frameNo: Int) -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    val isRunning: Boolean get() = running

    fun start(code: Int, data: Intent) {
        stop()
        try {
            val manager = app.getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(code, data)
            val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
            val d = p.createVirtualDisplay(
                "AeroScreen", WIDTH, HEIGHT, DENSITY,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            projection = p
            reader = r
            display = d
            running = true

            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post {
                        running = false
                        onStopped?.invoke()
                    }
                }
            }, handler)

            handler.postDelayed(captureRunnable, FIRST_FRAME_DELAY_MS)
            Log.d(TAG, "screen stream started")
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            running = false
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val image = reader?.acquireLatestImage()
            if (image != null) {
                try {
                    val bitmap = toBitmap(image)
                    val scaled = scaleDown(bitmap, MAX_SIDE)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    frameNo++
                    onFrame?.invoke(baos.toByteArray(), frameNo)
                } catch (t: Throwable) {
                    Log.e(TAG, "frame capture failed", t)
                } finally {
                    image.close()
                }
            }
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    /** Canonical MediaProjection RGBA -> Bitmap conversion with row padding. */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val buffer = plane.buffer.apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val paddedWidth = w + (rowStride - pixelStride * w) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == w) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            cropped
        }
    }

    private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
        if (maxOf(source.width, source.height) <= maxSide) return source
        val scale = maxSide.toFloat() / maxOf(source.width, source.height)
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun stop() {
        running = false
        handler.removeCallbacks(captureRunnable)
        try {
            display?.release()
            projection?.stop()
            reader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stop cleanup", t)
        }
        display = null
        projection = null
        reader = null
        Log.d(TAG, "screen stream stopped")
    }

    companion object {
        private const val TAG = "AeroScreen"
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val DENSITY = 320
        private const val MAX_SIDE = 640
        private const val FIRST_FRAME_DELAY_MS = 500L
        private const val FRAME_INTERVAL_MS = 1000L
    }
}
