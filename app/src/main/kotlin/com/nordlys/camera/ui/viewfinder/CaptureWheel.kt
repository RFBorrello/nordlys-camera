package com.nordlys.camera.ui.viewfinder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.nordlys.camera.model.CaptureMode
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val WHEEL_RADIUS = 150.63f
private const val WHEEL_CENTER_BEYOND_EDGE = 70.63f
private const val SLOT_ANGLE = 0.5599f
private const val DISC = 76f
private const val NEIGHBOUR_SCALE = 0.6316f
private const val FLICK_TRAVEL = 25f
private const val SLOT_TRAVEL = 70f
private val EXPO_OUT = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Width/height of the wheel's touch band, in design units. */
const val WHEEL_BAND_HALF_WIDTH = 132f
const val WHEEL_BAND_HEIGHT = 152f

private fun Float.mod(n: Int): Float = ((this % n) + n) % n

private fun CaptureMode.glyph() = when (this) {
    CaptureMode.VIDEO -> Glyph.VIDEO
    CaptureMode.PHOTO -> Glyph.CAMERA
    CaptureMode.SMART -> Glyph.SEQUENCE
}

/**
 * The shutter as a wheel of capture modes (docs/LUMIA_PARITY.md §2). Lay it
 * out as a band [WHEEL_BAND_HALF_WIDTH]·2 × [WHEEL_BAND_HEIGHT] design units,
 * centred on the shutter edge, so touches elsewhere reach the dials.
 *
 *  - drag along the edge: rotate modes; tap a neighbour: select it
 *  - drag toward the preview: open/close the pro dials ([onFlick]); while open,
 *    pulling further asks to reset every dial ([onReset])
 *  - tap: [onShutter]
 */
