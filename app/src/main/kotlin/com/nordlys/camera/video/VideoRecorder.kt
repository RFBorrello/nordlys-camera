package com.nordlys.camera.video

import android.content.ContentValues
import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.nordlys.camera.model.VideoQuality

/**
 * Clip controller backed by the platform MediaRecorder in surface-input mode.
 * The encoder surface joins the camera capture session; timing, compression,
 * container assembly, and audio come from the platform stack.
 * Audio only when the microphone permission has been granted.
 */
class VideoRecorder(private val context: Context) {

    companion object { private val TAG = "NordlysVideo" }

    private var recorder: MediaRecorder? = null
    var pendingUri: Uri? = null
        private set
    var outputFd: ParcelFileDescriptor? = null
        private set

    /** Builds the encoder and returns its input surface for the session. */
    /** The encoder input while a clip is being prepared or recorded. */
    var surface: Surface? = null
        private set

    fun prepare(audio: Boolean, lensId: String?, quality: VideoQuality, orientationHint: Int = 0): Surface {
        releaseRecorder()
        val r = MediaRecorder()
        recorder = r
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        val profile = resolveProfile(lensId, audio, quality)
        r.setVideoEncodingBitRate(profile.bitRate)
        r.setVideoFrameRate(profile.frameRate)
        r.setVideoSize(profile.width, profile.height)
        r.setVideoEncoder(profile.videoCodec)
        if (audio) {
            r.setAudioEncodingBitRate(profile.audioBitRate)
            r.setAudioSamplingRate(profile.audioSampleRate)
            r.setAudioChannels(profile.audioChannels)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }

        r.setOrientationHint(orientationHint)
        val base = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Nordlys_clip_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Nordlys")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, base)
            ?: throw IllegalStateException("MediaStore clip insert failed")
        pendingUri = uri
        outputFd = context.contentResolver.openFileDescriptor(uri, "rw")
        outputFd?.fileDescriptor?.let { r.setOutputFile(it) }

        runCatching { r.prepare() }.onFailure {
            releaseRecorder()
            throw it
        }
        return r.surface.also { surface = it }
    }

    /**
     * The chosen size and rate, with the bit-rate (and audio format) borrowed from
     * the device's own profile for that size when it publishes one, scaled to the
     * frame rate; a conservative manual profile otherwise.
     */
    private fun resolveProfile(lensId: String?, audio: Boolean, q: VideoQuality): ClipProfile {
        val cameraIndex = (lensId?.toIntOrNull() ?: 0).coerceIn(0, 1)
        val quality = if (q.height >= 1080) CamcorderProfile.QUALITY_1080P else CamcorderProfile.QUALITY_720P
        val p = runCatching {
            if (CamcorderProfile.hasProfile(cameraIndex, quality)) CamcorderProfile.get(cameraIndex, quality) else null
        }.getOrNull()
        val baseRate = p?.videoBitRate ?: if (q.height >= 1080) 17_000_000 else 10_000_000
        val baseFps = p?.videoFrameRate?.takeIf { it > 0 } ?: 30
        return ClipProfile(
            bitRate = (baseRate.toLong() * q.fps / baseFps).toInt(),
            frameRate = q.fps,
            width = q.width,
            height = q.height,
            videoCodec = MediaRecorder.VideoEncoder.H264,
            audioBitRate = if (audio) p?.audioBitRate ?: 96_000 else 0,
            audioSampleRate = if (audio) p?.audioSampleRate ?: 48_000 else 0,
            audioChannels = if (audio) p?.audioChannels ?: 1 else 0,
        )
    }

    fun start(): Boolean {
        val r = recorder ?: return false
        return runCatching { r.start() }.onFailure { Log.w(TAG, "record start failed", it) }.isSuccess
    }

    /** Completes the clip, publishes the row, returns the URI (null = broken clip). */
    fun stop(): Uri? {
        val r = recorder ?: return null
        val ok = runCatching { r.stop() }.isSuccess
        val uri = pendingUri
        releaseRecorder()
        if (!ok || uri == null) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            return null
        }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, done, null, null) }
        return uri
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        surface = null
        runCatching { outputFd?.close() }
        outputFd = null
    }
}

/** Clip encoding parameters, resolved once per session. */
private data class ClipProfile(
    val bitRate: Int,
    val frameRate: Int,
    val width: Int,
    val height: Int,
    val videoCodec: Int,
    val audioBitRate: Int,
    val audioSampleRate: Int,
    val audioChannels: Int,
)
