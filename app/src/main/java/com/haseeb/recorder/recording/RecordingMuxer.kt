package com.haseeb.recorder.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer

/*
 * Minimal muxer contract used by the recording service, so the same encoder code can write
 * either a normal MP4 or a crash-safe MKV file.
 */
interface RecordingMuxer {
    fun addTrack(format: MediaFormat): Int
    fun start()
    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun stop()
    fun release()
}

/*
 * Normal recording: delegates to Android's MediaMuxer (MP4).
 */
class Mp4RecordingMuxer private constructor(private val muxer: MediaMuxer) : RecordingMuxer {

    constructor(fd: ParcelFileDescriptor) :
        this(MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))

    constructor(path: String) :
        this(MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))

    override fun addTrack(format: MediaFormat): Int = muxer.addTrack(format)
    override fun start() = muxer.start()
    override fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        muxer.writeSampleData(trackIndex, buffer, info)
    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

/*
 * Folder (inside the app's own storage) where "staged" recordings are kept until converted to MP4.
 */
object RawRecordings {

    private const val DIR_NAME = "raw-recordings"
    const val EXTENSION = "mkv"

    /* Path of the file being recorded right now, so the list can protect it. */
    @Volatile
    var activePath: String? = null

    fun dir(context: android.content.Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    /*
     * Creates an empty file for a new staged recording, or returns null if the folder is not writable.
     */
    fun createFile(context: android.content.Context, baseName: String): File? {
        return try {
            val file = File(dir(context), "$baseName.$EXTENSION")
            if (file.createNewFile() || file.exists()) file else null
        } catch (_: Exception) {
            null
        }
    }

    fun list(context: android.content.Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals(EXTENSION, ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
