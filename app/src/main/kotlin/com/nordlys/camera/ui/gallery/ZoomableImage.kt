package com.nordlys.camera.ui.gallery

import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Photo that fits the screen and can be pinched up to 5×, panned while
 * zoomed, and double-tapped in and out. While at 1× single-finger drags are
 * left alone so the pager can swipe between photos; [onZoomedChange] lets the
 * pager stop swiping while zoomed. A single tap (once it can't become a
 * double-tap) goes to [onTap].
 *
 * Panning is bounded by the photo itself, not the screen, so a letter-boxed
 * picture can never be dragged out of view.
 */
@Composable
fun ZoomableImage(uri: Uri, description: String, onZoomedChange: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var intrinsic by remember(uri) { mutableStateOf(Size.Unspecified) }
    var animation by remember { mutableStateOf<Job?>(null) }

    /** The photo's on-screen size at 1× (ContentScale.Fit inside the box). */
    fun fitted(): Size {
        if (box.width == 0 || box.height == 0) return Size.Zero
        val i = intrinsic
        if (i == Size.Unspecified || i.width <= 0f || i.height <= 0f) return Size(box.width.toFloat(), box.height.toFloat())
        val k = minOf(box.width / i.width, box.height / i.height)
        return Size(i.width * k, i.height * k)
    }

    fun clamp(o: Offset, s: Float): Offset {
        val f = fitted()
        val maxX = ((f.width * s - box.width) / 2f).coerceAtLeast(0f)
        val maxY = ((f.height * s - box.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        animation?.cancel()
        val fromScale = scale
        val fromOffset = offset
        animation = scope.launch {
            animate(0f, 1f) { t, _ ->
                scale = fromScale + (targetScale - fromScale) * t
                offset = fromOffset + (targetOffset - fromOffset) * t
            }
        }
        onZoomedChange(targetScale > 1.01f)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(uri) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                    if (scale > 1.05f) {
                        animateTo(1f, Offset.Zero)
                    } else {
                        // zoom towards the tapped point
                        val centre = Offset(box.width / 2f, box.height / 2f)
                        animateTo(DOUBLE_TAP_ZOOM, clamp((centre - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM))
                    }
                })
            }
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        val moved = ev.changes.any { it.positionChanged() }
                        if (moved && (fingers >= 2 || scale > 1.01f)) {
                            animation?.cancel()
                            val z = if (fingers >= 2) ev.calculateZoom() else 1f
                            val pan = ev.calculatePan()
                            val centroid = ev.calculateCentroid(useCurrent = true)
                            val newScale = (scale * z).coerceIn(1f, MAX_ZOOM)
                            val centre = Offset(box.width / 2f, box.height / 2f)
                            // keep the point under the fingers fixed while scaling
                            val anchor = if (centroid == Offset.Unspecified) centre else centroid
                            val ratio = newScale / scale
                            val scaled = (offset - (anchor - centre)) * ratio + (anchor - centre)
                            scale = newScale
                            offset = clamp(scaled + pan, newScale)
                            onZoomedChange(newScale > 1.01f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale < 1.01f) { scale = 1f; offset = Offset.Zero; onZoomedChange(false) }
                }
            },
    ) {
        AsyncImage(
            // decode well above screen size so zooming in stays sharp
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(uri).size(4096).build(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            onSuccess = { intrinsic = it.painter.intrinsicSize },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
