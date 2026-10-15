package com.nordlys.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.nordlys.camera.model.CameraRanges
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.smart.YuvFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 engine with a three-stream session:
 *  - preview surface (viewfinder),
 *  - JPEG still catch (largest supported size),
 *  - YUV analysis catch (Smart-mode burst source).
 * Streams are configured once per open; captures address any of them.
 */
class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "NordlysCamera"
        private const val PRE_ROLL = 6
        private const val POST_ROLL = 6
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("nordlys-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var previewSurface: Surface? = null
    private var stillReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var lensId: String? = null
    private var characteristics: CameraCharacteristics? = null

    // Living-image ring buffer: pre-press analysis frames (armed on demand).
    private val ringBuffer = ArrayDeque<YuvFrame>()
    private var ringEnabled = false

    // Clip recording state (MediaRecorder surface-input mode).
    private var recorder: com.nordlys.camera.video.VideoRecorder? = null
    private var recording = false

    /** Analysis frames route to a sink; null sink means consume and drop. */
    @Volatile private var yuvSink: ((YuvFrame) -> Unit)? = null
    
    /** When set, arriving analysis frames satisfy a burst collection. */
    @Volatile private var burstCollector: kotlinx.coroutines.channels.Channel<YuvFrame>? = null

    @Volatile private var lastState: ManualState = ManualState()

    var ranges: CameraRanges = CameraRanges(
        isoMin = 50, isoMax = 6400, shutterMinNs = 250_000L, shutterMaxNs = 4_000_000_000L,
        evMinSteps = -3, evMaxSteps = 3, evStepF = 1f / 3f,
        focusMinD = 0f, focusMaxD = 10f, maxZoom = 1f, hasRaw = false,
    )
        private set

    val yuvSize: Size? get() = yuvReader?.let { Size(it.width, it.height) }

    // ---- lens discovery --------------------------------------------------------
    fun backLensIds(): List<String> = runCatching {
        manager.cameraIdList.filter { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    val frontLensId: String? get() = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }.getOrDefault(false)
        }
    }.getOrNull()

    // ---- open / configure ------------------------------------------------------
    suspend fun open(id: String) = withContext(Dispatchers.Main) {
        close()
        lensId = id
        characteristics = manager.getCameraCharacteristics(id)
        ranges = readRanges(manager.getCameraCharacteristics(id))
        device = suspendCancellableCoroutine { cont ->
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cd: CameraDevice) { if (cont.isActive) cont.resume(cd) }
                    override fun onDisconnected(cd: CameraDevice) { if (cont.isActive) cont.resume(cd) }
                    override fun onError(cd: CameraDevice, error: Int) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("camera open failed: $error"))
                    }
                }, handler)
            } catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e) }
        }
    }

    suspend fun setPreviewSurface(surface: Surface) {
        previewSurface = surface
        val d = device ?: return
        // drops any readers a recording detour may have left behind
        runCatching { stillReader?.close() }
        runCatching { yuvReader?.close() }
        stillReader = null; yuvReader = null

        // still output
        val stillSize = largestSize(ImageFormat.JPEG) ?: Size(1920, 1080)
        val still = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 1)
        stillReader = still

        // analysis output, sized modestly for burst cost
        val yuvSize = pickAnalysisSize()
        val yuv = if (yuvSize != null) ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 12) else null
        yuvReader = yuv
        yuv?.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val frame = try { YuvFrame.from(img) } catch (e: Throwable) { img.close(); null } ?: return@setOnImageAvailableListener
            val collector = burstCollector
            when {
                collector != null -> {
                    collector.trySend(frame)
                }
                ringEnabled -> {
                    synchronized(ringBuffer) {
                        ringBuffer.addLast(frame)
                        while (ringBuffer.size > PRE_ROLL) ringBuffer.removeFirst()
                    }
                }
                else -> frame // sink-less frame already closed by YuvFrame.from
            }
        }, handler)

        val outputs = buildList {
            add(OutputConfiguration(surface))
            add(OutputConfiguration(still.surface))
            yuv?.let { add(OutputConfiguration(it.surface)) }
        }

        val s = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
            try {
                d.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) { if (cont.isActive) cont.resume(cs) }
                            override fun onConfigureFailed(cs: CameraCaptureSession) {
                                if (cont.isActive) cont.resumeWithException(IllegalStateException("session configure failed"))
                            }
                        },
                    ),
                )
            } catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e) }
        }
        session = s
        apply(lastState)
    }

    // ---- preview ---------------------------------------------------------------
    fun apply(state: ManualState) {
        lastState = state
        val request = buildPreviewRequest(state) ?: return
        runCatching { session?.setRepeatingRequest(request, null, handler) }
            .onFailure { Log.w(TAG, "preview apply failed", it) }
    }

    /** Arms (or disarms) the continuous pre-press ring for Living Images. */
    fun setLivingEnabled(on: Boolean) {
        if (ringEnabled == on) return
        ringEnabled = on
        if (!on) synchronized(ringBuffer) { ringBuffer.clear() }
        apply(lastState)
    }

    private fun buildPreviewRequest(state: ManualState): CaptureRequest? {
        val d = device ?: return null
        val surface = previewSurface ?: return null
        return d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            yuvReader?.let { if (ringEnabled || burstCollector != null) addTarget(it.surface) }
            applyManualState(this, state)
        }.build()
    }

    // ---- still capture ---------------------------------------------------------
    /** A still moment: JPEG always; DNG when the sensor supports raw and it was requested. */
    data class StillsOut(val jpeg: ByteArray, val dng: ByteArray?)

    suspend fun captureStill(state: ManualState = lastState, withRaw: Boolean = false): StillsOut? =
        withContext(Dispatchers.IO) {
            val d = device ?: return@withContext null
            var s = session ?: return@withContext null
            val preview = previewSurface ?: return@withContext null
            val reader = stillReader ?: return@withContext null
            val chars = characteristics ?: return@withContext null

            val rawWanted = withRaw && ranges.hasRaw
            var rawReader: ImageReader? = null
            var swapped = false
            if (rawWanted) {
                val rawSize = largestSize(ImageFormat.RAW_SENSOR) ?: return@withContext null
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                // raw combination spot: rebuild this capture run on [preview, jpeg, raw]
                try {
                    s.close()
                    s = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                        try {
                            d.createCaptureSession(
                                SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface), OutputConfiguration(rawReader!!.surface)),
                                    executor,
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(cs: CameraCaptureSession) { if (cont.isActive) cont.resume(cs) }
                                        override fun onConfigureFailed(cs: CameraCaptureSession) {
                                            if (cont.isActive) cont.resumeWithException(IllegalStateException("raw session configure failed"))
                                        }
                                    },
                                ),
                            )
                        } catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e) }
                    }
                    session = s
                    swapped = true
                } catch (e: Throwable) {
                    Log.w(TAG, "raw session swap unavailable, jpeg only", e)
                    runCatching { rawReader?.close() }
                    rawReader = null
                    swapped = false
                    runCatching { setPreviewSurface(preview) } // restore
                    s = session ?: return@withContext null
                }
            }

            try {
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(preview)
                    addTarget(reader.surface)
                    rawReader?.let { addTarget(it.surface) }
                    applyManualState(this, state)
                }.build()

                val resultGate = kotlinx.coroutines.CompletableDeferred<android.hardware.camera2.TotalCaptureResult>()
                val jpegGate = suspendCancellableCoroutine<android.media.Image> { cont ->
                    runCatching {
                        s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(cSession: CameraCaptureSession, cRequest: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                resultGate.complete(result)
                            }
                        }, handler)
                    }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage()
                        if (img != null && cont.isActive) cont.resume(img)
                    }, handler)
                }
                val jpegBytes = jpegGate.planes[0].buffer.let { b ->
                    ByteArray(b.remaining()).also { b.get(it) }.also { jpegGate.close() }
                }

                var dngBytes: ByteArray? = null
                val rawReader2 = rawReader
                if (rawReader2 != null) {
                    dngBytes = runCatching {
                        val rawImage = suspendCancellableCoroutine<android.media.Image> { cont ->
                            rawReader2.setOnImageAvailableListener({ r ->
                                val img = r.acquireLatestImage()
                                if (img != null && cont.isActive) cont.resume(img)
                            }, handler)
                        }
                        val result = resultGate.await()
                        android.hardware.camera2.DngCreator(chars, result).let { dng ->
                            dng.setOrientation(0)
                            val stream = java.io.ByteArrayOutputStream()
                            dng.writeImage(stream, rawImage)
                            rawImage.close()
                            stream.toByteArray()
                        }
                    }.onFailure { Log.w(TAG, "dng build failed", it) }.getOrNull()
                }

                StillsOut(jpeg = jpegBytes, dng = dngBytes)
            } finally {
                if (swapped) {
                    runCatching { rawReader?.close() }
                    rawReader = null
                    runCatching { setPreviewSurface(preview) } // restore the capture session
                }
            }
        }

    // ---- bracketing -------------------------------------------------------------
    /**
     * Exposure-bracket fields: count frames around a center with a per-frame EV
     * step. With AE active the device AE bias is used; with a manual wheel the
     * shutter time is scaled photographically instead.
     */
    suspend fun captureBrackets(state: ManualState, count: Int, evStepF: Float): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val half = count / 2
            val frames = -half..half
            frames.mapNotNull { k ->
                val subst = if (state.iso != null && state.shutterNs != null) {
                    val f = Math.pow(2.0, (k * evStepF).toDouble())
                    state.copy(shutterNs = ((state.shutterNs!! * f).toLong().coerceIn(ranges.shutterMinNs, ranges.shutterMaxNs)))
                } else {
                    val devStep = if (ranges.evStepF == 0f) 1f / 3f else ranges.evStepF
                    val steps = (k * evStepF / devStep).roundToInt()
                    state.copy(
                        evSteps = steps,
                        iso = null,
                        shutterNs = null,
                    )
                }
                runCatching { captureStill(subst)?.jpeg }.getOrNull()
            }
        }

