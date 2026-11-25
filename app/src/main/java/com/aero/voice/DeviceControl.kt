package com.aero.voice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Device-level controls: torch, bluetooth, brightness, screenshot, and
 * settings deep-links for things Android doesn't allow apps to toggle
 * (WiFi / mobile data).
 */
object DeviceControl {

    private const val TAG = "AeroDevice"

    // ---- Torch ----

    fun torch(ctx: Context, on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findFirstBackCamera(cm) ?: return "No back camera found."
            cm.setTorchMode(cameraId, on)
            if (on) "Torch is on." else "Torch is off."
        } catch (t: Throwable) {
            Log.e(TAG, "torch failed", t)
            "I couldn't control the torch. Give camera permission."
        }
    }

    private fun findFirstBackCamera(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    // ---- Bluetooth ----

    fun bluetooth(ctx: Context, on: Boolean): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return "This device has no bluetooth."
            val ok = if (on) adapter.enable() else adapter.disable()
            if (ok) (if (on) "Bluetooth is on." else "Bluetooth is off.")
            else "Couldn't change bluetooth state."
        } catch (t: Throwable) {
            Log.e(TAG, "bluetooth failed", t)
            "I couldn't control bluetooth."
        }
    }

    // ---- Brightness ----

    fun brightness(ctx: Context, level: Int): String {
        val clamped = level.coerceIn(0, 255)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.System.canWrite(ctx)
            ) {
                // Ask the user once for WRITE_SETTINGS.
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                return "I need 'Modify system settings' permission to change brightness. I've opened it for you."
            }
            val ok = Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped
            )
            if (ok) "Brightness set to $clamped." else "Couldn't change brightness."
        } catch (t: Throwable) {
            Log.e(TAG, "brightness failed", t)
            "I couldn't change brightness."
        }
    }

    // ---- Screenshot (uses the latest captured frame) ----

    fun screenshot(ctx: Context): String {
        val latest = MediaProjectionHolder.latestJpeg ?: run {
            // Not capturing yet — ask for screen share so we can capture.
            ScreenShareHelper.ensureScreenShare(ctx)
            return "I need screen access first — please allow it, then ask me again."
        }
        return try {
            val bmp = BitmapFactory.decodeByteArray(latest, 0, latest.size)
                ?: return "Couldn't decode the screen."
            val saved = MediaStoreHelper.savePng(ctx, bmp)
            bmp.recycle()
            if (saved != null) "Screenshot saved to Pictures/Aero."
            else "Couldn't save the screenshot."
        } catch (t: Throwable) {
            Log.e(TAG, "screenshot failed", t)
            "Couldn't save screenshot."
        }
    }

    // ---- Volume ----

    fun volume(ctx: Context, percent: Int): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = (max * percent.coerceIn(0, 100) / 100).coerceIn(0, max)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            "Volume set to $percent%."
        } catch (t: Throwable) {
            Log.e(TAG, "volume failed", t)
            "I couldn't change the volume."
        }
    }

    fun volumeUp(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_RAISE,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            "Volume up."
        } catch (t: Throwable) {
            "I couldn't raise the volume."
        }
    }

    fun volumeDown(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            "Volume down."
        } catch (t: Throwable) {
            "I couldn't lower the volume."
        }
    }

    // ---- WiFi / mobile data: not toggleable by third-party apps ----

    fun openWifiSettings(ctx: Context): String {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return "Opening WiFi settings."
        } catch (_: Throwable) {
            return "Couldn't open WiFi settings."
        }
    }

    fun openDataSettings(ctx: Context): String {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_DATA_USAGE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return "Opening data settings."
        } catch (_: Throwable) {
            return "Couldn't open data settings."
        }
    }
}
