package com.aero.voice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Owns the MediaProjection INSIDE the foreground service (the Android 14+
 * requirement). Captures the real screen, converts to JPEG and pushes frames
 * through the exact same path as the camera: GeminiLiveClient.sendImage().
 */
object MediaProjectionHolder {

    private const val TAG = "AeroScreen"

    private val thread = HandlerThread("aero-screen-capture").also { it.start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var running = false
    private var frameNo = 0

    val isRunning: Boolean get() = running

    fun start(app: Context, code: Int, data: Intent) {
        stop()
        try {
            val metrics = app.resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val dpi = metrics.densityDpi

            val manager = app.getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(code, data)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val d = p.createVirtualDisplay(
                "AeroScreen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            projection = p
            reader = r
            display = d
            running = true

            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { running = false }
                }
            }, handler)

            handler.postDelayed(captureRunnable, 500L)
            Log.d(TAG, "media projection started ${w}x${h}@$dpi")
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
                    val scaled = scaleDown(bitmap, 640)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    frameNo++
                    val jpeg = baos.toByteArray()

                    // Same path as camera frames.
                    AeroState.liveClient?.sendImage(jpeg)

                    // On-device OCR as a secondary signal every 3rd frame.
                    if (Mirage.isReady && frameNo % 3 == 0) {
                        try {
                            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            val text = Mirage.describe(bmp)
                            bmp.recycle()
                            if (text.isNotBlank()) {
                                AeroState.liveClient?.sendContextText("Screen content: $text")
                            }
                        } catch (_: Exception) {
                        }
                    }
                    Log.d(TAG, "frame $frameNo sent ${jpeg.size} bytes")
                } catch (t: Throwable) {
                    Log.e(TAG, "frame capture failed", t)
                } finally {
                    image.close()
                }
            } else {
                Log.w(TAG, "no frame yet")
            }
            handler.postDelayed(this, 1000L)
        }
    }

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
        return if (paddedWidth == w) padded
        else Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
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
        Log.d(TAG, "media projection stopped")
    }
}
