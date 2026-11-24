package com.aero.voice

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * On-device Mirage vision/OCR engine (no API key, no cloud).
 * The 22 MB .cact weights are shipped in assets and loaded by the native
 * Cactus runtime through JNI on first use.
 */
object Mirage {

    init {
        System.loadLibrary("aero_mirage")
    }

    @Volatile
    private var loaded = false
    private val lock = Any()

    fun load(ctx: Context): Boolean = synchronized(lock) {
        if (loaded) return true
        try {
            val file = File(ctx.filesDir, "mirage.cact")
            if (!file.exists() || file.length() < 1_000_000L) {
                ctx.assets.open("mirage.cact").use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
            }
            loaded = nativeLoad(file.absolutePath) >= 0
        } catch (t: Throwable) {
            android.util.Log.e("Mirage", "load failed", t)
            loaded = false
        }
        loaded
    }

    val isReady: Boolean get() = loaded

    /** Runs OCR / scene description on a bitmap. Call from a background thread. */
    fun describe(source: Bitmap): String {
        if (!loaded) return ""
        val bitmap = if (maxOf(source.width, source.height) > 512) {
            val scale = 512f / maxOf(source.width, source.height)
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val rgb = ByteArray(w * h * 3)
        for (i in argb.indices) {
            val c = argb[i]
            rgb[i * 3] = ((c shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (c and 0xFF).toByte()
        }

        return nativeDescribe(rgb, w, h, "{\"ocr\":true}", "en", "")
    }

    private external fun nativeLoad(path: String): Int

    private external fun nativeDescribe(
        rgb: ByteArray,
        width: Int,
        height: Int,
        options: String,
        language: String,
        keywords: String
    ): String
}
