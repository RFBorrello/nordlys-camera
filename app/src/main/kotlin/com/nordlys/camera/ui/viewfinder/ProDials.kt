package com.nordlys.camera.ui.viewfinder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The five manual parameters, innermost ring first. */
enum class Dial { EV, SHUTTER, ISO, FOCUS, WB }

enum class Ticks { ALL, QUARTERS, FIRST_LAST }

/** One stop on a dial: words, a glyph, or both absent for an unlabeled stop. */
data class DialValue(val text: String? = null, val glyph: Glyph? = null, val dimmed: Boolean = false)

data class DialModel(
    val dial: Dial,
    val values: List<DialValue>,
    val ticks: Ticks,
    val index: Float,
    val continuous: IntRange? = null,
    val enabled: Boolean = true,
) {
    fun snap(i: Float): Float {
        val c = i.coerceIn(0f, (values.size - 1).toFloat())
        return if (continuous != null && c >= continuous.first && c <= continuous.last) c else c.roundToInt().toFloat()
    }
    val current: DialValue get() = values[index.roundToInt().coerceIn(0, values.size - 1)]
}

val Dial.thumbGlyph: Glyph
    get() = when (this) {
        Dial.EV -> Glyph.EV; Dial.SHUTTER -> Glyph.SHUTTER; Dial.ISO -> Glyph.ISO
        Dial.FOCUS -> Glyph.FOCUS; Dial.WB -> Glyph.WB
    }

/**
 * Viewfinder frame shared by the overlays. Design units come from the
 * reference's 480-unit short edge; [s] converts them to pixels. [anchor] is
 * the midpoint of the shutter edge, the centre of every ring and of the wheel.
 */
data class VfFrame(val width: Float, val height: Float, val anchor: Offset, val s: Float) {
    val visibleHalf: Float get() = width / 2f
}

private const val RING_WIDTH = 100f
private const val THUMB = 42f
private val EXPO_OUT = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private class RingGeometry(frame: VfFrame, radiusUnits: Float) {
    val outer = radiusUnits * frame.s
    val width = RING_WIDTH * frame.s
    val inner = outer - width
    val spanDeg: Float
    val maxThumb: Float // radians
    init {
        if (inner < frame.visibleHalf) {
            spanDeg = 90f
            maxThumb = (PI * 29.0 / 90.0).toFloat()
        } else {
            spanDeg = Math.toDegrees(asin((frame.visibleHalf / inner).toDouble())).toFloat()
            maxThumb = asin(((frame.visibleHalf - (THUMB / 2f + 5f) * frame.s) / inner).coerceIn(0f, 1f))
        }
    }
    fun angleOf(index: Float, n: Int): Float = if (n <= 1) 0f else (2f * index / (n - 1) - 1f) * maxThumb
    fun indexOf(angle: Float, n: Int): Float = (angle / maxThumb + 1f) / 2f * (n - 1)
    fun pointAt(anchor: Offset, angle: Float, r: Float) = Offset(anchor.x + r * sin(angle), anchor.y - r * cos(angle))
}

/**
 * Concentric arc dials drawn around the shutter edge (docs/LUMIA_PARITY.md §3).
 * [radii] holds the design radius of every open ring; a ring missing from it
 * is closed (and sweeps out). Touches that miss every ring fall through to
 * [onBackgroundTap] / [onPinch], or close the rings when any are open.
 */
