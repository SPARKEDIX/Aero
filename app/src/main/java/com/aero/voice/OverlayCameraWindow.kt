package com.aero.voice

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors

/**
 * Small centered camera window — same CameraX tech as the in-app camera.
 * Curved rectangular display, front/back flip only (closing is handled by the
 * orb's camera button). Uses a custom RESUMED lifecycle so preview always starts.
 */
class OverlayCameraWindow(private val context: Context) {

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var previewView: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var lensBack = true
    private var shown = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameSentAt = 0L

    fun show() {
        if (shown) return
        val v = LayoutInflater.from(context).inflate(R.layout.overlay_camera_window, null)
        val preview = v.findViewById<PreviewView>(R.id.camPreview)
        v.findViewById<ImageButton>(R.id.camFlip).setOnClickListener {
            ClickSound.play(context)
            toggleFlip()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        view = v
        previewView = preview
        shown = true
        try {
            wm.addView(v, params)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay add failed", t)
            shown = false
            return
        }
        preview.post { bindCamera() }
    }

    private fun bindCamera() {
        val preview = previewView ?: return
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val selector = if (lensBack) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.unbindAll()
                    val useCase = Preview.Builder().build().also {
                        it.setSurfaceProvider(preview.surfaceProvider)
                    }
                    // Analysis → JPEG → send to live AI (same as screen frames).
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val now = System.currentTimeMillis()
                            if (now - lastFrameSentAt >= 2000L) {
                                val client = AeroState.liveClient
                                if (client != null) {
                                    val bmp = imageProxy.toBitmap()
                                    val w = bmp.width
                                    val h = bmp.height
                                    val scale = 320.0 / maxOf(w, h)
                                    val scaled = if (scale < 1.0) {
                                        android.graphics.Bitmap.createScaledBitmap(
                                            bmp,
                                            (w * scale).toInt().coerceAtLeast(1),
                                            (h * scale).toInt().coerceAtLeast(1),
                                            true
                                        )
                                    } else {
                                        bmp
                                    }
                                    val bos = java.io.ByteArrayOutputStream()
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, bos)
                                    client.sendImage(bos.toByteArray())
                                    lastFrameSentAt = now
                                    if (scaled !== bmp) scaled.recycle()
                                    bmp.recycle()
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                    cameraProvider.bindToLifecycle(owner, selector, useCase, analysis)
                } catch (t: Throwable) {
                    Log.w(TAG, "camera bind failed", t)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            Log.w(TAG, "camera provider failed", t)
        }
    }

    fun toggleFlip() {
        if (!shown) return
        lensBack = !lensBack
        bindCamera()
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        try {
            analysisExecutor.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        provider = null
        try {
            wm.removeView(view)
        } catch (_: Throwable) {
        }
        view = null
        previewView = null
    }

    companion object {
        private const val TAG = "AeroOverlayCam"
    }
}
