package com.nordlys.camera.ui.viewfinder

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nordlys.camera.camera.CameraEngine
import com.nordlys.camera.data.CameraPrefs
import com.nordlys.camera.data.CameraPrefsStore
import com.nordlys.camera.living.LivingStore
import com.nordlys.camera.model.CameraRanges
import com.nordlys.camera.model.CaptureMode
import com.nordlys.camera.model.DualCapture
import com.nordlys.camera.model.FlashSetting
import com.nordlys.camera.model.FocusSetting
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.model.SceneSetting
import com.nordlys.camera.model.WbSetting
import com.nordlys.camera.model.evLabel
import com.nordlys.camera.model.isoIsRisky
import com.nordlys.camera.model.isoStops
import com.nordlys.camera.model.shutterIsRisky
import com.nordlys.camera.model.shutterLabel
import com.nordlys.camera.model.shutterStopsNs
import com.nordlys.camera.smart.YuvFrame
import com.nordlys.camera.ui.components.GridOverlay
import com.nordlys.camera.ui.settings.SettingsSheet
import com.nordlys.camera.ui.smart.SmartEditorScreen
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.Selawik
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which pro dials are showing. */
private sealed interface OpenDials {
    data object None : OpenDials
    data class Single(val dial: Dial) : OpenDials
    data object All : OpenDials
}

private const val SEQUENCE_FRAMES = 7
private const val SEQUENCE_INTERVAL_MS = 350L

/**
 * The viewfinder (docs/LUMIA_PARITY.md §1–5): a device-fixed frame with the
 * shutter wheel on the bottom edge, concentric pro dials around it, the
 * indicator strip on the long edge, and glyphs that turn to stay upright.
 */