@Composable
fun ProDials(
    frame: VfFrame,
    models: Map<Dial, DialModel>,
    radii: Map<Dial, Float>,
    glyphRotation: Float,
    accent: Color,
    onIndex: (Dial, Float) -> Unit,
    onDismiss: () -> Unit,
    onBackgroundTap: (Offset) -> Unit,
    onPinch: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val phase = remember { Dial.entries.associateWith { Animatable(-1f) } }
    val focusAlpha = remember { Dial.entries.associateWith { Animatable(1f) } }
    val shownRadius = remember { mutableStateMapOf<Dial, Float>() }
    var dragging by remember { mutableStateOf<Dial?>(null) }
    var dragIndex by remember { mutableStateOf(0f) }

    // bring in rings that opened (or moved), sweep out the ones that closed
    LaunchedEffect(radii) {
        for (d in Dial.entries) {
            val r = radii[d]
            val prev = shownRadius[d]
            if (r != null && (prev != r || phase.getValue(d).value != 0f)) {
                shownRadius[d] = r
                launch {
                    phase.getValue(d).snapTo(1f)
                    phase.getValue(d).animateTo(0f, tween(500, easing = EXPO_OUT))
                }
            } else if (r == null && prev != null) {
                launch {
                    phase.getValue(d).animateTo(-1f, tween(500, easing = EXPO_OUT))
                    if (radii[d] == null) shownRadius.remove(d)
                }
            }
        }
    }

    val latestModels by rememberUpdatedState(models)
    val latestRadii by rememberUpdatedState(radii)
    val latestFrame by rememberUpdatedState(frame)
    val slop = LocalViewConfiguration.current.touchSlop
    val indexChanged by rememberUpdatedState(onIndex)
    val dismiss by rememberUpdatedState(onDismiss)
    val backgroundTap by rememberUpdatedState(onBackgroundTap)
    val pinch by rememberUpdatedState(onPinch)

    // keep the edge back-gesture away from the thumbs that park on the screen edges
    val exclusions = radii.mapNotNull { (d, r) ->
        val m = models[d] ?: return@mapNotNull null
        val geo = RingGeometry(frame, r)
        val p = geo.pointAt(frame.anchor, geo.angleOf(m.index, m.values.size), geo.inner)
        val half = 44f * frame.s
        android.graphics.Rect((p.x - half).toInt(), (p.y - half).toInt(), (p.x + half).toInt(), (p.y + half).toInt())
    }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { view.systemGestureExclusionRects = exclusions }
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val f = latestFrame
                val hit = hitDial(f, latestRadii, latestModels, down.position)
                if (hit != null) {
                    val (dial, onThumb) = hit
                    val model = latestModels.getValue(dial)
                    val geo = RingGeometry(f, latestRadii.getValue(dial))
                    val n = model.values.size
                    val startAngle = touchAngle(f.anchor, down.position)
                    var startIndex = model.index
                    if (!onThumb) {
                        startIndex = model.snap(geo.indexOf(startAngle, n))
                        if (startIndex != model.index) indexChanged(dial, startIndex)
                    }
                    dragging = dial
                    dragIndex = startIndex
                    Dial.entries.filter { it != dial }.forEach { d -> scope.launch { focusAlpha.getValue(d).animateTo(0f, tween(300)) } }
                    down.consume()
                    var lastEmitted = startIndex
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val a = touchAngle(f.anchor, ch.position)
                        val raw = (startIndex + (a - startAngle) / (2f * geo.maxThumb / (n - 1).coerceAtLeast(1)))
                            .coerceIn(0f, (n - 1).toFloat())
                        dragIndex = raw
                        val snapped = model.snap(raw)
                        if (snapped != lastEmitted) { lastEmitted = snapped; indexChanged(dial, snapped) }
                        ch.consume()
                    }
                    dragging = null
                    Dial.entries.forEach { d -> scope.launch { focusAlpha.getValue(d).animateTo(1f, tween(300)) } }
                } else {
                    var pinched = false
                    var travelled = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            val z = ev.calculateZoom()
                            if (z != 1f) { pinch(z); pinched = true }
                            ev.changes.forEach { it.consume() }
                        } else {
                            ev.changes.firstOrNull { it.id == down.id }?.let { travelled += it.positionChange().getDistance() }
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    if (!pinched && travelled < slop) {
                        if (latestRadii.isNotEmpty()) dismiss() else backgroundTap(down.position)
                    }
                }
            }
        },
    ) {
        for (d in Dial.entries.reversed()) {
            val r = shownRadius[d] ?: continue
            val model = models[d] ?: continue
            val ph = phase.getValue(d).value
            val alpha = (1f - abs(ph)) * focusAlpha.getValue(d).value
            if (alpha <= 0.01f) continue
            val geo = RingGeometry(frame, r)
            val idx = if (dragging == d) dragIndex else model.index
            rotate(ph * 2f * geo.spanDeg, pivot = frame.anchor) {
                drawRing(frame, geo, model, idx, alpha, glyphRotation, if (dragging == d) accent else Color.Black)
            }
        }
    }
}

private fun touchAngle(anchor: Offset, p: Offset): Float = atan2(p.x - anchor.x, anchor.y - p.y)

private fun hitDial(f: VfFrame, radii: Map<Dial, Float>, models: Map<Dial, DialModel>, p: Offset): Pair<Dial, Boolean>? {
    val dist = hypot(p.x - f.anchor.x, p.y - f.anchor.y)
    val angle = touchAngle(f.anchor, p)
    for ((d, r) in radii) {
        val m = models[d] ?: continue
        if (!m.enabled) continue
        val geo = RingGeometry(f, r)
        val thumb = geo.pointAt(f.anchor, geo.angleOf(m.index, m.values.size), geo.inner)
        if (hypot(p.x - thumb.x, p.y - thumb.y) < 40f * f.s) return d to true
    }
    for ((d, r) in radii) {
        val m = models[d] ?: continue
        if (!m.enabled) continue
        val geo = RingGeometry(f, r)
        if (dist >= geo.outer - 1.3f * geo.width && dist < geo.outer - 0.3f * geo.width &&
            abs(Math.toDegrees(angle.toDouble())) <= geo.spanDeg
        ) return d to false
    }
    return null
}

