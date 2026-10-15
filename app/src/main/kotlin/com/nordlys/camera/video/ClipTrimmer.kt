package com.nordlys.camera.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Cuts a stretch out of a clip without re-encoding (§9): samples are copied
 * as they are, so the start snaps back to the key frame at or before the
 * requested start. The source is never touched; the cut is a new library item.
 */
object ClipTrimmer {
    private const val TAG = "NordlysTrim"

    suspend fun trim(context: Context, src: Uri, startUs: Long, endUs: Long): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Nordlys_trim_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Nordlys")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val ok = runCatching {
            resolver.openFileDescriptor(out, "rw")!!.use { pfd -> copy(context, src, startUs, endUs, MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)) }
        }.onFailure { Log.w(TAG, "trim failed", it) }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(out, null, null) }
            return@withContext null
        }
        resolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        out
    }

    private fun copy(context: Context, src: Uri, startUs: Long, endUs: Long, muxer: MediaMuxer): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, src, null)
            val map = HashMap<Int, Int>()
            var videoTrack = -1
            var maxInput = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.mime()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/")) { if (videoTrack >= 0) continue; videoTrack = i }
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                ex.selectTrack(i)
                map[i] = muxer.addTrack(f)
            }
            if (videoTrack < 0) return false
            muxer.setOrientationHint(rotationOf(context, src))
            muxer.start()

            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // the video key frame decides where the cut truly begins
            var base = -1L
            val buf = ByteBuffer.allocate(maxInput)
            val info = MediaCodec.BufferInfo()
            var written = 0
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val t = ex.sampleTime
                if (track == videoTrack && base < 0) base = t
                if (t > endUs) {
                    if (track == videoTrack) break
                } else if (base >= 0 && t >= base) {
                    val size = ex.readSampleData(buf, 0)
                    if (size > 0) {
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, t - base, flags)
                        muxer.writeSampleData(map.getValue(track), buf, info)
                        written++
                    }
                }
                if (!ex.advance()) break
            }
            muxer.stop()
            return written > 0
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }

    private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME) ?: ""

    private fun rotationOf(context: Context, src: Uri): Int = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, src)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally { r.release() }
    }.getOrDefault(0)
}
