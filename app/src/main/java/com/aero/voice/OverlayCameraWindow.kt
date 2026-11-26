package com.aero.voice

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.content.ContextCompat

/**
 * Small floating camera window on top of any screen (no app open).
 * Shows live camera preview with a front/back flip button and a close button.
 */
class OverlayCameraWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var texture: TextureView? = null
    private var engine: Engine? = null
    private var shown = false

    fun show() {
        if (shown) return
        val v = LayoutInflater.from(context).inflate(R.layout.overlay_camera_window, null)
        val textureView = v.findViewById<TextureView>(R.id.camTexture)
        val flip = v.findViewById<ImageButton>(R.id.camFlip)
        val close = v.findViewById<ImageButton>(R.id.camClose)

        flip.setOnClickListener { engine?.flip() }
        close.setOnClickListener { dismiss() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 160
        }
        view = v
        texture = textureView
        shown = true

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                engine = Engine(context, textureView).also { it.start(0) }
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        try {
            wm.addView(v, params)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay window add failed", t)
            shown = false
        }
    }

    fun toggleFlip() {
        engine?.flip()
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        try {
            wm.removeView(view)
        } catch (_: Throwable) {
        }
        view = null
    }

    private class Engine(
        private val context: Context,
        private val texture: TextureView
    ) {
        private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        private var thread: HandlerThread? = null
        private var handler: Handler? = null
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var currentId = 0

        fun start(id: Int) {
            currentId = id
            thread = HandlerThread("aero-cam").also { it.start() }
            handler = Handler(thread!!.looper)
            open()
        }

        fun flip() {
            val ids = try {
                cm.cameraIdList
            } catch (_: Exception) {
                emptyArray()
            }
            if (ids.isEmpty()) return
            val next = (currentId + 1) % ids.size
            close()
            currentId = next
            open()
        }

        private fun open() {
            val camId = try {
                cm.cameraIdList[currentId]
            } catch (_: Exception) {
                return
            }
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "camera permission missing")
                return
            }
            try {
                cm.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        startPreview(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                    }
                }, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "openCamera failed", t)
            }
        }

        private fun startPreview(camera: CameraDevice) {
            val st = texture.surfaceTexture ?: return
            st.setDefaultBufferSize(640, 480)
            val surface = Surface(st)
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            try {
                camera.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            try {
                                s.setRepeatingRequest(req, null, handler)
                            } catch (_: Throwable) {
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.w(TAG, "camera configure failed")
                        }
                    },
                    handler
                )
            } catch (t: Throwable) {
                Log.w(TAG, "createCaptureSession failed", t)
            }
        }

        fun close() {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            session = null
            try {
                device?.close()
            } catch (_: Throwable) {
            }
            device = null
            try {
                thread?.quitSafely()
            } catch (_: Throwable) {
            }
            thread = null
            handler = null
        }
    }

    companion object {
        private const val TAG = "AeroOverlayCam"
    }
}
