package com.aero.voice

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/** Saves bitmaps to the public Pictures/Aero folder via MediaStore. */
object MediaStoreHelper {

    /** Returns the content Uri if saved, else null. */
    fun savePng(ctx: Context, bmp: Bitmap): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "aero_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aero")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
            uri
        } catch (t: Throwable) {
            null
        }
    }
}
