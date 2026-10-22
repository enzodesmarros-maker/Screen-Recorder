package com.haseeb.recorder.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/*
 * Stores the custom image chosen for the floating bubble as a small square PNG inside the app.
 */
object BubbleIconStore {

    private const val FILE_NAME = "bubble_icon.png"
    private const val TARGET_PX = 256

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun hasCustom(context: Context): Boolean = file(context).exists()

    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /*
     * Reads the picked image, crops it to a centered square, shrinks it and saves it. Run off the main thread.
     */
    fun saveFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return false

            val side = min(decoded.width, decoded.height)
            val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(cropped, TARGET_PX, TARGET_PX, true)
            FileOutputStream(file(context)).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }
}