/**
     * Living-image capture: pre-press ring snapshot, the JPEG still moment,
     * then post-press analysis frames assembled into one motion payload.
     */
    suspend fun captureLivingStill(state: ManualState = lastState): com.nordlys.camera.living.LivingPayload? {
        if (!ringEnabled) setLivingEnabled(true)
        val pre = synchronized(ringBuffer) { ringBuffer.toList() }
        synchronized(ringBuffer) { ringBuffer.clear() }

        val still = withContext(Dispatchers.IO) { captureStill(state).also { it } }?.jpeg

        val channel = kotlinx.coroutines.channels.Channel<YuvFrame>(POST_ROLL)
        burstCollector = channel
        apply(lastState)
        val post = runCatching { List(POST_ROLL) { channel.receive() } }.getOrDefault(emptyList())
        burstCollector = null

        if (still == null) return null
        return com.nordlys.camera.living.LivingPayload(
            stillJpeg = still,
            frames = (pre + post).sortedBy { it.tsMs },
        )
    }

    // ---- clip recording --------------------------------------------------------
    /**
     * Swaps the session to preview + recorder-encoder surface on the record
     * template. Call [finishVideoRecording] to complete and restore capture.
     */
    suspend fun beginVideoRecording(audio: Boolean): Boolean = withContext(Dispatchers.Main) {
        val d = device ?: return@withContext false
        val surface = previewSurface ?: return@withContext false
        if (recording) return@withContext true

        val lens = lastLensId()
        val rec = com.nordlys.camera.video.VideoRecorder(context)
        val encoderSurface = rec.prepare(audio, lens)
        recorder = rec
        try {
            session?.close(); session = null
            val s = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                try {
                    d.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(surface), OutputConfiguration(encoderSurface)),
                            executor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(cs: CameraCaptureSession) { if (cont.isActive) cont.resume(cs) }
                                override fun onConfigureFailed(cs: CameraCaptureSession) {
                                    if (cont.isActive) cont.resumeWithException(IllegalStateException("record session configure failed"))
                                }
                            },
                        ),
                    )
                } catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e) }
            }
            session = s
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                addTarget(encoderSurface)
                applyManualState(this, lastState)
            }.build()
            s.setRepeatingRequest(request, null, handler)
            val started = rec.start()
            recording = started
            started
        } catch (e: Throwable) {
            Log.w(TAG, "record begin failed", e)
            runCatching { rec.stop() }
            recorder = null
            recording = false
            false
        }
    }

    suspend fun finishVideoRecording(): Uri? = withContext(Dispatchers.Main) {
        if (!recording) return@withContext null
        recording = false
        val uri = runCatching { recorder?.stop() }.getOrNull()
        recorder = null

        // back to the capture-configured session
        val surface = previewSurface
        if (surface != null && device != null) {
            runCatching { setPreviewSurface(surface) }
                .onFailure { Log.w(TAG, "restore after record failed", it) }
        }
        uri
    }

    val isRecording: Boolean get() = recording

    private fun lastLensId(): String? = lensId

    // ---- Smart-mode burst ------------------------------------------------------
    /**
     * Collects [frames] analysis frames sequentially. Each capture reuses the
     * preview template so the stream keeps navigating naturally; the image
     * delivery is awaited one frame at a time.
     */
    suspend fun captureYuvBurst(frames: Int): List<YuvFrame> = withContext(Dispatchers.IO) {
        val reader = yuvReader ?: return@withContext emptyList()
        val wasRing = ringEnabled
        if (!wasRing) setLivingEnabled(true)  // arms the yuv stream on the repeating request
        val channel = kotlinx.coroutines.channels.Channel<YuvFrame>(frames)
        burstCollector = channel
        apply(lastState) // ensure yuv target present in the repeating request
        try {
            List(frames) { channel.receive() }
        } finally {
            burstCollector = null
            setLivingEnabled(wasRing)
        }
    }

    // ---- storage ---------------------------------------------------------------
    fun saveJpeg(bytes: ByteArray): Uri? = saveMedia(bytes, "image/jpeg", "jpg")

    fun saveDng(bytes: ByteArray): Uri? = saveMedia(bytes, "image/x-adobe-dng", "dng")

    private fun saveMedia(bytes: ByteArray, mime: String, ext: String): Uri? {
        try {
            val base = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Nordlys_${System.currentTimeMillis()}.$ext")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, base)
                ?: return null
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
            return uri
        } catch (e: Throwable) {
            Log.w(TAG, "save failed", e); return null
        }
    }

    // ---- helpers ---------------------------------------------------------------
    private fun largestSize(fmt: Int): Size? {
        val c = lensId?.let { manager.getCameraCharacteristics(it) } ?: return null
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(fmt) ?: return null
        return sizes.maxByOrNull { it.width * it.height }
    }

    /** Analysis stream keeps under ~1.3 MP so burst capture and merge stay brisk. */
    private fun pickAnalysisSize(): Size? {
        val c = lensId?.let { manager.getCameraCharacteristics(it) } ?: return null
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        return sizes.filter { it.width * it.height <= 1_300_000 }
            .maxByOrNull { it.width * it.height }
    }

    private fun readRanges(c: CameraCharacteristics): CameraRanges {
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 6400)
        val expo = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f / 3f
        val focus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.toFloat() ?: 0f
        val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        val raw = (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)).any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        }
        return CameraRanges(
            isoMin = iso.lower, isoMax = iso.upper,
            shutterMinNs = expo?.lower ?: 250_000L, shutterMaxNs = expo?.upper ?: 4_000_000_000L,
            evMinSteps = ev.lower, evMaxSteps = ev.upper, evStepF = evStep,
            focusMinD = 0f, focusMaxD = focus, maxZoom = zoom.upper, hasRaw = raw,
        )
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
    }

    fun release() {
        close()
        runCatching { stillReader?.close() }
        runCatching { yuvReader?.close() }
        thread.quitSafely()
    }
}
