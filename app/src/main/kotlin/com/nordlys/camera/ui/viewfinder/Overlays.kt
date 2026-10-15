package com.nordlys.camera.ui.viewfinder

import android.graphics.Bitmap
import android.view.OrientationEventListener
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import com.nordlys.camera.camera.FocusPhase
import com.nordlys.camera.ui.theme.Selawik
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max

private val EASE_IN_OUT = CubicBezierEasing(0.7f, 0f, 0.3f, 1f)

/**
 * Device rotation quantized to the orientations the viewfinder supports
 * (0 = upright, 90 = left side up, 270 = right side up). Upside-down keeps the
 * previous value, as the reference does.
 */
@Composable
fun rememberDeviceOrientation(): State<Int> {
    val context = LocalContext.current
    val state = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val next = when {
                    deg >= 315 || deg < 45 -> 0
                    deg in 60..120 -> 90
                    deg in 240..300 -> 270
                    else -> return
                }
                if (next != state.intValue) state.intValue = next
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return state
}

/** Counter-rotation for glyphs so they stay upright; animated like the reference (0.5 s ease-in-out). */
@Composable
fun animatedGlyphRotation(orientation: Int): Float {
    val target = when (orientation) { 90 -> -90f; 270 -> 90f; else -> 0f }
    val r by animateFloatAsState(target, tween(500, easing = EASE_IN_OUT), label = "glyph-rotation")
    return r
}

/** Lays [content] out in the device's upright frame (dimensions swap in landscape). */
@Composable
fun UprightOverlay(orientation: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val turned = orientation == 90 || orientation == 270
    val angle = when (orientation) { 90 -> -90f; 270 -> 90f; else -> 0f }
    Layout(content = content, modifier = modifier.fillMaxSize()) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val inner = if (turned) Constraints.fixed(h, w) else Constraints.fixed(w, h)
        val placeables = measurables.map { it.measure(inner) }
        layout(w, h) {
            placeables.forEach { p ->
                p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) { rotationZ = angle }
            }
        }
    }
}

/**
 * Focus reticle: outer ring, translucent track, small centre square, and an
 * accent progress arc for timed captures. Blinks while searching.
 */
@Composable
fun FocusReticle(center: Offset?, phase: FocusPhase, s: Float, accent: Color, progress: Float?, modifier: Modifier = Modifier) {
    val shown = center != null && (phase != FocusPhase.IDLE || progress != null)
    val alpha by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) tween(500) else tween(500, delayMillis = 200),
        label = "reticle",
    )
    val blink = rememberInfiniteTransition(label = "blink").animateFloat(
        1f, 0.1f, infiniteRepeatable(tween(100, easing = LinearEasing), RepeatMode.Reverse), label = "blink-alpha",
    )
    if (center == null || alpha <= 0.01f) return
    Canvas(modifier.fillMaxSize()) {
        val a = alpha * (if (phase == FocusPhase.SEARCHING) blink.value else 1f)
        val outer = 63f * s
        val inner = 61f * s
        val track = 11f * s
        drawCircle(Color.Black.copy(alpha = 0.2f * a), inner - track / 2f, center, style = Stroke(track))
        val ringColor = when (phase) {
            FocusPhase.LOCKED -> accent
            FocusPhase.FAILED -> Color(0xFFE81123)
            else -> Color.White
        }
        progress?.let {
            drawArc(
                accent.copy(alpha = a), -90f, 360f * it, false,
                topLeft = Offset(center.x - inner + track / 2f, center.y - inner + track / 2f),
                size = Size((inner - track / 2f) * 2, (inner - track / 2f) * 2), style = Stroke(track),
            )
        }
        drawCircle(ringColor.copy(alpha = a), outer, center, style = Stroke(2f * s))
        val sq = 14f * s
        drawRoundRect(
            Color.White.copy(alpha = a), topLeft = Offset(center.x - sq / 2f, center.y - sq / 2f), size = Size(sq, sq),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * s), style = Stroke(maxOf(1f, s * 0.7f)),
        )
    }
}

/** A black iris that closes over the frame and reopens (0.25 s each way). Bump [shot] to play it. */
@Composable
fun ShutterIris(shot: Int, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(shot) {
        if (shot == 0) return@LaunchedEffect
        t.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
        t.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
    }
    if (t.value <= 0f) return
    Canvas(modifier.fillMaxSize()) {
        val r = hypot(size.width, size.height) / 2f
        val w = r * t.value
        drawCircle(Color.Black, r - w / 2f, center, style = Stroke(w + 2f))
    }
}

/** Large light caption that fades in, holds, and fades out — for mode and dial names. */
@Composable
fun InfoText(text: String?, key: Int, s: Float, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (text == null) return@LaunchedEffect
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(250))
        delay(850)
        alpha.animateTo(0f, tween(250))
    }
    if (text == null || alpha.value <= 0f) return
    val fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (54f * s).toSp() }
    Text(
        text = text,
        style = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Light, fontSize = fontSize, lineHeight = fontSize * 1.05f),
        color = Color.White,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
    )
}

/**
 * Capture confirmation: the new photo rises over a black scrim, holds briefly,
 * then shrinks away toward the gallery corner.
 */
@Composable
fun CaptureConfirmation(bitmap: Bitmap?, key: Int, modifier: Modifier = Modifier) {
    val rise = remember { Animatable(1f) }
    val leave = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (bitmap == null) return@LaunchedEffect
        leave.snapTo(0f)
        rise.snapTo(1f)
        rise.animateTo(0f, tween(400, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)))
        delay(450)
        leave.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    if (bitmap == null || leave.value >= 1f) return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - rise.value * 1.6f).coerceIn(0f, 1f) * (1f - leave.value) }.background(Color.Black))
        Image(
            image, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = rise.value * size.height
                val k = 1f - 0.85f * leave.value
                scaleX = k; scaleY = k
                translationX += leave.value * size.width * 0.42f
                translationY += -leave.value * size.height * 0.45f
                alpha = 1f - leave.value * 0.6f
            },
        )
    }
}

/** Viewfinder sizes in pixels for a given box; keeps every overlay on the same geometry. */
fun vfFrame(width: Float, height: Float, bottomInset: Float): VfFrame {
    val s = width / 480f
    return VfFrame(width, height, Offset(width / 2f, height - max(0f, bottomInset)), s)
}