@Composable
fun ViewfinderScreen(hasPermission: Boolean, audioGranted: Boolean = false, onOpenGallery: () -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context) }
    val store = remember { CameraPrefsStore(context) }
    val livingStore = remember { LivingStore(context) }
    val tagger = remember { com.nordlys.camera.data.LocationTagger(context) { engine.location = it } }

    var prefs by remember { mutableStateOf(store.load()) }
    var dials by remember { mutableStateOf<OpenDials>(OpenDials.None) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var ready by remember { mutableStateOf(false) }
    var ranges by remember { mutableStateOf(engine.ranges) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Bitmap?>(null) }
    var confirmKey by remember { mutableIntStateOf(0) }
    var confirmOrientation by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var recordSec by remember { mutableIntStateOf(0) }
    var modeEnteredAt by remember { mutableStateOf(0L) }
    var info by remember { mutableStateOf<String?>(null) }
    var infoKey by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var sequence by remember { mutableStateOf<Pair<List<YuvFrame>, Int>?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val meter by engine.meter.collectAsState()
    val focusPhase by engine.focusPhase.collectAsState()
    val orientation by rememberDeviceOrientation()
    val glyphRotation = animatedGlyphRotation(orientation)
    LaunchedEffect(orientation) { engine.deviceOrientation = orientation }

    val manual = prefs.manual
    val mode = prefs.mode
    fun update(p: CameraPrefs) { prefs = p; store.save(p) }
    fun updateManual(m: ManualState) {
        update(prefs.copy(manual = m))
        if (ready) { if (recording) engine.applyDuringRecording(m) else engine.apply(m) }
    }
    fun say(text: String) { info = text; infoKey++ }

    var locationAllowed by remember { mutableStateOf(tagger.granted()) }
    val locationAsk = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        locationAllowed = result.values.any { it } || tagger.granted()
        if (!locationAllowed) update(prefs.copy(location = false))
    }
    LaunchedEffect(prefs.location) {
        if (prefs.location && !tagger.granted()) locationAsk.launch(
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    // track position only while the viewfinder is running and location info is on
    DisposableEffect(ready, prefs.location, locationAllowed) {
        if (ready && prefs.location && locationAllowed) tagger.start()
        onDispose { tagger.stop() }
    }

    val lensId = remember(hasPermission, prefs.front) {
        if (!hasPermission) null else if (prefs.front) engine.frontLensId ?: engine.backLensIds().firstOrNull() else engine.backLensIds().firstOrNull()
    }
    val bufferSize = remember(lensId, prefs.aspect) { lensId?.let { engine.previewSizeFor(it, prefs.aspect) } }

    // open + configure whenever the surface, lens or aspect changes
    LaunchedEffect(surface, lensId, prefs.aspect) {
        val s = surface ?: return@LaunchedEffect
        val id = lensId ?: return@LaunchedEffect
        ready = false
        runCatching {
            engine.open(id)
            ranges = engine.ranges
            engine.apply(prefs.manual)
            engine.startPreview(s, prefs.aspect)
        }.onSuccess { ready = true }.onFailure { Log.w("NordlysViewfinder", "camera start failed", it) }
    }
    // the living buffer runs only where a single photo can come of it (§8)
    val livingOn = prefs.living && mode == CaptureMode.PHOTO && !prefs.front && prefs.bracketCount <= 1
    LaunchedEffect(ready, livingOn) { if (ready) engine.setLivingEnabled(livingOn) }
    var videoQualities by remember { mutableStateOf(emptyList<com.nordlys.camera.model.VideoQuality>()) }
    LaunchedEffect(ready, lensId) { if (ready && !prefs.front) videoQualities = engine.videoQualities() }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { livingStore.sweep() } }
    LaunchedEffect(focusPhase) { if (focusPhase == com.nordlys.camera.camera.FocusPhase.IDLE && progress == null) focusPoint = null }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                if (recording) scope.launch { engine.finishVideoRecording(); recording = false }
                engine.close(); ready = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); engine.release() }
    }
    LaunchedEffect(recording) {
        recordSec = 0
        val start = System.currentTimeMillis()
        while (recording) {
            delay(1000 - (System.currentTimeMillis() - start) % 1000)
            if (recording) recordSec = ((System.currentTimeMillis() - start) / 1000).toInt()
        }
    }

    BackHandler(enabled = dials != OpenDials.None && !menuOpen && !settingsOpen) { dials = OpenDials.None }

    // ---- geometry ----------------------------------------------------------------
    val gestureInset = WindowInsets.mandatorySystemGestures.getBottom(density).toFloat()
    val frame = vfFrame(screen.width.toFloat(), screen.height.toFloat(), gestureInset * 0.6f)
    val s = frame.s
    val previewH = screen.width * prefs.aspect.ratio
    val previewTop = ((screen.height - previewH) / 2f).coerceAtLeast(0f)
    val previewRect = Rect(0f, previewTop, screen.width.toFloat(), previewTop + previewH)
    fun px(v: Float) = with(density) { v.toDp() }

    // ---- dials -------------------------------------------------------------------
    val models = remember(manual, ranges, meter, mode) { buildDialModels(manual, ranges, meter, mode) }
    val radii: Map<Dial, Float> = when (val d = dials) {
        OpenDials.None -> emptyMap()
        is OpenDials.Single -> mapOf(d.dial to 294f)
        OpenDials.All -> if (mode == CaptureMode.VIDEO) {
            if (ranges.canFocus) mapOf(Dial.FOCUS to 294f, Dial.WB to 394f) else mapOf(Dial.WB to 294f)
        } else buildMap {
            put(Dial.EV, 294f); put(Dial.SHUTTER, 394f); put(Dial.ISO, 494f)
            if (ranges.canFocus) { put(Dial.FOCUS, 594f); put(Dial.WB, 694f) } else put(Dial.WB, 594f)
        }
    }

    fun onDialIndex(d: Dial, i: Float) {
        val m = models[d] ?: return
        val k = i.roundToInt()
        updateManual(
            when (d) {
                Dial.EV -> manual.copy(evThirds = k - ranges.evMaxThirds)
                Dial.ISO -> manual.copy(iso = if (k == 0) null else isoStops(ranges)[k - 1])
                Dial.SHUTTER -> manual.copy(shutterNs = if (k == 0) null else shutterStopsNs(ranges)[k - 1])
                Dial.WB -> manual.copy(wb = WbSetting.entries[k])
                Dial.FOCUS -> manual.copy(
                    focus = when {
                        i < 0.5f -> FocusSetting.Auto
                        k >= m.values.size - 1 -> FocusSetting.Infinity
                        else -> FocusSetting.Manual(((i - 1f) / 5f).coerceIn(0f, 1f))
                    },
                )
            },
        )
    }

    fun toggleDial(d: Dial) {
        dials = if (dials == OpenDials.Single(d)) OpenDials.None else OpenDials.Single(d)
        if (dials != OpenDials.None) say(
            when (d) {
                Dial.EV -> "exposure"; Dial.SHUTTER -> "shutter speed"; Dial.ISO -> "ISO"
                Dial.FOCUS -> "focus"; Dial.WB -> "white balance"
            },
        )
    }

    // ---- capture -----------------------------------------------------------------
    fun afterSave(saved: CameraEngine.Saved?, shotOrientation: Int = orientation) {
        saved?.preview?.let { confirm = it; confirmOrientation = shotOrientation; confirmKey++ }
    }

    fun shoot() {
        if (busy || !ready) return
        when (mode) {
            CaptureMode.VIDEO -> scope.launch {
                busy = true
                if (!recording) {
                    recording = runCatching { engine.beginVideoRecording(audioGranted, prefs.videoQuality) }.getOrDefault(false)
                    if (!recording) say("video didn't start")
                } else {
                    runCatching { engine.finishVideoRecording() }
                    recording = false
                }
                busy = false
            }
            CaptureMode.PHOTO -> scope.launch {
                busy = true
                dials = if (dials is OpenDials.Single) OpenDials.None else dials
                if (prefs.timerSec > 0) {
                    val total = prefs.timerSec * 1000L
                    val start = System.currentTimeMillis()
                    while (true) {
                        val el = System.currentTimeMillis() - start
                        if (el >= total) break
                        progress = el / total.toFloat()
                        countdown = ((total - el + 999) / 1000).toInt()
                        delay(50)
                    }
                    progress = null; countdown = 0
                }
                shot++
                runCatching {
                    when {
                        prefs.bracketCount > 1 -> {
                            val frames = engine.captureBrackets(manual, prefs.bracketCount, prefs.bracketEv)
                            var last: CameraEngine.Saved? = null
                            frames.forEach { last = engine.saveStill(CameraEngine.StillsOut(it, null), DualCapture.OFF) ?: last }
                            afterSave(last)
                        }
                        livingOn -> {
                            val clip = livingStore.newClipFile()
                            engine.captureLivingStill(manual, clip)?.let { (still, hasClip) ->
                                val saved = engine.saveStill(still, if (prefs.dual == DualCapture.DNG) DualCapture.OFF else prefs.dual)
                                if (saved != null && hasClip) runCatching { livingStore.attach(saved.uri, clip, still.flashFired) }
                                else clip.delete()
                                afterSave(saved)
                            }
                        }
                        else -> engine.captureStill(manual, withRaw = prefs.dual == DualCapture.DNG)?.let {
                            afterSave(engine.saveStill(it, prefs.dual))
                        }
                    }
                }.onFailure { Log.w("NordlysViewfinder", "capture failed", it); say("couldn't take the photo") }
                busy = false
            }
            CaptureMode.SMART -> scope.launch {
                busy = true
                val rotation = engine.captureOrientation()
                focusPoint = Offset(previewRect.center.x, previewRect.center.y)
                val anim = launch {
                    val total = SEQUENCE_FRAMES * SEQUENCE_INTERVAL_MS + 100
                    val start = System.currentTimeMillis()
                    while (true) {
                        val f = (System.currentTimeMillis() - start) / total.toFloat()
                        progress = f.coerceAtMost(1f)
                        if (f >= 1f) break
                        delay(16)
                    }
                }
                val frames = runCatching { engine.captureSequence(SEQUENCE_FRAMES, SEQUENCE_INTERVAL_MS) }.getOrDefault(emptyList())
                anim.cancel()
                progress = null
                focusPoint = null
                if (frames.size >= 3) sequence = frames to rotation else say("hold still and try again")
                busy = false
            }
        }
    }

    // ---- strip ---------------------------------------------------------------------
    val oneOpen = (dials as? OpenDials.Single)?.dial
    val cells = buildStripCells(manual, ranges, meter, mode, prefs.advanced, oneOpen)
    fun onCell(id: CellId) {
        when (id) {
            CellId.FLASH -> if (ranges.hasFlash) {
                val next = when (manual.flash) { FlashSetting.AUTO -> FlashSetting.OFF; FlashSetting.OFF -> FlashSetting.ON; FlashSetting.ON -> FlashSetting.AUTO }
                updateManual(manual.copy(flash = next))
                say(when (next) { FlashSetting.AUTO -> "flash auto"; FlashSetting.ON -> "flash on"; FlashSetting.OFF -> "flash off" })
            }
            CellId.TORCH -> if (ranges.hasFlash) updateManual(manual.copy(torch = !manual.torch))
            CellId.SCENE -> {
                val next = SceneSetting.entries[(manual.scene.ordinal + 1) % SceneSetting.entries.size]
                updateManual(manual.copy(scene = next))
                say(when (next) { SceneSetting.AUTO -> "auto"; SceneSetting.NIGHT -> "night"; SceneSetting.SPORT -> "sports" })
            }
            CellId.WB -> toggleDial(Dial.WB)
            CellId.FOCUS -> toggleDial(Dial.FOCUS)
            CellId.ISO -> toggleDial(Dial.ISO)
            CellId.SHUTTER -> toggleDial(Dial.SHUTTER)
            CellId.EV -> toggleDial(Dial.EV)
            CellId.MODE_TOGGLE -> {
                val adv = !prefs.advanced
                dials = OpenDials.None
                update(prefs.copy(advanced = adv, manual = if (adv) prefs.manual else prefs.manual.withDialsReset()))
                if (ready) engine.apply(prefs.manual)
                say(if (adv) "pro" else "auto")
            }
        }
    }

    // ---- layout --------------------------------------------------------------------
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { screen = it }) {
        if (screen.width == 0) return@Box

        if (bufferSize != null) {
            CameraPreviewSurface(
                bufferSize = bufferSize,
                modifier = Modifier.offset { IntOffset(0, previewTop.roundToInt()) }.size(px(screen.width.toFloat()), px(previewH)),
                onSurfaceReady = { surface = it },
                onSurfaceGone = { surface = null; engine.close(); ready = false },
            )
        }
        Box(Modifier.offset { IntOffset(0, previewTop.roundToInt()) }.size(px(screen.width.toFloat()), px(previewH))) {
            GridOverlay(prefs.grid, Modifier.fillMaxSize())
        }
        FocusReticle(focusPoint, focusPhase, s, MetroAccent, if (mode == CaptureMode.SMART) progress else null)

        ProDials(
            frame = frame,
            models = models,
            radii = radii,
            glyphRotation = glyphRotation,
            accent = MetroAccent,
            onIndex = { d, i -> onDialIndex(d, i) },
            onDismiss = { dials = OpenDials.None },
            onBackgroundTap = { p ->
                if (ready && previewRect.contains(p) && mode != CaptureMode.SMART) {
                    focusPoint = p
                    engine.focusAt((p.x - previewRect.left) / previewRect.width, (p.y - previewRect.top) / previewRect.height)
                }
            },
            onPinch = { z -> updateManual(manual.copy(zoomRatio = (manual.zoomRatio * z).coerceIn(1f, ranges.maxZoom.coerceAtLeast(1f)))) },
            modifier = Modifier.fillMaxSize(),
        )

        IndicatorStrip(
            frame = frame,
            visible = dials != OpenDials.All && mode != CaptureMode.SMART && !menuOpen,
            advanced = prefs.advanced,
            cells = cells,
            glyphRotation = glyphRotation,
            onCell = { onCell(it) },
        )

        // gallery (top corner) and menu (bottom corner) on the strip's edge
        RoundButton(Glyph.GALLERY, s, glyphRotation, Modifier.offset { IntOffset((screen.width - 70f * s).roundToInt(), (8f * s).roundToInt()) }, onClick = onOpenGallery)
        if (dials == OpenDials.None) {
            RoundButton(Glyph.MORE, s, glyphRotation, Modifier.offset { IntOffset((screen.width - 70f * s).roundToInt(), (frame.anchor.y - 66f * s).roundToInt()) }, ring = false) { menuOpen = true }
        }

        val bandW = WHEEL_BAND_HALF_WIDTH * 2f * s
        val bandH = WHEEL_BAND_HEIGHT * s
        CaptureWheel(
            s = s,
            mode = mode,
            glyphRotation = glyphRotation,
            accent = MetroAccent,
            dialsOpen = dials == OpenDials.All,
            modeSwitching = dials == OpenDials.None && !recording && !busy,
            flickable = !prefs.front && mode != CaptureMode.SMART && !recording,
            recording = recording,
            progress = if (mode == CaptureMode.SMART) null else progress,
            enabled = ready,
            onModeChange = { m ->
                dials = OpenDials.None
                engine.clearFocus()
                update(prefs.copy(mode = m, manual = manual.copy(torch = false)))
                modeEnteredAt = System.currentTimeMillis()
                say(when (m) { CaptureMode.VIDEO -> "video"; CaptureMode.PHOTO -> "photo"; CaptureMode.SMART -> "smart sequence" })
            },
            onFlick = { open ->
                dials = if (open) OpenDials.All else OpenDials.None
                if (open && !prefs.advanced) update(prefs.copy(advanced = true))
                if (open) say("pro")
            },
            onReset = {
                updateManual(manual.withDialsReset())
                say("all auto")
            },
            onShutter = { shoot() },
            modifier = Modifier
                .offset { IntOffset(((screen.width - bandW) / 2f).roundToInt(), (frame.anchor.y - bandH).roundToInt()) }
                .size(px(bandW), px(bandH)),
        )

        ShutterIris(shot, Modifier.offset { IntOffset(0, previewTop.roundToInt()) }.size(px(screen.width.toFloat()), px(previewH)))
        // the confirmation is shown the way the photo was framed, not in the fixed viewfinder frame
        UprightOverlay(confirmOrientation) { CaptureConfirmation(confirm, confirmKey) }

        // video time readout (§9): just above the shutter upright, bottom-left in landscape
        if (mode == CaptureMode.VIDEO) {
            var captionUp by remember { mutableStateOf(false) }
            LaunchedEffect(infoKey) {
                if (info == null) return@LaunchedEffect
                captionUp = true; delay(1350); captionUp = false
            }
            val showTimer = recording || (dials == OpenDials.None && !menuOpen && !captionUp)
            val landscape = orientation == 90 || orientation == 270
            UprightOverlay(orientation) {
                Box(Modifier.fillMaxSize()) {
                    VideoTimer(
                        seconds = if (recording) recordSec else 0,
                        show = showTimer,
                        delayed = !recording && System.currentTimeMillis() - modeEnteredAt < 1500,
                        s = s,
                        modifier = if (landscape) Modifier.align(Alignment.BottomStart).padding(start = px(18f * s), bottom = px(38f * s))
                        else Modifier.align(Alignment.BottomCenter).padding(bottom = px(screen.height - frame.anchor.y + 163f * s)),
                    )
                }
            }
        }

        UprightOverlay(orientation) {
            Box(Modifier.fillMaxSize()) {
                InfoText(info, infoKey, s, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = px(200f * s)))
                if (countdown > 0) {
                    Text(
                        countdown.toString(),
                        style = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Light, fontSize = with(density) { (140f * s).toSp() }),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        UprightOverlay(orientation) {
            ViewfinderMenu(
                open = menuOpen,
                items = buildList {
                    if (engine.frontLensId != null) add(MenuItem(if (prefs.front) "main camera" else "front camera") {
                        menuOpen = false; dials = OpenDials.None; update(prefs.copy(front = !prefs.front))
                    })
                    if (mode == CaptureMode.PHOTO) {
                        add(MenuItem("self-timer", if (prefs.timerSec == 0) "off" else "${prefs.timerSec} seconds") {
                            update(prefs.copy(timerSec = when (prefs.timerSec) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }))
                        })
                        add(MenuItem("bracketing", if (prefs.bracketCount <= 1) "off" else "${prefs.bracketCount} photos, ±%.1f EV apart".format(prefs.bracketEv)) {
                            val presets = listOf(1 to 1f, 3 to 0.7f, 3 to 1f, 3 to 2f, 5 to 0.7f, 5 to 1f)
                            val i = presets.indexOfFirst { it.first == prefs.bracketCount && it.second == prefs.bracketEv }
                            val (n, ev) = presets[(i + 1) % presets.size]
                            update(prefs.copy(bracketCount = n, bracketEv = ev))
                        })
                        if (!prefs.front) add(MenuItem("living images", if (prefs.living) "on" else "off") {
                            update(prefs.copy(living = !prefs.living, playLiving = prefs.playLiving || !prefs.living))
                        })
                    }
                    add(MenuItem("settings") { menuOpen = false; settingsOpen = true })
                },
                onClose = { menuOpen = false },
            )
        }

        if (settingsOpen) {
            SettingsSheet(
                prefs = prefs,
                hasRaw = ranges.hasRaw,
                videoQualities = videoQualities,
                onChange = { update(it) },
                onReset = {
                    update(CameraPrefs(front = prefs.front))
                    if (ready) engine.apply(ManualState())
                },
                onClose = { settingsOpen = false },
            )
        }

        sequence?.let { (frames, rotation) ->
            SmartEditorScreen(
                frames = frames,
                rotation = rotation,
                onSave = { bytes -> engine.saveJpeg(bytes)?.also { afterSave(it) } != null },
                onClose = { sequence = null },
            )
        }
    }
}

