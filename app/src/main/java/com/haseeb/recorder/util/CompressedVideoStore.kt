package com.haseeb.recorder.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.haseeb.recorder.data.ConfigManager
import java.io.File
import java.io.IOException

/*
 * Saves compressed copies of recordings where the recordings list can find them.
 */
object CompressedVideoStore {

    /*
     * Builds a name the recordings list recognizes (it only lists files starting with ScreenRecord_).
     */
    fun buildName(originalName: String): String {
        val base = originalName.removeSuffix(".mp4")
        val prefixed = if (base.startsWith("ScreenRecord_")) base else "ScreenRecord_$base"
        return "${prefixed}_compressed.mp4"
    }

    /*
     * Copies the file to the custom storage folder when set, otherwise to DCIM/ScreenRecorder.
     * Must be called from a background thread.
     */
    fun save(context: Context, originalName: String, file: File): Boolean {
        val displayName = buildName(originalName)

        val customUri = ConfigManager(context).customStorageUri
        if (!customUri.isNullOrEmpty()) {
            try {
                val dir = DocumentFile.fromTreeUri(context, Uri.parse(customUri))
                if (dir != null && dir.canWrite()) {
                    val target = dir.createFile("video/mp4", displayName)
                    if (target != null) {
                        val out = context.contentResolver.openOutputStream(target.uri)
                        if (out != null) {
                            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                            return true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("No output stream")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            true
        } catch (_: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }
}
