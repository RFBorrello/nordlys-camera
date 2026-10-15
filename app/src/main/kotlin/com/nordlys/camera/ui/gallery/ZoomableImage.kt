package com.nordlys.camera.ui.gallery

import android.net.Uri
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Photo that fits the screen and can be pinched up to 5×, panned while
 * zoomed, and double-tapped in and out. While at 1× single-finger drags are
 * left alone so the pager can swipe between photos; [onZoomedChange] lets the
 * pager stop swiping while zoomed. A single tap (once it can't become a
 * double-tap) goes to [onTap].
 */
@Composable
fun ZoomableImage(uri: Uri, description: String, onZoomedChange: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val scale = remember(uri) { Animatable(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (box.width * (s - 1f)) / 2f
        val maxY = (box.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(uri) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                    scope.launch {
                        if (scale.value > 1.05f) {
                            offset = Offset.Zero
                            scale.animateTo(1f)
                            onZoomedChange(false)
                        } else {
                            // zoom towards the tapped point
                            val centre = Offset(box.width / 2f, box.height / 2f)
                            offset = clamp((centre - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                            scale.animateTo(DOUBLE_TAP_ZOOM)
                            onZoomedChange(true)
                        }
                    }
                })
            }
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        val zoomed = scale.value > 1.01f
                        if (fingers >= 2 || zoomed) {
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val centroid = ev.calculateCentroid()
                            val newScale = (scale.value * z).coerceIn(1f, MAX_ZOOM)
                            val centre = Offset(box.width / 2f, box.height / 2f)
                            // keep the pinch centroid fixed while scaling
                            val anchored = (offset - (centroid - centre)) * (newScale / scale.value) + (centroid - centre)
                            offset = clamp(anchored + pan, newScale)
                            scope.launch { scale.snapTo(newScale) }
                            onZoomedChange(newScale > 1.01f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale.value < 1.01f) offset = Offset.Zero
                }
            },
    ) {
        AsyncImage(
            model = uri,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
