package com.haseeb.recorder.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.min

/*
 * Re-encodes an existing recording to H.265 with a lower bitrate using the device's hardware encoder
 * (Media3 Transformer). Must be started and cancelled from the main thread.
 */
@OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context) {

    /*
     * Compression strength: target bitrate as a fraction of the original, and an optional cap
     * on the short side of the output (to also reduce resolution).
     */
    enum class Preset(val bitrateFactor: Float, val capShortSide: Int?) {
        LIGHT(0.60f, null),
        BALANCED(0.40f, null),
        MAXIMUM(0.25f, 720)
    }

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(output: File)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var outputFile: File? = null
    private var finished = false

    private val progressPoller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (finished) return
            callback?.let {
                if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    it.onProgress(progressHolder.progress.coerceIn(0, 100))
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private var callback: Callback? = null

    /*
     * Starts compressing the given video into a temporary file in the app cache.
     */
    fun start(source: Uri, sourceSizeBytes: Long, durationMs: Long, preset: Preset, callback: Callback) {
        this.callback = callback
        finished = false

        val dir = File(context.cacheDir, "compress").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "compress_${System.currentTimeMillis()}.mp4")
        outputFile = out

        val originalBps = if (durationMs > 0L && sourceSizeBytes > 0L) {
            sourceSizeBytes * 8_000L / durationMs
        } else {
            4_000_000L
        }
        val targetBps = (originalBps * preset.bitrateFactor).toLong().coerceIn(300_000L, 20_000_000L).toInt()

        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        readDisplaySize(source)?.let { (w, h) ->
            val cap = preset.capShortSide
            if (cap != null && min(w, h) > cap) {
                videoEffects.add(if (h >= w) Presentation.createForWidth(cap) else Presentation.createForHeight(cap))
            }
        }

        try {
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(targetBps).build())
                .build()

            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (finished) return
                        finish()
                        callback.onSuccess(out)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (finished) return
                        finish()
                        out.delete()
                        callback.onError(exportException.message ?: "Export error")
                    }
                })
                .build()
            transformer = t

            val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
            t.start(edited, out.absolutePath)
            mainHandler.post(progressPoller)
        } catch (e: Exception) {
            finish()
            out.delete()
            callback.onError(e.message ?: "Could not start compression")
        }
    }

    /*
     * Cancels a running compression and removes the temporary output.
     */
    fun cancel() {
        if (finished) return
        finish()
        try { transformer?.cancel() } catch (_: Exception) {}
        outputFile?.delete()
    }

    private fun finish() {
        finished = true
        mainHandler.removeCallbacks(progressPoller)
    }

    /*
     * Reads the video size as displayed (rotation applied), or null when unavailable.
     */
    private fun readDisplaySize(uri: Uri): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) Pair(h, w) else Pair(w, h)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
