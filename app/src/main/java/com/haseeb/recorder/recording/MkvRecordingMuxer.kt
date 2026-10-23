package com.haseeb.recorder.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/*
 * Small Matroska (.mkv) writer for H.264/H.265 video and AAC audio.
 * Data is written cluster by cluster (about one second each) as the recording goes, with no index
 * needed, so if the phone dies or the app is killed the file is still readable up to the last cluster.
 * On a clean stop it also writes the final size, duration and a seek index.
 */
class MkvRecordingMuxer(private val file: File) : RecordingMuxer {

    private class Track(val number: Int, val format: MediaFormat, val isVideo: Boolean, val mime: String)

    private companion object {
        const val CLUSTER_MIN_MS = 1000L
        const val CLUSTER_MAX_MS = 20_000L
        const val SYNC_EVERY_CLUSTERS = 5
        const val MIME_AVC = "video/avc"
        const val MIME_HEVC = "video/hevc"
        const val MIME_AAC = "audio/mp4a-latm"
    }

    private val tracks = ArrayList<Track>()
    private var raf: RandomAccessFile? = null
    private var started = false

    private var segmentSizePos = 0L
    private var segmentDataStart = 0L
    private var durationValuePos = 0L

    private var baseUs = Long.MIN_VALUE
    private var lastTimeMs = 0L

    private val cluster = ByteArrayOutputStream(512 * 1024)
    private var clusterOpen = false
    private var clusterStartMs = 0L
    private var clusterCueable = false
    private var clustersWritten = 0
    private val cuePoints = ArrayList<LongArray>()

    // ---------------------------------------------------------------- RecordingMuxer

    override fun addTrack(format: MediaFormat): Int {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Track without mime")
        val isVideo = mime == MIME_AVC || mime == MIME_HEVC
        if (!isVideo && mime != MIME_AAC) throw IllegalArgumentException("Unsupported track type for MKV: $mime")
        tracks.add(Track(tracks.size + 1, format, isVideo, mime))
        return tracks.size - 1
    }

    override fun start() {
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        raf = out
        writeHeader(out)
        started = true
    }

