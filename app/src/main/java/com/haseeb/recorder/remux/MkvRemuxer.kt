package com.haseeb.recorder.remux

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.util.AppLog
import java.io.File
import java.nio.ByteBuffer

/*
 * Converts a staged .mkv recording into a normal MP4 without re-encoding: the already compressed
 * video/audio data is copied into a new container, so it is fast and keeps the original quality.
 * Blocking: run it on a background thread.
 */
class MkvRemuxer(private val context: Context) {

    interface Callback {
        /* percent is -1 when the total duration is unknown; etaSeconds is -1 when not yet estimable. */
        fun onProgress(percent: Int, etaSeconds: Long)
    }

    private class Output(
        val fd: ParcelFileDescriptor?,
        val path: String?,
        val mediaStoreUri: Uri?,
        val documentUri: Uri?
    )

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /*
     * Returns the saved MP4's display name, or null when cancelled. Throws on failure.
     */
    fun run(input: File, mp4Name: String, callback: Callback): String? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var output: Output? = null
        var success = false
        try {
            extractor.setDataSource(input.absolutePath)

            val indexMap = HashMap<Int, Int>()
            var durationUs = 0L
            val formats = ArrayList<Pair<Int, MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                formats.add(Pair(i, format))
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            if (formats.isEmpty()) throw IllegalStateException("No readable video or audio tracks")
            AppLog.i("Remux", "Converting ${input.name}: tracks=${formats.size} duration=${durationUs / 1000}ms size=${input.length() / 1024} KB")

            output = openOutput(mp4Name)
            muxer = if (output.fd != null) {
                MediaMuxer(output.fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(output.path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            for ((trackIndex, format) in formats) {
                indexMap[trackIndex] = muxer.addTrack(format)
                extractor.selectTrack(trackIndex)
            }
            muxer.start()

            var buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val startedAt = SystemClock.elapsedRealtime()
            var lastReport = 0L

            while (!cancelled) {
                var size: Int
                try {
                    size = extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    buffer = ByteBuffer.allocate(buffer.capacity() * 4)
                    size = extractor.readSampleData(buffer, 0)
                }
                if (size < 0) break

                val trackIndex = indexMap[extractor.sampleTrackIndex]
                if (trackIndex != null) {
                    info.set(
                        0,
                        size,
                        extractor.sampleTime.coerceAtLeast(0),
                        if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    )
                    muxer.writeSampleData(trackIndex, buffer, info)

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= 500) {
                        lastReport = now
                        if (durationUs > 0) {
                            val percent = ((info.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 99)
                            val elapsed = now - startedAt
                            val eta = if (percent >= 2) (elapsed * (100 - percent) / percent) / 1000 else -1L
                            callback.onProgress(percent, eta)
                        } else {
                            callback.onProgress(-1, -1)
                        }
                    }
                }
                extractor.advance()
            }

            if (cancelled) return null

            muxer.stop()
            success = true
            callback.onProgress(100, 0)
            return mp4Name
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (output != null) finishOutput(output, success)
        }
    }

    // ---------------------------------------------------------------- output handling

    /*
     * Opens the destination like a normal recording: custom folder if the user chose one,
     * otherwise DCIM/ScreenCats.
     */
    private fun openOutput(displayName: String): Output {
        val customUri = ConfigManager(context).customStorageUri
        if (!customUri.isNullOrEmpty()) {
            try {
                val dir = DocumentFile.fromTreeUri(context, Uri.parse(customUri))
                if (dir != null && dir.canWrite()) {
                    val target = dir.createFile("video/mp4", displayName)
                    if (target != null) {
                        val fd = context.contentResolver.openFileDescriptor(target.uri, "rw")
                        if (fd != null) return Output(fd, null, null, target.uri)
                    }
                }
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenCats")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the output file")
            val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Could not open the output file")
            return Output(fd, null, uri, null)
        }

        val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(movies, "ScreenCats").apply { mkdirs() }
        return Output(null, File(dir, displayName).absolutePath, null, null)
    }

    private fun finishOutput(output: Output, success: Boolean) {
        try { output.fd?.close() } catch (_: Exception) {}
        val resolver = context.contentResolver
        if (success) {
            if (output.mediaStoreUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    resolver.update(output.mediaStoreUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (_: Exception) {}
            }
        } else {
            // Cancelled or failed: do not leave a broken half-written video behind.
            try { output.mediaStoreUri?.let { resolver.delete(it, null, null) } } catch (_: Exception) {}
            try { output.documentUri?.let { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, it)?.delete() } } catch (_: Exception) {}
            try { output.path?.let { File(it).delete() } } catch (_: Exception) {}
        }
    }
}