@Composable
fun CaptureWheel(
    s: Float,
    mode: CaptureMode,
    glyphRotation: Float,
    accent: Color,
    dialsOpen: Boolean,
    modeSwitching: Boolean,
    flickable: Boolean,
    recording: Boolean,
    progress: Float?,
    enabled: Boolean,
    onModeChange: (CaptureMode) -> Unit,
    onFlick: (Boolean) -> Unit,
    onReset: () -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = CaptureMode.entries
    val n = modes.size
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(mode.ordinal.toFloat()) }
    val flickPos = remember { Animatable(if (dialsOpen) 0f else 1f) }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(mode) {
        val target = mode.ordinal.toFloat()
        val current = pos.value.mod(n)
        if (abs(current - target) > 0.001f) {
            var delta = target - current
            if (delta > n / 2f) delta -= n
            if (delta < -n / 2f) delta += n
            pos.animateTo(pos.value + delta, tween(150, easing = EXPO_OUT))
        }
    }
    LaunchedEffect(dialsOpen) { flickPos.animateTo(if (dialsOpen) 0f else 1f, tween(150, easing = EXPO_OUT)) }

    val latest by rememberUpdatedState(arrayOf<Any?>(mode, dialsOpen, modeSwitching, flickable, enabled))
    val modeChange by rememberUpdatedState(onModeChange)
    val flicked by rememberUpdatedState(onFlick)
    val reset by rememberUpdatedState(onReset)
    val shutter by rememberUpdatedState(onShutter)

    Canvas(
        modifier.pointerInput(s) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val curMode = latest[0] as CaptureMode
                val on = latest[1] as Boolean
                val switching = latest[2] as Boolean
                val canFlick = latest[3] as Boolean
                val canShoot = latest[4] as Boolean
                val anchor = Offset(size.width / 2f, size.height.toFloat())
                val dx0 = down.position.x - anchor.x
                val up0 = anchor.y - down.position.y
                val inMiddle = abs(dx0) < 47.5f * s && up0 < 140f * s
                val neighbour = when {
                    inMiddle -> 0
                    up0 < 100f * s && abs(dx0 + 80f * s) < 34f * s -> +1 // left of centre: next mode
                    up0 < 100f * s && abs(dx0 - 80f * s) < 34f * s -> -1 // right of centre: previous mode
                    else -> return@awaitEachGesture
                }
                down.consume()
                if (inMiddle) pressed = true
                val lockPx = 5f * s
                var locked = false
                var horizontal = false
                val startPos = curMode.ordinal.toFloat()
                var flick = if (on) 0f else 1f
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    val dx = ch.position.x - down.position.x
                    val dy = ch.position.y - down.position.y
                    if (!locked) {
                        val v = abs(dy) > lockPx && canFlick
                        val h = abs(dx) > lockPx && switching && !on
                        if (!v && !h) continue
                        locked = true
                        horizontal = !v
                        pressed = false
                    }
                    if (horizontal) {
                        scope.launch { pos.snapTo(startPos + dx / (SLOT_TRAVEL * s)) }
                    } else {
                        flick = if (on) 0.5f * flick + 0.5f * (dy / (FLICK_TRAVEL * s))
                        else 0.5f * flick + 0.5f * (1f + dy / (FLICK_TRAVEL * s))
                        flick = flick.coerceIn(if (on) -1f else 0f, 1f)
                        scope.launch { flickPos.snapTo(flick) }
                    }
                }
                pressed = false
                when {
                    !locked -> {
                        if (neighbour == 0) { if (canShoot) shutter() }
                        else if (switching && !on) modeChange(modes[(curMode.ordinal + neighbour).mod(n)])
                    }
                    horizontal -> {
                        val nearest = pos.value.roundToInt()
                        scope.launch { pos.animateTo(nearest.toFloat(), tween(150, easing = EXPO_OUT)) }
                        val m = modes[nearest.toFloat().mod(n).roundToInt() % n]
                        if (m != curMode) modeChange(m)
                    }
                    else -> {
                        when {
                            on && flick < -0.5f -> reset()
                            (!on && flick < 0.5f) || (on && flick > 0.5f) -> flicked(!on)
                        }
                        scope.launch { flickPos.animateTo(if (latest[1] as Boolean) 0f else 1f, tween(150, easing = EXPO_OUT)) }
                    }
                }
            }
        },
    ) {
        val anchor = Offset(size.width / 2f, size.height)
        val wheelCentre = Offset(anchor.x, anchor.y + WHEEL_CENTER_BEYOND_EDGE * s)
        val r = WHEEL_RADIUS * s
        val on = dialsOpen
        val showNeighbours = modeSwitching && !on
        val p = pos.value
        val lift = (1f - flickPos.value) * FLICK_TRAVEL * s
        for (i in 0 until 3 * n) {
            val k = i - (p.mod(n) + n)
            if (abs(k) > 2f) continue
            val selected = abs(k) < 0.5f
            if (!selected && !showNeighbours) continue
            val ax = SLOT_ANGLE * k * (if (abs(k) > 1f) abs(k).pow(0.7f) else 1f)
            val ay = SLOT_ANGLE * k
            var c = Offset(wheelCentre.x - r * sin(ay), wheelCentre.y - r * cos(ax))
            if (selected) c = Offset(c.x, c.y - lift)
            val scale = NEIGHBOUR_SCALE + (if (abs(k) < 1f) (1f - NEIGHBOUR_SCALE) * (1f - abs(k)) else 0f)
            val radius = DISC / 2f * s * scale
            val m = modes[i % n]
            val highlight = if (pressed) (1f - abs(k)).coerceIn(0f, 1f) else 0f
            drawCircle(Color.Black.copy(alpha = 0.3f), radius, c)
            if (recording && selected) drawCircle(Color(0xFFD13438), radius * 0.92f, c)
            if (highlight > 0f) drawCircle(accent.copy(alpha = highlight), radius, c)
            drawCircle(Color.White, radius - 1.6f * s * scale, c, style = Stroke(3.25f * s * scale))
            rotate(glyphRotation, pivot = c) {
                drawGlyph(m.glyph(), c, radius * 1.15f, Color.White.copy(alpha = if (enabled || !selected) 1f else 0.5f))
            }
            if (selected && progress != null) {
                val pr = 35f * s
                drawArc(
                    accent, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                    topLeft = Offset(c.x - pr, c.y - pr), size = Size(pr * 2, pr * 2), style = Stroke(3f * s),
                )
            }
        }
    }
}