    override fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || info.size <= 0) return
        val track = tracks[trackIndex]

        if (baseUs == Long.MIN_VALUE) baseUs = info.presentationTimeUs
        val timeMs = ((info.presentationTimeUs - baseUs + 500) / 1000).coerceAtLeast(0)
        if (timeMs > lastTimeMs) lastTimeMs = timeMs

        val isKey = track.isVideo && (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val raw = ByteArray(info.size)
        val view = buffer.duplicate()
        view.position(info.offset)
        view.limit(info.offset + info.size)
        view.get(raw)
        val payload = if (track.isVideo) annexBToLengthPrefixed(raw) else raw

        val startNew = !clusterOpen ||
            (track.isVideo && isKey && timeMs - clusterStartMs >= CLUSTER_MIN_MS) ||
            (timeMs - clusterStartMs > CLUSTER_MAX_MS)
        if (startNew) {
            closeCluster()
            openCluster(timeMs, cueable = isKey)
        }

        val relative = (timeMs - clusterStartMs).coerceIn(-32768L, 32767L).toInt()
        val flags = if (!track.isVideo || isKey) 0x80 else 0x00
        val blockSize = 1 + 2 + 1 + payload.size
        cluster.write(idBytes(0xA3))
        cluster.write(sizeVint(blockSize.toLong()))
        cluster.write(0x80 or track.number)
        cluster.write((relative shr 8) and 0xFF)
        cluster.write(relative and 0xFF)
        cluster.write(flags)
        cluster.write(payload)
    }

    override fun stop() {
        val out = raf ?: return
        if (started) {
            closeCluster()
            writeCues(out)
            finalizeHeader(out)
            try { out.fd.sync() } catch (_: Exception) {}
        }
        started = false
    }

    override fun release() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        started = false
    }

    // ---------------------------------------------------------------- file structure

    private fun writeHeader(out: RandomAccessFile) {
        // EBML header
        val ebml = ByteArrayOutputStream()
        ebml.write(uintEl(0x4286, 1))          // EBMLVersion
        ebml.write(uintEl(0x42F7, 1))          // EBMLReadVersion
        ebml.write(uintEl(0x42F2, 4))          // EBMLMaxIDLength
        ebml.write(uintEl(0x42F3, 8))          // EBMLMaxSizeLength
        ebml.write(strEl(0x4282, "matroska"))  // DocType
        ebml.write(uintEl(0x4287, 4))          // DocTypeVersion
        ebml.write(uintEl(0x4285, 2))          // DocTypeReadVersion
        out.write(element(0x1A45DFA3, ebml.toByteArray()))

        // Segment with a fixed 8-byte size field (unknown now, patched on a clean stop)
        out.write(idBytes(0x18538067))
        segmentSizePos = out.filePointer
        out.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1))
        segmentDataStart = out.filePointer

        // Info
        val before = ByteArrayOutputStream()
        before.write(uintEl(0x2AD7B1, 1_000_000))       // TimecodeScale: 1 ms
        before.write(strEl(0x4D80, "ScreenCats"))        // MuxingApp
        before.write(strEl(0x5741, "ScreenCats"))        // WritingApp
        val beforeBytes = before.toByteArray()
        val durationElement = element(0x4489, ByteArray(8))
        val infoPayload = beforeBytes + durationElement
        val infoStart = out.filePointer
        val infoIdAndSize = idBytes(0x1549A966) + sizeVint(infoPayload.size.toLong())
        durationValuePos = infoStart + infoIdAndSize.size + beforeBytes.size + 3
        out.write(infoIdAndSize)
        out.write(infoPayload)

        // Tracks
        val entries = ByteArrayOutputStream()
        for (track in tracks) entries.write(element(0xAE, trackEntryPayload(track)))
        out.write(element(0x1654AE6B, entries.toByteArray()))
    }

    private fun trackEntryPayload(track: Track): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(uintEl(0xD7, track.number.toLong()))            // TrackNumber
        p.write(uintEl(0x73C5, 1000L + track.number))           // TrackUID
        p.write(uintEl(0x83, if (track.isVideo) 1 else 2))      // TrackType
        p.write(uintEl(0x9C, 0))                                // FlagLacing
        if (track.isVideo) {
            p.write(strEl(0x86, if (track.mime == MIME_AVC) "V_MPEG4/ISO/AVC" else "V_MPEGH/ISO/HEVC"))
            val priv = if (track.mime == MIME_AVC) buildAvcC(track.format) else buildHvcC(track.format)
            if (priv != null) p.write(element(0x63A2, priv))
            val video = ByteArrayOutputStream()
            video.write(uintEl(0xB0, track.format.getInteger(MediaFormat.KEY_WIDTH).toLong()))
            video.write(uintEl(0xBA, track.format.getInteger(MediaFormat.KEY_HEIGHT).toLong()))
            p.write(element(0xE0, video.toByteArray()))
        } else {
            p.write(strEl(0x86, "A_AAC"))
            csd(track.format, "csd-0")?.let { p.write(element(0x63A2, it)) }
            val audio = ByteArrayOutputStream()
            val rate = track.format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble()
            val channels = track.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toLong()
            audio.write(element(0xB5, ByteBuffer.allocate(8).putDouble(rate).array()))
            audio.write(uintEl(0x9F, channels))
            p.write(element(0xE1, audio.toByteArray()))
        }
        return p.toByteArray()
    }

    private fun openCluster(timeMs: Long, cueable: Boolean) {
        cluster.reset()
        clusterStartMs = timeMs
        clusterCueable = cueable
        cluster.write(uintEl(0xE7, timeMs))   // cluster Timecode
        clusterOpen = true
    }

    private fun closeCluster() {
        if (!clusterOpen) return
        val out = raf ?: return
        val data = cluster.toByteArray()
        val position = out.filePointer
        out.write(idBytes(0x1F43B675))
        out.write(sizeVint(data.size.toLong()))
        out.write(data)
        if (clusterCueable) cuePoints.add(longArrayOf(clusterStartMs, position - segmentDataStart))
        clusterOpen = false
        clustersWritten++
        if (clustersWritten % SYNC_EVERY_CLUSTERS == 0) {
            try { out.fd.sync() } catch (_: Exception) {}
        }
    }

    private fun writeCues(out: RandomAccessFile) {
        val videoTrack = tracks.firstOrNull { it.isVideo } ?: return
        if (cuePoints.isEmpty()) return
        val all = ByteArrayOutputStream()
        for (cue in cuePoints) {
            val positions = element(
                0xB7,
                uintEl(0xF7, videoTrack.number.toLong()) + uintEl(0xF1, cue[1])
            )
            all.write(element(0xBB, uintEl(0xB3, cue[0]) + positions))
        }
        out.write(element(0x1C53BB6B, all.toByteArray()))
    }

    /*
     * Fills in the real segment size and duration now that the file is complete.
     */
    private fun finalizeHeader(out: RandomAccessFile) {
        val end = out.length()
        val size = end - segmentDataStart
        out.seek(segmentSizePos)
        val sizeBytes = ByteArray(8)
        sizeBytes[0] = 0x01
        for (i in 1..7) sizeBytes[i] = ((size shr (8 * (7 - i))) and 0xFF).toByte()
        out.write(sizeBytes)

        out.seek(durationValuePos)
        out.write(ByteBuffer.allocate(8).putDouble(lastTimeMs.toDouble()).array())
        out.seek(end)
    }

    // ---------------------------------------------------------------- codec private data

    private fun csd(format: MediaFormat, key: String): ByteArray? {
        val buf = format.getByteBuffer(key) ?: return null
        val copy = buf.duplicate()
        copy.rewind()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    /* Splits an Annex-B stream (00 00 01 / 00 00 00 01 start codes) into NAL units without start codes. */
    private fun splitAnnexB(d: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val nals = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val start = starts[k]
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else d.size
            while (end > start && d[end - 1].toInt() == 0) end--
            if (end > start) nals.add(d.copyOfRange(start, end))
        }
        return nals
    }

    /* Converts one encoded frame from Annex-B to the 4-byte-length-prefixed form Matroska uses. */
    private fun annexBToLengthPrefixed(frame: ByteArray): ByteArray {
        val nals = splitAnnexB(frame)
        if (nals.isEmpty()) return frame
        var total = 0
        for (n in nals) total += 4 + n.size
        val out = ByteBuffer.allocate(total)
        for (n in nals) {
            out.putInt(n.size)
            out.put(n)
        }
        return out.array()
    }

    private fun buildAvcC(format: MediaFormat): ByteArray? {
        val all = ByteArrayOutputStream()
        csd(format, "csd-0")?.let { all.write(it) }
        csd(format, "csd-1")?.let { all.write(it) }
        val nals = splitAnnexB(all.toByteArray())
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } ?: return null
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 } ?: return null
        if (sps.size < 4) return null
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write(sps[1].toInt())
        out.write(sps[2].toInt())
        out.write(sps[3].toInt())
        out.write(0xFF)                      // 4-byte NAL lengths
        out.write(0xE1)                      // one SPS
        out.write(sps.size shr 8); out.write(sps.size and 0xFF); out.write(sps)
        out.write(1)                         // one PPS
        out.write(pps.size shr 8); out.write(pps.size and 0xFF); out.write(pps)
        return out.toByteArray()
    }

    private fun buildHvcC(format: MediaFormat): ByteArray? {
        val nals = splitAnnexB(csd(format, "csd-0") ?: return null)
        fun typeOf(n: ByteArray) = (n[0].toInt() shr 1) and 0x3F
        val vps = nals.firstOrNull { it.size > 2 && typeOf(it) == 32 }
        val sps = nals.firstOrNull { it.size > 2 && typeOf(it) == 33 } ?: return null
        val pps = nals.firstOrNull { it.size > 2 && typeOf(it) == 34 }

        val rbsp = unescape(sps, 2, minOf(sps.size, 2 + 48))
        if (rbsp.size < 13) return null
        val maxSubLayersMinus1 = (rbsp[0].toInt() shr 1) and 0x07
        val nested = rbsp[0].toInt() and 0x01

        val out = ByteArrayOutputStream()
        out.write(1)                                   // configurationVersion
        out.write(rbsp[1].toInt())                     // profile space, tier, profile idc
        out.write(rbsp, 2, 4)                          // profile compatibility flags
        out.write(rbsp, 6, 6)                          // constraint indicator flags
        out.write(rbsp[12].toInt())                    // level idc
        out.write(0xF0); out.write(0x00)               // min spatial segmentation
        out.write(0xFC)                                // parallelism type
        out.write(0xFD)                                // chroma format 4:2:0
        out.write(0xF8)                                // luma bit depth 8
        out.write(0xF8)                                // chroma bit depth 8
        out.write(0); out.write(0)                     // average frame rate
        out.write(((maxSubLayersMinus1 + 1) shl 3) or (nested shl 2) or 3)

        val arrays = listOfNotNull(
            vps?.let { Pair(32, it) },
            Pair(33, sps),
            pps?.let { Pair(34, it) }
        )
        out.write(arrays.size)
        for ((type, nal) in arrays) {
            out.write(0x80 or type)
            out.write(0); out.write(1)                 // one NAL of this type
            out.write(nal.size shr 8); out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /* Removes emulation-prevention bytes (00 00 03) so header fields can be read directly. */
    private fun unescape(src: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (i in from until to) {
            val b = src[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- EBML helpers

    private fun idBytes(id: Int): ByteArray {
        val n = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(n) { i -> ((id shr (8 * (n - 1 - i))) and 0xFF).toByte() }
    }

    private fun sizeVint(size: Long): ByteArray {
        var n = 1
        while (n < 8 && size >= (1L shl (7 * n)) - 1) n++
        val marked = size or (1L shl (7 * n))
        return ByteArray(n) { i -> ((marked shr (8 * (n - 1 - i))) and 0xFF).toByte() }
    }

    private fun uintBytes(v: Long): ByteArray {
        var n = 1
        while (n < 8 && (v ushr (8 * n)) != 0L) n++
        return ByteArray(n) { i -> ((v shr (8 * (n - 1 - i))) and 0xFF).toByte() }
    }

    private fun element(id: Int, payload: ByteArray): ByteArray = idBytes(id) + sizeVint(payload.size.toLong()) + payload
    private fun uintEl(id: Int, v: Long): ByteArray = element(id, uintBytes(v))
    private fun uintEl(id: Int, v: Int): ByteArray = element(id, uintBytes(v.toLong()))
    private fun strEl(id: Int, s: String): ByteArray = element(id, s.toByteArray(Charsets.US_ASCII))
}
