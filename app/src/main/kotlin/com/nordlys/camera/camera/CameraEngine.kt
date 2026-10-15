package com.nordlys.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.nordlys.camera.imaging.Oversampler
import com.nordlys.camera.model.AspectSetting
import com.nordlys.camera.model.CameraRanges
import com.nordlys.camera.model.DualCapture
import com.nordlys.camera.model.FlashSetting
import com.nordlys.camera.model.FocusSetting
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.smart.YuvFrame
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

/** Live readout of what auto exposure and focus are doing, for the indicator strip and reticle. */
data class Meter(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val afState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE,
)

/** Where a tap-to-focus stands. */
enum class FocusPhase { IDLE, SEARCHING, LOCKED, FAILED }

/**
 * Camera2 engine with a three-stream session:
 *  - preview surface (viewfinder), sized to the capture aspect,
 *  - JPEG still catch (largest size at that aspect),
 *  - YUV analysis catch (smart sequences and living-image motion).
 */
class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "NordlysCamera"
        private const val PRE_ROLL = 6
        private const val POST_ROLL = 6
        private const val ANALYSIS_MAX_PIXELS = 1920 * 1080
        private const val SETTLE_TAG = "nordlys-settle"
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
    private var aspect: AspectSetting = AspectSetting.FOUR_THREE

    private val ringBuffer = ArrayDeque<YuvFrame>()
    private var ringEnabled = false

    private var recorder: com.nordlys.camera.video.VideoRecorder? = null
    private var recording = false

    @Volatile private var burstCollector: Channel<YuvFrame>? = null
    @Volatile private var lastState: ManualState = ManualState()
    @Volatile private var meteredProduct: Double? = null
    @Volatile private var focusRegion: MeteringRectangle? = null
    @Volatile private var aeLocked = false
    private var lastMeterPush = 0L

    /** Device rotation in degrees (0/90/180/270, clockwise from natural), fed by the UI. */
    @Volatile var deviceOrientation: Int = 0
    /** Latest fix to geotag stills with, or null when location info is off. */
    @Volatile var location: android.location.Location? = null

    private val _meter = MutableStateFlow(Meter())
    val meter: StateFlow<Meter> = _meter

    private val _focusPhase = MutableStateFlow(FocusPhase.IDLE)
    val focusPhase: StateFlow<FocusPhase> = _focusPhase

    var ranges: CameraRanges = CameraRanges(
        isoMin = 50, isoMax = 3200, shutterMinNs = 125_000L, shutterMaxNs = 1_000_000_000L,
        evMinSteps = -6, evMaxSteps = 6, evStepF = 1f / 3f,
        focusMaxD = 0f, maxZoom = 1f, hasRaw = false, hasFlash = false,
    )
        private set

    private var traits = DeviceTraits(ranges, emptySet(), emptyList())

    val isFront: Boolean
        get() = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

    // ---- lens discovery --------------------------------------------------------
    fun backLensIds(): List<String> = lensesFacing(CameraCharacteristics.LENS_FACING_BACK)

    val frontLensId: String? get() = lensesFacing(CameraCharacteristics.LENS_FACING_FRONT).firstOrNull()

    private fun lensesFacing(facing: Int): List<String> = runCatching {
        manager.cameraIdList.filter { id ->
            runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing }
                .getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    // ---- open / configure ------------------------------------------------------
    suspend fun open(id: String) = withContext(Dispatchers.Main) {
        close()
        lensId = id
        val c = manager.getCameraCharacteristics(id)
        characteristics = c
        ranges = readRanges(c)
        traits = DeviceTraits(
            ranges = ranges,
            sceneModes = (c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)).toSet(),
            fpsRanges = (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()).toList(),
        )
        meteredProduct = null
        device = suspendCancellableCoroutine { cont ->
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cd: CameraDevice) { if (cont.isActive) cont.resume(cd) }
                    override fun onDisconnected(cd: CameraDevice) { cd.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected")) }
                    override fun onError(cd: CameraDevice, error: Int) {
                        cd.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("camera open failed: $error"))
                    }
                }, handler)
            } catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e) }
        }
    }

    /** Preview buffer size (sensor/landscape orientation) for an aspect; size the SurfaceView to it before [startPreview]. */
    fun previewSizeFor(id: String, aspect: AspectSetting): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.view.SurfaceHolder::class.java) ?: return Size(1440, 1080)
        return sizes.filter { matches(it, aspect) && it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }

    /** Sensor orientation relative to the natural (portrait) display, for preview transforms. */
    val sensorOrientation: Int get() = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    suspend fun startPreview(surface: Surface, aspect: AspectSetting) {
        this.aspect = aspect
        previewSurface = surface
        val d = device ?: return
        runCatching { stillReader?.close() }
        runCatching { yuvReader?.close() }
        stillReader = null; yuvReader = null
        runCatching { session?.close() }
        session = null

        val stillSize = largestSize(ImageFormat.JPEG, aspect) ?: Size(1920, 1440)
        val still = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2)
        stillReader = still

        val yuvSize = pickAnalysisSize(aspect)
        val yuv = yuvSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 4) }
        yuvReader = yuv
        yuv?.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val collector = burstCollector
            if (collector == null && !ringEnabled) { img.close(); return@setOnImageAvailableListener }
            val frame = try { YuvFrame.from(img) } catch (e: Throwable) { img.close(); null } ?: return@setOnImageAvailableListener
            if (collector != null) {
                collector.trySend(frame)
            } else {
                synchronized(ringBuffer) {
                    ringBuffer.addLast(frame)
                    while (ringBuffer.size > PRE_ROLL) ringBuffer.removeFirst()
                }
            }
        }, handler)

        val outputs = buildList {
            add(OutputConfiguration(surface))
            add(OutputConfiguration(still.surface))
            yuv?.let { add(OutputConfiguration(it.surface)) }
        }
        session = createSession(d, outputs)
        apply(lastState)
    }

    private suspend fun createSession(d: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            try {
                d.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outputs, executor,
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

    // ---- preview ---------------------------------------------------------------
    private val meterCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
            if (aeMode != null && aeMode != CameraMetadata.CONTROL_AE_MODE_OFF && iso != null && exp != null) {
                // undo the compensation so the product describes the scene, not the bias
                val bias = Math.pow(2.0, lastState.evThirds / 3.0)
                meteredProduct = iso.toDouble() * exp / bias
            }
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
            if (_focusPhase.value == FocusPhase.SEARCHING) {
                when (af) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> _focusPhase.value = FocusPhase.LOCKED
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> _focusPhase.value = FocusPhase.FAILED
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastMeterPush > 120) {
                lastMeterPush = now
                _meter.value = Meter(iso, exp, af)
            }
        }
    }

    fun apply(state: ManualState) {
        lastState = state
        val request = buildPreviewRequest(state) ?: return
        runCatching { session?.setRepeatingRequest(request, meterCallback, handler) }
            .onFailure { Log.w(TAG, "preview apply failed", it) }
    }

    fun setLivingEnabled(on: Boolean) {
        if (ringEnabled == on) return
        ringEnabled = on
        if (!on) synchronized(ringBuffer) { ringBuffer.clear() }
        apply(lastState)
    }

    private fun buildPreviewRequest(state: ManualState, template: Int = CameraDevice.TEMPLATE_PREVIEW): CaptureRequest? {
        val d = device ?: return null
        val surface = previewSurface ?: return null
        return d.createCaptureRequest(template).apply {
            addTarget(surface)
            yuvReader?.let { if (ringEnabled || burstCollector != null) addTarget(it.surface) }
            decorate(this, state, still = false)
        }.build()
    }

    private fun decorate(b: CaptureRequest.Builder, state: ManualState, still: Boolean) {
        applyManualState(b, state, traits, meteredProduct, still)
        focusRegion?.let { region ->
            if (state.focus == FocusSetting.Auto && ranges.canFocus) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
        if (aeLocked) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
    }

    // ---- focus -----------------------------------------------------------------
    /**
     * Tap-to-focus at a point of the upright preview, normalized 0..1.
     * Meters there too; the lock holds until [clearFocus] (after capture).
     */
    fun focusAt(u: Float, v: Float) {
        val c = characteristics ?: return
        val d = device ?: return
        val s = session ?: return
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val (sx, sy) = viewToSensor(u, v)
        val zoom = lastState.zoomRatio.coerceAtLeast(1f)
        val cx = active.width() * (0.5f + (sx - 0.5f) / zoom)
        val cy = active.height() * (0.5f + (sy - 0.5f) / zoom)
        val half = (minOf(active.width(), active.height()) * 0.08f / zoom).roundToInt().coerceAtLeast(16)
        val rect = Rect(
            (cx - half).roundToInt().coerceIn(0, active.width() - 1),
            (cy - half).roundToInt().coerceIn(0, active.height() - 1),
            (cx + half).roundToInt().coerceIn(1, active.width()),
            (cy + half).roundToInt().coerceIn(1, active.height()),
        )
        focusRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        if (lastState.focus != FocusSetting.Auto || !ranges.canFocus) {
            // manual focus: the tap only meters
            apply(lastState)
            return
        }
        _focusPhase.value = FocusPhase.SEARCHING
        runCatching {
            s.stopRepeating()
            val cancel = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface!!)
                decorate(this, lastState, still = false)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }.build()
            s.capture(cancel, null, handler)
            apply(lastState)
            val trigger = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface!!)
                decorate(this, lastState, still = false)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }.build()
            s.capture(trigger, meterCallback, handler)
        }.onFailure { Log.w(TAG, "focus trigger failed", it); _focusPhase.value = FocusPhase.FAILED }
    }

    fun clearFocus() {
        if (focusRegion == null && _focusPhase.value == FocusPhase.IDLE) return
        focusRegion = null
        _focusPhase.value = FocusPhase.IDLE
        val d = device; val s = session; val p = previewSurface
        if (d != null && s != null && p != null) runCatching {
            val cancel = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(p)
                decorate(this, lastState, still = false)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }.build()
            s.capture(cancel, null, handler)
        }
        apply(lastState)
    }

    /** Upright-preview coordinates → normalized sensor coordinates. */
    private fun viewToSensor(u0: Float, v0: Float): Pair<Float, Float> {
        val u = if (isFront) 1f - u0 else u0
        return when (sensorOrientation) {
            90 -> v0 to 1f - u
            180 -> 1f - u to 1f - v0
            270 -> 1f - v0 to u
            else -> u to v0
        }
    }

    // ---- still capture ---------------------------------------------------------
    data class StillsOut(val jpeg: ByteArray, val dng: ByteArray?)

    /** Settles AF (and AE for flash) before a still, like a half-press. */
    private suspend fun settleBeforeStill(state: ManualState) {
        val d = device ?: return
        val s = session ?: return
        val p = previewSurface ?: return
        val needsFlashMeter = ranges.hasFlash && state.iso == null && state.shutterNs == null &&
            state.flash != FlashSetting.OFF && !state.torch
        val af = _meter.value.afState
        val needsAf = state.focus == FocusSetting.Auto && ranges.canFocus && focusRegion == null &&
            af != CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED && af != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        if (!needsFlashMeter && !needsAf) return

        val done = CompletableDeferred<Unit>()
        // Only results from the trigger onwards count: frames already in flight still
        // report the old CONVERGED state, and accepting one would skip the pre-flash.
        var armed = false
        var sincePrecapture = 0
        var sawPrecapture = false
        val watcher = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                meterCallback.onCaptureCompleted(cs, r, result)
                if (r.tag == SETTLE_TAG) armed = true
                if (!armed) return
                sincePrecapture++
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                val afNow = result.get(CaptureResult.CONTROL_AF_STATE)
                if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
                // some HALs never report PRECAPTURE; give them a few frames to run the sequence
                val aeSettled = sawPrecapture || sincePrecapture >= 4
                val aeOk = !needsFlashMeter || ae == null || (aeSettled && ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE &&
                    (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        ae == CaptureResult.CONTROL_AE_STATE_LOCKED))
                val afOk = !needsAf || afNow == null || afNow == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afNow == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED || afNow == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                if (aeOk && afOk && !done.isCompleted) done.complete(Unit)
            }
        }
        runCatching {
            val trigger = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(p)
                decorate(this, lastState, still = false)
                setTag(SETTLE_TAG)
                if (needsFlashMeter) set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                if (needsAf) set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }.build()
            s.capture(trigger, watcher, handler)
            buildPreviewRequest(lastState)?.let { s.setRepeatingRequest(it, watcher, handler) }
        }
        val settled = withTimeoutOrNull(if (needsFlashMeter) 2500L else 1200L) { done.await() } != null
        if (!settled) Log.w(TAG, "settle timed out (flash meter=$needsFlashMeter, af=$needsAf)")
        apply(lastState)
    }

    suspend fun captureStill(state: ManualState = lastState, withRaw: Boolean = false): StillsOut? =
        withContext(Dispatchers.IO) {
            settleBeforeStill(state)
            val d = device ?: return@withContext null
            var s = session ?: return@withContext null
            val preview = previewSurface ?: return@withContext null
            val reader = stillReader ?: return@withContext null
            val chars = characteristics ?: return@withContext null

            var rawReader: ImageReader? = null
            var swapped = false
            if (withRaw && ranges.hasRaw) {
                val rawSize = largestSize(ImageFormat.RAW_SENSOR, null)
                if (rawSize != null) {
                    rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                    try {
                        s.close()
                        s = createSession(d, listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface), OutputConfiguration(rawReader.surface)))
                        session = s
                        swapped = true
                        apply(lastState)
                    } catch (e: Throwable) {
                        Log.w(TAG, "raw session unavailable, jpeg only", e)
                        runCatching { rawReader?.close() }
                        rawReader = null
                        runCatching { startPreview(preview, aspect) }
                        s = session ?: return@withContext null
                    }
                }
            }

            try {
                val jpegOrientation = captureOrientation()
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    rawReader?.let { addTarget(it.surface) }
                    decorate(this, state, still = true)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    location?.let { set(CaptureRequest.JPEG_GPS_LOCATION, it) }
                    set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }.build()

                val resultGate = CompletableDeferred<TotalCaptureResult>()
                val jpegImage = suspendCancellableCoroutine<android.media.Image> { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage()
                        if (img != null && cont.isActive) cont.resume(img) else img?.close()
                    }, handler)
                    runCatching {
                        s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                Log.i(TAG, "still: flash=${result.get(CaptureResult.FLASH_STATE)} ae=${result.get(CaptureResult.CONTROL_AE_STATE)} " +
                                    "aeMode=${result.get(CaptureResult.CONTROL_AE_MODE)} iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                                    "exp=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} gps=${location != null}")
                                resultGate.complete(result)
                            }
                        }, handler)
                    }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
                }
                val jpegBytes = jpegImage.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                jpegImage.close()

                val dngBytes = rawReader?.let { rr ->
                    runCatching {
                        val rawImage = suspendCancellableCoroutine<android.media.Image> { cont ->
                            rr.setOnImageAvailableListener({ r ->
                                val img = r.acquireLatestImage()
                                if (img != null && cont.isActive) cont.resume(img) else img?.close()
                            }, handler)
                        }
                        val result = resultGate.await()
                        android.hardware.camera2.DngCreator(chars, result).use { dng ->
                            dng.setOrientation(exifOrientation(jpegOrientation))
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
                    runCatching { startPreview(preview, aspect) }
                }
                clearFocus()
            }
        }

    /** Rotation (degrees) that makes a sensor-oriented frame upright for the current device pose. */
    fun captureOrientation(): Int {
        val sensor = sensorOrientation
        val dev = ((deviceOrientation + 45) / 90 * 90) % 360
        return if (isFront) (sensor - dev + 360) % 360 else (sensor + dev) % 360
    }

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> android.media.ExifInterface.ORIENTATION_NORMAL
    }

    // ---- bracketing --------------------------------------------------------------
    /** [count] frames around the current exposure, [evStep] apart. */
    suspend fun captureBrackets(state: ManualState, count: Int, evStep: Float): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val half = count / 2
            (-half..half).mapNotNull { k ->
                val thirds = state.evThirds + (k * evStep * 3f).roundToInt()
                val manual = state.iso != null && state.shutterNs != null
                val frame = if (manual) {
                    val f = Math.pow(2.0, (k * evStep).toDouble())
                    state.copy(shutterNs = (state.shutterNs!! * f).toLong().coerceIn(ranges.shutterMinNs, ranges.shutterMaxNs))
                } else state.copy(evThirds = thirds.coerceIn(-ranges.evMaxThirds, ranges.evMaxThirds))
                apply(frame)
                kotlinx.coroutines.delay(250) // let AE settle on the new bias
                runCatching { captureStill(frame)?.jpeg }.getOrNull()
            }.also { apply(state) }
        }

    // ---- living images ---------------------------------------------------------
    suspend fun captureLivingStill(state: ManualState = lastState): com.nordlys.camera.living.LivingPayload? {
        if (!ringEnabled) setLivingEnabled(true)
        val pre = synchronized(ringBuffer) { ringBuffer.toList().also { ringBuffer.clear() } }
        val still = captureStill(state)?.jpeg

        val channel = Channel<YuvFrame>(POST_ROLL)
        burstCollector = channel
        apply(lastState)
        val post = withTimeoutOrNull(2000) { List(POST_ROLL) { channel.receive() } } ?: emptyList()
        burstCollector = null
        apply(lastState)

        if (still == null) return null
        return com.nordlys.camera.living.LivingPayload(stillJpeg = still, frames = (pre + post).sortedBy { it.tsMs })
    }

    // ---- smart sequence ----------------------------------------------------------
    /**
     * A timed sequence: [count] analysis frames, [intervalMs] apart, with
     * exposure and focus held so the frames composite cleanly.
     */
    suspend fun captureSequence(count: Int, intervalMs: Long, onFrame: (Int) -> Unit = {}): List<YuvFrame> =
        withContext(Dispatchers.IO) {
            yuvReader ?: return@withContext emptyList()
            settleBeforeStill(lastState.copy(flash = FlashSetting.OFF))
            aeLocked = true
            val channel = Channel<YuvFrame>(Channel.CONFLATED)
            burstCollector = channel
            apply(lastState)
            val frames = ArrayList<YuvFrame>(count)
            try {
                withTimeoutOrNull(count * intervalMs + 3000) {
                    var nextDue = 0L
                    while (frames.size < count) {
                        val f = channel.receive()
                        if (f.tsMs >= nextDue) {
                            frames.add(f)
                            onFrame(frames.size)
                            nextDue = f.tsMs + intervalMs - 15
                        }
                    }
                }
            } finally {
                burstCollector = null
                aeLocked = false
                apply(lastState)
            }
            dumpSequence(frames)
            frames
        }

    /**
     * Tuning aid: when `files/dump-sequences` exists in the app's external files
     * directory (create it over adb), the last sequence is written there as raw
     * I420 planes so composites can be tuned off-device.
     */
    private fun dumpSequence(frames: List<YuvFrame>) {
        val root = context.getExternalFilesDir(null) ?: return
        if (!File(root, "dump-sequences").exists()) return
        runCatching {
            val dir = File(root, "last-sequence").apply { deleteRecursively(); mkdirs() }
            File(dir, "meta.txt").writeText("rotation=${captureOrientation()}\nframes=${frames.size}\n")
            frames.forEachIndexed { i, f ->
                File(dir, "f$i-${f.width}x${f.height}.i420").outputStream().use { o -> o.write(f.y); o.write(f.u); o.write(f.v) }
            }
        }.onFailure { Log.w(TAG, "sequence dump failed", it) }
    }

    // ---- clip recording --------------------------------------------------------
    suspend fun beginVideoRecording(audio: Boolean): Boolean = withContext(Dispatchers.Main) {
        val d = device ?: return@withContext false
        val surface = previewSurface ?: return@withContext false
        if (recording) return@withContext true

        val rec = com.nordlys.camera.video.VideoRecorder(context)
        val encoderSurface = rec.prepare(audio, lensId, captureOrientation())
        recorder = rec
        try {
            session?.close(); session = null
            val s = createSession(d, listOf(OutputConfiguration(surface), OutputConfiguration(encoderSurface)))
            session = s
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                addTarget(encoderSurface)
                applyManualState(this, lastState, traits, meteredProduct, still = false)
            }.build()
            s.setRepeatingRequest(request, meterCallback, handler)
            val started = rec.start()
            recording = started
            started
        } catch (e: Throwable) {
            Log.w(TAG, "record begin failed", e)
            runCatching { rec.stop() }
            recorder = null
            recording = false
            runCatching { startPreview(surface, aspect) }
            false
        }
    }

    suspend fun finishVideoRecording(): Uri? = withContext(Dispatchers.Main) {
        if (!recording) return@withContext null
        recording = false
        val uri = runCatching { recorder?.stop() }.getOrNull()
        recorder = null
        val surface = previewSurface
        if (surface != null && device != null) {
            runCatching { startPreview(surface, aspect) }.onFailure { Log.w(TAG, "restore after record failed", it) }
        }
        uri
    }

    /** Re-issues the repeating request (e.g. to light or douse the torch while recording). */
    fun applyDuringRecording(state: ManualState) {
        lastState = state
        if (!recording) { apply(state); return }
        val d = device ?: return
        val s = session ?: return
        val p = previewSurface ?: return
        val enc = recorder?.surface ?: return
        runCatching {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(p); addTarget(enc)
                applyManualState(this, state, traits, meteredProduct, still = false)
            }.build()
            s.setRepeatingRequest(request, meterCallback, handler)
        }
    }

    val isRecording: Boolean get() = recording

    // ---- storage ---------------------------------------------------------------
    /** One saved photo: the library row plus a small bitmap for the capture confirmation. */
    data class Saved(val uri: Uri, val preview: Bitmap?)

    /**
     * Saves a still the way the reference does: an oversampled photo always,
     * plus the full-resolution JPEG or the DNG when dual capture asks for it.
     */
    suspend fun saveStill(out: StillsOut, dual: DualCapture): Saved? = withContext(Dispatchers.IO) {
        val over = runCatching { Oversampler.oversample(out.jpeg, context.cacheDir) }
            .onFailure { Log.w(TAG, "oversample failed", it) }.getOrNull()
        val stamp = System.currentTimeMillis()
        val primary = if (over != null) {
            if (dual == DualCapture.FULL_RES) saveMedia(out.jpeg, "image/jpeg", "NRD_${stamp}_full.jpg")
            saveMedia(over.jpeg, "image/jpeg", "NRD_$stamp.jpg")
        } else saveMedia(out.jpeg, "image/jpeg", "NRD_$stamp.jpg")
        if (dual == DualCapture.DNG) out.dng?.let { saveMedia(it, "image/x-adobe-dng", "NRD_$stamp.dng") }
        primary?.let { Saved(it, smallPreview(over?.jpeg ?: out.jpeg)) }
    }

    suspend fun saveJpeg(bytes: ByteArray): Saved? = withContext(Dispatchers.IO) {
        saveMedia(bytes, "image/jpeg", "NRD_${System.currentTimeMillis()}.jpg")?.let { Saved(it, smallPreview(bytes)) }
    }

    private fun smallPreview(jpeg: ByteArray): Bitmap? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val rot = android.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        val deg = when (rot) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    }.getOrNull()

    private fun saveMedia(bytes: ByteArray, mime: String, name: String): Uri? {
        try {
            val base = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Nordlys")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, base) ?: return null
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            Log.w(TAG, "save failed", e); return null
        }
    }

    // ---- helpers ---------------------------------------------------------------
    private fun matches(s: Size, aspect: AspectSetting): Boolean {
        val r = maxOf(s.width, s.height).toFloat() / minOf(s.width, s.height)
        return abs(r - aspect.ratio) < 0.02f
    }

    private fun largestSize(fmt: Int, aspect: AspectSetting?): Size? {
        val sizes = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(fmt) ?: return null
        val pool = if (aspect == null) sizes.toList() else sizes.filter { matches(it, aspect) }.ifEmpty { sizes.toList() }
        return pool.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun pickAnalysisSize(aspect: AspectSetting): Size? {
        val sizes = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        val fits = sizes.filter { it.width * it.height <= ANALYSIS_MAX_PIXELS && it.width <= 1920 }
        return (fits.filter { matches(it, aspect) }.ifEmpty { fits }).maxByOrNull { it.width * it.height }
    }

    private fun readRanges(c: CameraCharacteristics): CameraRanges {
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600)
        val expo = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
        val focus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        return CameraRanges(
            isoMin = iso.lower, isoMax = iso.upper,
            shutterMinNs = expo?.lower ?: 125_000L, shutterMaxNs = (expo?.upper ?: 1_000_000_000L).coerceAtMost(30_000_000_000L),
            evMinSteps = ev.lower, evMaxSteps = ev.upper, evStepF = evStep,
            focusMaxD = focus, maxZoom = zoom.upper,
            hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        )
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
        focusRegion = null
        _focusPhase.value = FocusPhase.IDLE
    }

    fun release() {
        close()
        runCatching { stillReader?.close() }
        runCatching { yuvReader?.close() }
        thread.quitSafely()
    }
}
