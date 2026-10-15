package com.nordlys.camera.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nordlys.camera.camera.CameraEngine
import com.nordlys.camera.living.LivingWriter
import com.nordlys.camera.ui.settings.SettingsSheet
import com.nordlys.camera.smart.SmartProcessor
import com.nordlys.camera.smart.YuvFrame
import com.nordlys.camera.model.FlashMode
import com.nordlys.camera.model.GridType
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.model.ShutterStop
import com.nordlys.camera.model.WbPreset
import com.nordlys.camera.model.isoStops
import com.nordlys.camera.model.shutterStops
import com.nordlys.camera.ui.components.GridOverlay
import com.nordlys.camera.ui.components.MetroChip
import com.nordlys.camera.ui.components.MetroPivot
import com.nordlys.camera.ui.components.ShutterButton
import com.nordlys.camera.ui.theme.MetroForeground
import com.nordlys.camera.ui.theme.MetroForegroundDim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Pro Camera shell: preview with grid overlay, Metro pivot, shutter, and
 * the parameter wheel. Phase 1 delivers photo capture in [Camera] mode;
 * Smart and Video pivots are reserved until their engines land.
 */
@Composable
fun ViewfinderScreen(hasPermission: Boolean, audioGranted: Boolean = false, onOpenGallery: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = remember { CameraEngine(context) }
    val scope = rememberCoroutineScope()

    var engineReady by remember { mutableStateOf(false) }
    var wheelFocus by remember { mutableStateOf<Param?>(null) }
    var mode by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf(ManualState()) }
    var flash by remember { mutableStateOf(FlashMode.OFF) }
    var frontSelected by remember { mutableStateOf(false) }
    var recentSaved by remember { mutableStateOf(false) }
    var smartPhase by remember { mutableStateOf<SmartProcessor.Phase?>(null) }
    var smartProgress by remember { mutableStateOf(0 to 0) }
    var livingOn by remember { mutableStateOf(false) }
    val livingWriter = remember { LivingWriter(context) }
    var recording by remember { mutableStateOf(false) }
    var recordStartMs by remember { mutableStateOf(0L) }
    var recordElapsedSec by remember { mutableStateOf(0L) }
    var captureDng by remember { mutableStateOf(false) }
    var bracketCount by remember { mutableStateOf(1) }
    var bracketStep by remember { mutableStateOf(1.0f) }
    var timerSec by remember { mutableStateOf(0) }
    var countdownSec by remember { mutableStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    val lensId = remember { engine.backLensIds().firstOrNull() }
    val frontId = engine.frontLensId

    fun commitUpdate(s: ManualState) { state = s; engine.apply(s) }

    fun bindPreview(surface: android.view.Surface) {
        scope.launch {
            if (!hasPermission) return@launch
            val id = if (frontSelected) frontId else lensId
            if (id == null) return@launch
            runCatching {
                engine.open(id)
                engine.setPreviewSurface(surface)
            }.onSuccess { engineReady = true }.onFailure { engineReady = false }
            engine.apply(state.copy(flash = flash))
        }
    }

    val observer = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) engine.release()
        }
    }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { engine.close() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Preview
        CameraPreviewSurface(Modifier.fillMaxSize(), onSurfaceReady = { surface -> bindPreview(surface) })
        GridOverlay(grid = state.grid, Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Spacer(Modifier.height(4.dp))
            MetroPivot(
                items = listOf("Camera", "Smart", "Video"),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.padding(start = 16.dp),
            )

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ParameterWheel(
                    state = state,
                    ranges = engine.ranges,
                    focusOn = wheelFocus,
                    bracketCount = bracketCount,
                    bracketStep = bracketStep,
                    onFocusToggle = { p -> wheelFocus = if (wheelFocus == p) null else p },
                    onChange = ::commitUpdate,
                    onResetAll = {
                        commitUpdate(ManualState())
                        bracketCount = 1
                    },
                )
            }

            // Bottom HUD
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HudChip(
                        text = when (flash) { FlashMode.OFF -> "No flash"; FlashMode.ON -> "Strobe"; FlashMode.TORCH -> "Video light" },
                        onClick = {
                            flash = FlashMode.entries[(FlashMode.entries.indexOf(flash) + 1) % FlashMode.entries.size]
                            engine.apply(state.copy(flash = flash))
                        },
                    )
                    HudChip(
                        text = if (livingOn) "Motion on" else "No motion",
                        visible = mode == 0,
                        onClick = {
                            livingOn = !livingOn
                            engine.setLivingEnabled(livingOn)
                        },
                    )
                    HudChip(
                        text = if (frontSelected) "Front lens" else "Back lens",
                        visible = (if (frontSelected) frontId else lensId) != null,
                        onClick = {
                            if (frontId != null) { frontSelected = !frontSelected; engineReady = false }
                        },
                    )
                    HudChip(
                        text = if (timerSec == 0) "No delay" else "$timerSec s wait",
                        visible = mode == 0,
                        onClick = { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 } },
                    )
                    HudChip(
                        text = if (captureDng) "JPEG + DNG" else "JPEG only",
                        visible = mode == 0 && engine.ranges.hasRaw,
                        onClick = { captureDng = !captureDng },
                    )
                    HudChip(
                        text = "Settings",
                        onClick = { settingsOpen = true },
                    )
                }

                if (mode == 2) {
                    // Video mode: record / stop as their own control row.
                    Column(verticalArrangement = Arrangement.Center) {
                        HudChip(
                            text = if (recording) "Recording %02d:%02d".format(recordElapsedSec / 60, recordElapsedSec % 60) else "Ready",
                            visible = true,
                            onClick = {},
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            HudChip(text = "", visible = false, onClick = {})
                            ShutterButton(
                                enabled = engineReady && smartPhase == null,
                                pressing = recording,
                                onClick = {
                                    scope.launch {
                                        if (!recording) {
                                            runCatching { engine.beginVideoRecording(audioGranted) }
                                                .onSuccess { ok -> recording = ok }
                                                .onFailure { android.util.Log.w("NordlysViewfinder", "record failed", it) }
                                        } else {
                                            runCatching { engine.finishVideoRecording() }
                                            recording = false
                                            recentSaved = true
                                        }
                                    }
                                },
                                modifier = Modifier,
                            )
                            HudChip(
                                text = if (recording) "Stop" else "Record",
                                onClick = {
                                    scope.launch {
                                        if (!recording) {
                                            runCatching { engine.beginVideoRecording(audioGranted) }
                                                .onSuccess { ok -> recording = ok }
                                                .onFailure { android.util.Log.w("NordlysViewfinder", "record failed", it) }
                                        } else {
                                            runCatching { engine.finishVideoRecording() }
                                            recording = false
                                            recentSaved = true
                                        }
                                    }
                                },
                            )
                        }
                    }
                    LaunchedEffect(recording) {
                        if (recording) {
                            recordStartMs = System.currentTimeMillis()
                            while (recording) {
                                recordElapsedSec = (System.currentTimeMillis() - recordStartMs) / 1000
                                delay(500)
                            }
                        } else {
                            recordElapsedSec = 0L
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        HudChip(
                            text = if (recentSaved) "Saved" else gridLabel(state),
                            onClick = {
                                val order = GridType.entries
                                commitUpdate(state.copy(grid = order[(order.indexOf(state.grid) + 1) % order.size]))
                                recentSaved = false
                            },
                        )
                        ShutterButton(
                            enabled = engineReady && mode != 2 && smartPhase == null,
                            onClick = {
                                wheelFocus = null
                                scope.launch {
                                    if (timerSec > 0 && !livingOn) {
                                        for (t in timerSec downTo 1) {
                                            countdownSec = t
                                            delay(1000)
                                        }
                                        countdownSec = 0
                                    }
                                    if (mode == 0) {
                                        if (bracketCount > 1 && !livingOn) {
                                            runCatching {
                                                engine.captureBrackets(
                                                    state.copy(flash = flash),
                                                    count = bracketCount,
                                                    evStepF = bracketStep,
                                                )
                                            }.onSuccess { frames ->
                                                frames.forEach { engine.saveJpeg(it) }
                                                recentSaved = frames.isNotEmpty()
                                            }.onFailure { android.util.Log.w("NordlysViewfinder", "bracket failed", it) }
                                        } else if (livingOn) {
                                            runCatching { engine.captureLivingStill(state.copy(flash = flash)) }
                                                .onSuccess { payload ->
                                                    payload?.let {
                                                        val saved = engine.saveJpeg(it.stillJpeg)
                                                        if (saved != null) {
                                                            runCatching { livingWriter.write(saved, it) }
                                                            recentSaved = true
                                                        }
                                                    }
                                                }
                                                .onFailure { android.util.Log.w("NordlysViewfinder", "living capture failed", it) }
                                        } else {
                                            runCatching { engine.captureStill(state.copy(flash = flash), withRaw = captureDng) }
                                                .onSuccess { still ->
                                                    still?.let {
                                                        engine.saveJpeg(it.jpeg)
                                                        it.dng?.let { bytes -> engine.saveDng(bytes) }
                                                        recentSaved = true
                                                    }
                                                }
                                                .onFailure { android.util.Log.w("NordlysViewfinder", "capture failed", it) }
                                        }
                                    } else {
                                        // Smart mode: burst -> align/merge -> save
                                        smartPhase = SmartProcessor.Phase.ALIGN
                                        runCatching {
                                            val burst = engine.captureYuvBurst(7)
                                            val merged = SmartProcessor().process(burst) { phase, i, n ->
                                                smartPhase = phase
                                                smartProgress = i to n
                                            }
                                            merged?.let {
                                                engine.saveJpeg(it.toJpegBytes())
                                                recentSaved = true
                                            }
                                        }.onFailure { android.util.Log.w("NordlysViewfinder", "smart failed", it) }
                                        smartPhase = null
                                    }
                                }
                            },
                            modifier = Modifier,
                        )
                        HudChip(
                            text = "Library",
                            onClick = onOpenGallery,
                        )
                    }
                }
            }

        }

        if (countdownSec > 0) {
            Text(
                text = countdownSec.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = com.nordlys.camera.ui.theme.MetroAccent,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (settingsOpen) {
            SettingsSheet(
                onReset = {
                    commitUpdate(ManualState())
                    bracketCount = 1
                    captureDng = false
                    timerSec = 0
                    livingOn = false
                    flash = FlashMode.OFF
                    engine.apply(ManualState())
                },
                hasRaw = engine.ranges.hasRaw,
                onClose = { settingsOpen = false },
            )
        }

        if (smartPhase != null) {
            Text(
                text = when (smartPhase) {
                    SmartProcessor.Phase.ALIGN -> "Keep still"
                    else -> "Fusing frames"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = com.nordlys.camera.ui.theme.MetroForeground,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (wheelFocus != null) {
            ValueSheet(
                param = wheelFocus!!,
                state = state,
                ranges = engine.ranges,
                bracketPick = if (bracketCount > 1) bracketCount to bracketStep else null,
                onBracketPick = { n, ev ->
                    bracketCount = n
                    bracketStep = ev
                    wheelFocus = null
                },
                onSelect = { s -> commitUpdate(s); wheelFocus = null },
                onDismiss = { wheelFocus = null },
            )
        }
    }
}

private enum class Param { ISO, SHUTTER, EV, FOCUS, WHITE_BALANCE, ZOOM, BRACKET, GRID }

private fun gridLabel(s: ManualState) = when (s.grid) {
    GridType.NONE -> "No grid"
    GridType.RULE_OF_THIRDS -> "Thirds"
    GridType.SQUARE -> "Square"
    GridType.GOLDEN_RATIO -> "Golden"
}

@Composable
private fun ParameterWheel(
    state: ManualState,
    ranges: com.nordlys.camera.model.CameraRanges,
    focusOn: Param?,
    bracketCount: Int,
    bracketStep: Float,
    onFocusToggle: (Param) -> Unit,
    onChange: (ManualState) -> Unit,
    onResetAll: () -> Unit,
) {
    Column(Modifier.padding(end = 16.dp, top = 24.dp)) {
        WheelEntry(label = if (state.iso != null) state.iso.toString() else "ISO auto", active = focusOn == Param.ISO) { onFocusToggle(Param.ISO) }
        WheelEntry(
            label = state.shutterNs?.let(ShutterStop::label) ?: "Shutter auto",
            active = focusOn == Param.SHUTTER,
        ) { onFocusToggle(Param.SHUTTER) }
        WheelEntry(
            label = "EV %.1f".format(state.evSteps * ranges.evStepF),
            active = focusOn == Param.EV,
        ) { onFocusToggle(Param.EV) }
        WheelEntry(
            label = if (state.focusDiopters != null) {
                if (ranges.focusMaxD > 0f) "Focus %.2f m".format(ranges.focusMaxD / state.focusDiopters!!) else "Focus fixed"
            } else "Focus auto",
            active = focusOn == Param.FOCUS,
            enabled = ranges.focusMaxD > 0f,
        ) { if (ranges.focusMaxD > 0f) onFocusToggle(Param.FOCUS) }
        WheelEntry(
            label = "Zoom %.1f×".format(state.zoomRatio),
            active = focusOn == Param.ZOOM,
        ) { onFocusToggle(Param.ZOOM) }
        WheelEntry(label = state.wb.label, active = focusOn == Param.WHITE_BALANCE) { onFocusToggle(Param.WHITE_BALANCE) }
        WheelEntry(
            label = if (bracketCount > 1) "Bracket ${bracketCount} ±%.1f stop".format(bracketStep) else "Bracket off",
            active = focusOn == Param.BRACKET,
        ) { onFocusToggle(Param.BRACKET) }
        Spacer(Modifier.height(12.dp))
        WheelEntry(label = "Reset", active = false) {
            onResetAll()
        }
    }
}

@Composable
private fun WheelEntry(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = when {
            active -> MaterialTheme.colorScheme.primary
            !enabled -> MetroForeground.copy(alpha = 0.35f)
            else -> MetroForeground
        },
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** Metro overlay sheet: full-width flat list of stops for the focused parameter. */
@Composable
private fun ValueSheet(
    param: Param,
    state: ManualState,
    ranges: com.nordlys.camera.model.CameraRanges,
    bracketPick: Pair<Int, Float>?,
    onBracketPick: (Int, Float) -> Unit,
    onSelect: (ManualState) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().background(Color(0xDD0A0A0A)).clickable(onClick = onDismiss),
        color = Color.Transparent,
    ) {
        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp)) {
            Text(
                text = when (param) {
                    Param.ISO -> "ISO"
                    Param.SHUTTER -> "Shutter"
                    Param.EV -> "Exposure bias"
                    Param.FOCUS -> "Focus distance"
                    Param.ZOOM -> "Zoom"
                    Param.BRACKET -> "Bracketing"
                    Param.WHITE_BALANCE -> "White balance"
                    Param.GRID -> "-"
                },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (param) {
                    Param.ISO -> {
                        items(listOf(null) + isoStops(ranges)) { iso ->
                            MetroChip(
                                label = iso?.toString() ?: "Auto",
                                selected = state.iso == iso,
                                onClick = { onSelect(state.copy(iso = iso)) },
                            )
                        }
                    }
                    Param.EV -> {
                        val steps = descend(ranges.evMaxSteps, ranges.evMinSteps)
                        items(listOf(null) + steps) { step ->
                            MetroChip(
                                label = step?.let { "EV%+.1f".format(it * ranges.evStepF) } ?: "Auto",
                                selected = step == state.evSteps && state.iso == null && state.shutterNs == null,
                                onClick = { onSelect(state.copy(evSteps = step ?: 0)) },
                            )
                        }
                    }
                    Param.FOCUS -> {
                        val maxD = ranges.focusMaxD
                        val focusStops = if (maxD <= 0f) emptyList() else listOf(maxD, 0.75f * maxD, 0.5f * maxD, 0.25f * maxD, maxD.floatRangeStart)
                        items(listOf<Float?>(null) + focusStops) { diopters ->
                            MetroChip(
                                label = diopters?.let { d -> "M %.2f".format(maxD / d) } ?: "AF",
                                selected = state.focusDiopters == diopters,
                                onClick = { onSelect(state.copy(focusDiopters = diopters)) },
                            )
                        }
                    }
                    Param.ZOOM -> {
                        val zoomStops = zoomAnswers(ranges.maxZoom)
                        items(zoomStops) { z ->
                            MetroChip(
                                label = "%.1f×".format(z),
                                selected = kotlin.math.abs(state.zoomRatio - z) < 0.001f,
                                onClick = { onSelect(state.copy(zoomRatio = z)) },
                            )
                        }
                    }
                    Param.BRACKET -> {
                        val picks = listOf(
                            1 to 1.0f,
                            3 to 0.3f, 3 to 1.0f, 3 to 2.0f,
                            5 to 0.3f, 5 to 1.0f,
                        )
                        items(picks) { pick ->
                            val (n, ev) = pick
                            val label = if (n == 1) "Single" else "${n} ±%.1f".format(ev)
                            MetroChip(
                                label = label,
                                selected = bracketPick?.first == n && bracketPick?.second == ev,
                                onClick = { onBracketPick(n, ev) },
                            )
                        }
                    }
                    Param.SHUTTER -> {
                        items(listOf(null) + shutterStops(ranges)) { stop ->
                            MetroChip(
                                label = stop?.label ?: "Auto",
                                selected = state.shutterNs == stop?.ns,
                                onClick = { onSelect(state.copy(shutterNs = stop?.ns)) },
                            )
                        }
                    }
                    Param.WHITE_BALANCE -> {
                        items(WbPreset.entries.toList()) { wb ->
                            MetroChip(
                                label = wb.label,
                                selected = state.wb == wb,
                                onClick = { onSelect(state.copy(wb = wb)) },
                            )
                        }
                    }
                    Param.GRID -> {}
                }
            }
        }
    }
}

private fun descend(from: Int, to: Int): List<Int> = (from downTo to).toList()

private fun zoomAnswers(maxZoom: Float): List<Float> {
    val steps = ((maxZoom - 1f) * 10).toInt().coerceAtLeast(0)
    if (steps == 0) return listOf(1f)
    return (0..steps).map { 1f + it / 10f }
}

private val Float.floatRangeStart get() = 0.01f

@Composable
private fun HudChip(text: String, visible: Boolean = true, onClick: () -> Unit) {
    if (!visible) return
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MetroForegroundDim,
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