private fun DrawScope.drawRing(
    f: VfFrame,
    geo: RingGeometry,
    model: DialModel,
    index: Float,
    alpha: Float,
    glyphRotation: Float,
    thumbFill: Color,
) {
    val a = f.anchor
    val enabledAlpha = if (model.enabled) 1f else 0.4f
    val midR = geo.outer - geo.width / 2f
    // band
    drawArc(
        Color.Black.copy(alpha = 0.4f * alpha), startAngle = -90f - geo.spanDeg, sweepAngle = 2f * geo.spanDeg, useCenter = false,
        topLeft = Offset(a.x - midR, a.y - midR), size = Size(midR * 2, midR * 2), style = Stroke(geo.width),
    )
    // hairline on the inner edge
    drawArc(
        Color.White.copy(alpha = 0.7f * enabledAlpha * alpha), startAngle = -90f - geo.spanDeg, sweepAngle = 2f * geo.spanDeg, useCenter = false,
        topLeft = Offset(a.x - geo.inner, a.y - geo.inner), size = Size(geo.inner * 2, geo.inner * 2), style = Stroke(maxOf(1f, f.s)),
    )

    // ticks just inside the band, reading along the arc
    val n = model.values.size
    val tickIdx: List<Int> = when {
        n < 2 -> emptyList()
        model.ticks == Ticks.ALL -> (0 until n).toList()
        model.ticks == Ticks.FIRST_LAST -> listOf(0, n - 1)
        n < 4 -> listOf(0, n - 1)
        else -> listOf(0.0, 0.25, 0.75, 1.0).map { (it * (n - 1)).roundToInt() }.distinct()
    }
    for (i in tickIdx) {
        val ang = geo.angleOf(i.toFloat(), n)
        val pos = geo.pointAt(a, ang, geo.inner + 11f * f.s)
        val v = model.values[i]
        val tickAlpha = alpha * enabledAlpha * (if (v.dimmed) 0.6f else 0.9f)
        rotate(Math.toDegrees(ang.toDouble()).toFloat(), pivot = pos) {
            if (v.glyph != null) drawGlyph(v.glyph, pos, 16f * f.s, Color.White.copy(alpha = tickAlpha))
            else drawCanvasText(v.text ?: "", pos, 11f * f.s, Color.White.copy(alpha = tickAlpha), NordlysTypefaces.regular)
        }
    }

    // current value, centred in the band; neighbouring values slide past each other
    val labelPos = Offset(a.x, a.y - midR)
    val whole = floor(index).toInt().coerceIn(0, n - 1)
    val frac = index - whole
    val eased = if (frac <= 0.5f) 0.5f * (2f * frac).pow(2) else 1f - 0.5f * (2f * (1f - frac)).pow(2)
    rotate(glyphRotation, pivot = labelPos) {
        val inContinuous = model.continuous?.let { index >= it.first && index <= it.last } == true
        if (eased == 0f || inContinuous || whole >= n - 1) {
            drawValue(model.values[if (inContinuous) index.roundToInt() else whole], labelPos, f.s, alpha * enabledAlpha)
        } else {
            val slide = 30f * f.s
            drawValue(model.values[whole], labelPos + Offset(0f, slide * eased), f.s, alpha * enabledAlpha * (1f - eased))
            drawValue(model.values[whole + 1], labelPos + Offset(0f, -slide * (1f - eased)), f.s, alpha * enabledAlpha * eased)
        }
    }

    // thumb on the inner edge
    val thumbPos = geo.pointAt(a, geo.angleOf(index, n), geo.inner)
    val tr = THUMB / 2f * f.s
    drawCircle(thumbFill.copy(alpha = alpha), tr, thumbPos)
    rotate(glyphRotation, pivot = thumbPos) {
        if (model.dial == Dial.WB) drawCircle(Color.White.copy(alpha = alpha * enabledAlpha), tr * 0.86f, thumbPos, style = Stroke(tr / 7f))
        drawGlyph(model.dial.thumbGlyph, thumbPos, THUMB * f.s * (if (model.dial == Dial.WB) 0.9f else 1.15f), Color.White.copy(alpha = alpha * enabledAlpha))
    }
}

private fun DrawScope.drawValue(v: DialValue, c: Offset, s: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val a = alpha * (if (v.dimmed) 0.55f else 1f)
    if (v.glyph != null) drawGlyph(v.glyph, c, 56f * s, Color.White.copy(alpha = a))
    else drawCanvasText(v.text ?: "", c, 24f * s, Color.White.copy(alpha = a), NordlysTypefaces.bold)
}

/** Centred single-line text on the native canvas. */
fun DrawScope.drawCanvasText(t: String, c: Offset, px: Float, color: Color, typeface: android.graphics.Typeface) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = px
        this.color = android.graphics.Color.argb((color.alpha * 255).roundToInt(), (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt())
        textAlign = android.graphics.Paint.Align.CENTER
        this.typeface = typeface
    }
    val fm = paint.fontMetrics
    drawContext.canvas.nativeCanvas.drawText(t, c.x, c.y - (fm.ascent + fm.descent) / 2f, paint)
}
