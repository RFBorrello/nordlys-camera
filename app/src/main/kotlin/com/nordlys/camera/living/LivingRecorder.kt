package com.nordlys.camera.living

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer

/**
 * Living-image rolling buffer (docs/LUMIA_PARITY.md §8): viewfinder frames are
 * encoded to H.264 as they arrive and the encoded stream is kept for the last
 * few seconds. [cut] turns the stretch ending just after a capture into an MP4.
 *
 * [feed] runs on the camera thread; the encoder output is drained on its own
 * thread so a slow muxer never stalls the preview.
 */
class LivingRecorder(private val width: Int, private val height: Int) {

    companion object {
        private const val TAG = "NordlysLiving"
        const val PRE_US = 1_500_000L      // footage kept before the capture starts
        const val POST_US = 150_000L       // and after it, like the reference's stop delay
        private const val KEEP_US = 3_000_000L
        private const val MIN_CLIP_US = 500_000L
        private const val FPS = 30
    }

    private class Sample(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var codec: MediaCodec? = null
    private var format: MediaFormat? = null
    @Volatile private var running = false
    @Volatile private var lastFedUs = 0L
    private var drainThread: Thread? = null
    private var lastInputUs = 0L
    private var lastSyncAskUs = 0L
    @Volatile private var fed = 0
    @Volatile private var dropped = 0

    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * FPS * 0.18f).toInt().coerceIn(2_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            running = true
            drainThread = Thread({ drainLoop(c) }, "nordlys-living").apply { start() }
        }.onFailure { Log.w(TAG, "encoder unavailable", it); release() }.isSuccess
    }

    /** Copies one YUV frame into the encoder. The caller still owns and closes [image]. */
    fun feed(image: Image) {
        val c = codec ?: return
        if (!running) return
        val ptsUs = image.timestamp / 1000
        if (ptsUs <= lastInputUs) return
        runCatching {
            // some encoders ignore the key-frame interval; ask explicitly so a cut can start near where it should
            if (ptsUs - lastSyncAskUs >= 500_000L) {
                c.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                lastSyncAskUs = ptsUs
            }
            val index = c.dequeueInputBuffer(0)
            if (index < 0) { dropped++; return } // encoder busy: drop the frame, the buffer just gets a gap
            val dst = c.getInputImage(index)
            if (dst == null) { c.queueInputBuffer(index, 0, 0, ptsUs, 0); return }
            copyYuv(image, dst)
            c.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
            lastInputUs = ptsUs
            lastFedUs = ptsUs
            fed++
        }.onFailure { Log.w(TAG, "feed failed", it) }
    }

    /** Timestamp (µs, sensor clock) of the newest frame handed to the encoder. */
    val latestUs: Long get() = lastFedUs

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try { c.dequeueOutputBuffer(info, 20_000) } catch (e: Throwable) { break }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(samples) { format = c.outputFormat }
                index >= 0 -> {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                        val s = Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        synchronized(samples) {
                            samples.addLast(s)
                            trim(s.ptsUs - KEEP_US)
                        }
                    }
                    runCatching { c.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    /** Drops samples older than [cutoffUs], but always keeps a key frame at the head. */
    private fun trim(cutoffUs: Long) {
        val keep = samples.indices.lastOrNull { samples[it].key && samples[it].ptsUs <= cutoffUs } ?: return
        repeat(keep) { samples.removeFirst() }
    }

    /**
     * Writes the clip that ends [POST_US] after [captureUs] (sensor clock, µs) to
     * [out], waiting for the encoder to catch up. False = too little footage.
     */
    suspend fun cut(captureUs: Long, out: File, rotation: Int): Boolean {
        val endUs = captureUs + POST_US
        withTimeoutOrNull(1500) {
            while (true) {
                val newest = synchronized(samples) { samples.lastOrNull()?.ptsUs ?: 0L }
                if (newest >= endUs) break
                delay(20)
            }
        }
        val (fmt, picked) = synchronized(samples) {
            val window = samples.filter { it.ptsUs <= endUs }
            val wantStart = captureUs - PRE_US
            // start on the last key frame at or before the wanted start (or the first one we have)
            val keyIdx = window.indices.lastOrNull { window[it].key && window[it].ptsUs <= wantStart }
                ?: window.indices.firstOrNull { window[it].key }
            format to (if (keyIdx == null) emptyList() else window.drop(keyIdx))
        }
        Log.i(TAG, "cut: fed=$fed dropped=$dropped picked=${picked.size} span=${if (picked.size > 1) picked.last().ptsUs - picked.first().ptsUs else 0}us lead=${if (picked.isNotEmpty()) captureUs - picked.first().ptsUs else 0}us")
        if (fmt == null || picked.size < 2) return false
        if (picked.last().ptsUs - picked.first().ptsUs < MIN_CLIP_US) return false

        return runCatching {
            out.parentFile?.mkdirs()
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                muxer.setOrientationHint(rotation)
                val track = muxer.addTrack(fmt)
                muxer.start()
                val base = picked.first().ptsUs
                val info = MediaCodec.BufferInfo()
                picked.forEach { s ->
                    info.set(0, s.data.size, s.ptsUs - base, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            true
        }.onFailure { Log.w(TAG, "clip write failed", it); out.delete() }.getOrDefault(false)
    }

    /** Forgets buffered footage (e.g. while bracketing), keeping the encoder alive. */
    fun clear() = synchronized(samples) { samples.clear() }

    fun release() {
        running = false
        runCatching { drainThread?.join(200) }
        drainThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        synchronized(samples) { samples.clear() }
    }

    private fun copyYuv(src: Image, dst: Image) {
        val w = minOf(src.width, dst.width)
        val h = minOf(src.height, dst.height)
        for (p in 0 until 3) {
            val sp = src.planes[p]; val dp = dst.planes[p]
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            val sb = sp.buffer; val db = dp.buffer
            val sPix = sp.pixelStride; val dPix = dp.pixelStride
            val sRow = sp.rowStride; val dRow = dp.rowStride
            if (sPix == 1 && dPix == 1) {
                val row = ByteArray(pw)
                for (y in 0 until ph) {
                    sb.position(y * sRow); sb.get(row, 0, pw)
                    db.position(y * dRow); db.put(row, 0, pw)
                }
            } else if (sPix == dPix) {
                val len = (pw - 1) * sPix + 1
                val row = ByteArray(len)
                for (y in 0 until ph) {
                    sb.position(y * sRow); sb.get(row, 0, minOf(len, sb.remaining()))
                    db.position(y * dRow); db.put(row, 0, minOf(len, db.remaining()))
                }
            } else {
                val sLen = (pw - 1) * sPix + 1
                val row = ByteArray(sLen)
                for (y in 0 until ph) {
                    sb.position(y * sRow); sb.get(row, 0, minOf(sLen, sb.remaining()))
                    val base = y * dRow
                    for (x in 0 until pw) db.put(base + x * dPix, row[x * sPix])
                }
            }
        }
    }
}
