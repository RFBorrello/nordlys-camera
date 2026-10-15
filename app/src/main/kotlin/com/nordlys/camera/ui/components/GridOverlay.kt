package com.nordlys.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.nordlys.camera.model.GridType

/** Composition guides over the preview: white hairlines at 70 %. */
@Composable
fun GridOverlay(
    grid: GridType,
    modifier: Modifier = Modifier,
) {
    if (grid == GridType.NONE) return
    Canvas(modifier = modifier) {
        val color = Color.White.copy(alpha = 0.7f)
        val stroke = 1.2f
        fun v(x: Float) = drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        fun h(y: Float) = drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        when (grid) {
            GridType.NONE -> Unit
            GridType.THIRDS -> listOf(1f / 3f, 2f / 3f).forEach { v(size.width * it); h(size.height * it) }
            GridType.GOLDEN -> listOf(0.382f, 0.618f).forEach { v(size.width * it); h(size.height * it) }
            GridType.CROSSHAIR -> { v(size.width / 2f); h(size.height / 2f) }
            GridType.SQUARE -> {
                // the largest centred square, marked on the long sides
                val side = minOf(size.width, size.height)
                if (size.height > size.width) {
                    val top = (size.height - side) / 2f
                    h(top); h(top + side)
                } else {
                    val left = (size.width - side) / 2f
                    v(left); v(left + side)
                }
            }
        }
    }
}