@Composable
private fun RoundButton(glyph: Glyph, s: Float, rotation: Float, modifier: Modifier, ring: Boolean = true, onClick: () -> Unit) {
    val size = with(LocalDensity.current) { (62f * s).toDp() }
    Canvas(
        modifier
            .size(size)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        val r = 24f * s
        if (ring) {
            drawCircle(Color.Black.copy(alpha = 0.3f), r, center)
            drawCircle(Color.White, r - 1.25f * s, center, style = Stroke(2.5f * s))
        }
        rotate(rotation, pivot = center) { drawGlyph(glyph, center, r * 1.3f) }
    }
}

// ---- models ----------------------------------------------------------------------

private fun buildDialModels(m: ManualState, r: CameraRanges, meter: com.nordlys.camera.camera.Meter, mode: CaptureMode): Map<Dial, DialModel> {
    val fullManual = m.iso != null && m.shutterNs != null
    val evMax = r.evMaxThirds
    val ev = DialModel(
        Dial.EV,
        values = (-evMax..evMax).map { DialValue(evLabel(it), dimmed = it == 0) },
        ticks = Ticks.QUARTERS,
        index = (m.evThirds + evMax).toFloat().coerceIn(0f, (2 * evMax).toFloat()),
        enabled = !fullManual && evMax > 0,
    )
    val isoList = isoStops(r)
    val iso = DialModel(
        Dial.ISO,
        values = listOf(DialValue(if (m.iso == null && meter.iso != null) meter.iso.toString() else "auto", dimmed = true)) + isoList.map { DialValue(it.toString()) },
        ticks = Ticks.QUARTERS,
        index = m.iso?.let { v -> (isoList.indexOfLast { it <= v }.coerceAtLeast(0) + 1).toFloat() } ?: 0f,
    )
    val ssList = shutterStopsNs(r)
    val shutter = DialModel(
        Dial.SHUTTER,
        values = listOf(DialValue(if (m.shutterNs == null && meter.exposureNs != null) shutterLabel(meter.exposureNs) else "auto", dimmed = true)) +
            ssList.map { DialValue(shutterLabel(it)) },
        ticks = Ticks.QUARTERS,
        index = m.shutterNs?.let { v -> (ssList.indexOfLast { it <= v }.coerceAtLeast(0) + 1).toFloat() } ?: 0f,
    )
    val focus = DialModel(
        Dial.FOCUS,
        values = listOf(DialValue("auto", dimmed = true)) + List(6) { DialValue(glyph = Glyph.FOCUS_MANUAL) } + DialValue(glyph = Glyph.FOCUS_INFINITY),
        ticks = Ticks.FIRST_LAST,
        index = when (val f = m.focus) {
            FocusSetting.Auto -> 0f
            is FocusSetting.Manual -> 1f + f.position * 5f
            FocusSetting.Infinity -> 7f
        },
        continuous = 1..6,
        enabled = r.canFocus,
    )
    val wb = DialModel(
        Dial.WB,
        values = listOf(
            DialValue("auto", dimmed = true), DialValue(glyph = Glyph.WB_DAYLIGHT), DialValue(glyph = Glyph.WB_CLOUDY),
            DialValue(glyph = Glyph.WB_FLUORESCENT), DialValue(glyph = Glyph.WB_TUNGSTEN),
        ),
        ticks = Ticks.ALL,
        index = m.wb.ordinal.toFloat(),
    )
    return if (mode == CaptureMode.VIDEO) mapOf(Dial.FOCUS to focus, Dial.WB to wb)
    else mapOf(Dial.EV to ev, Dial.SHUTTER to shutter, Dial.ISO to iso, Dial.FOCUS to focus, Dial.WB to wb)
}

private fun buildStripCells(
    m: ManualState,
    r: CameraRanges,
    meter: com.nordlys.camera.camera.Meter,
    mode: CaptureMode,
    advanced: Boolean,
    open: Dial?,
): List<StripCell> {
    fun dim(d: Dial) = open != null && open != d
    val flashGlyph = when {
        !r.hasFlash -> Glyph.FLASH_OFF
        m.flash == FlashSetting.AUTO -> Glyph.FLASH_AUTO
        m.flash == FlashSetting.ON -> Glyph.FLASH_ON
        else -> Glyph.FLASH_OFF
    }
    val wbCell = StripCell(
        CellId.WB, Glyph.WB,
        text = if (m.wb == WbSetting.AUTO) "auto" else null,
        valueGlyph = when (m.wb) {
            WbSetting.AUTO -> null; WbSetting.DAYLIGHT -> Glyph.WB_DAYLIGHT; WbSetting.CLOUDY -> Glyph.WB_CLOUDY
            WbSetting.FLUORESCENT -> Glyph.WB_FLUORESCENT; WbSetting.TUNGSTEN -> Glyph.WB_TUNGSTEN
        },
        valueDimmed = m.wb == WbSetting.AUTO, dimmed = dim(Dial.WB),
    )
    val focusCell = StripCell(
        CellId.FOCUS, Glyph.FOCUS,
        text = if (m.focus == FocusSetting.Auto) "auto" else null,
        valueGlyph = when (m.focus) { FocusSetting.Auto -> null; FocusSetting.Infinity -> Glyph.FOCUS_INFINITY; is FocusSetting.Manual -> Glyph.FOCUS_MANUAL },
        valueDimmed = m.focus == FocusSetting.Auto, dimmed = dim(Dial.FOCUS),
    )
    if (mode == CaptureMode.VIDEO) {
        val torch = if (m.torch) Glyph.TORCH_ON else Glyph.TORCH_OFF
        return if (!advanced) listOf(StripCell(CellId.TORCH, torch, big = true))
        else buildList {
            add(StripCell(CellId.TORCH, torch, text = if (m.torch) "on" else "off", valueDimmed = !m.torch, dimmed = open != null))
            add(wbCell)
            if (r.canFocus) add(focusCell)
        }
    }
    if (!advanced) return listOf(
        StripCell(CellId.FLASH, flashGlyph, big = true, dimmed = !r.hasFlash),
        StripCell(
            CellId.SCENE,
            when (m.scene) { SceneSetting.AUTO -> Glyph.SCENE_AUTO; SceneSetting.NIGHT -> Glyph.SCENE_NIGHT; SceneSetting.SPORT -> Glyph.SCENE_SPORT },
            big = true,
        ),
    )
    val isoNow = m.iso ?: meter.iso
    val ssNow = m.shutterNs ?: meter.exposureNs
    return buildList {
        add(
            StripCell(
                CellId.FLASH, Glyph.FLASH_ON,
                text = if (m.flash == FlashSetting.AUTO && r.hasFlash) "auto" else null,
                valueGlyph = if (m.flash == FlashSetting.AUTO && r.hasFlash) null else flashGlyph,
                valueDimmed = m.flash == FlashSetting.AUTO, dimmed = open != null || !r.hasFlash,
            ),
        )
        add(wbCell)
        if (r.canFocus) add(focusCell)
        add(StripCell(CellId.ISO, Glyph.ISO, text = isoNow?.toString() ?: "auto", valueDimmed = m.iso == null, danger = isoNow?.let(::isoIsRisky) == true, dimmed = dim(Dial.ISO)))
        add(StripCell(CellId.SHUTTER, Glyph.SHUTTER, text = ssNow?.let(::shutterLabel) ?: "auto", valueDimmed = m.shutterNs == null, danger = ssNow?.let(::shutterIsRisky) == true, dimmed = dim(Dial.SHUTTER)))
        add(StripCell(CellId.EV, Glyph.EV, text = evLabel(m.evThirds), valueDimmed = m.evThirds == 0, dimmed = dim(Dial.EV)))
    }
}
