package com.nordlys.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import com.nordlys.camera.model.GridType
import com.nordlys.camera.ui.theme.MetroForegroundDim

/** Viewfinder overlays: composition grids drawn as thin Metro hairlines. */
@Composable
fun GridOverlay(
    grid: GridType,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = 1f
        val lineColor = MetroForegroundDim.copy(alpha = 0.45f)
        fun vertical(x: Float, yStart: Float = 0f, yEnd: Float = size.height) {
            drawLine(lineColor, Offset(x, yStart), Offset(x, yEnd), strokeWidth = stroke, cap = StrokeCap.Square)
        }
        fun horizontal(y: Float, xStart: Float = 0f, xEnd: Float = size.width) {
            drawLine(lineColor, Offset(xStart, y), Offset(xEnd, y), strokeWidth = stroke, cap = StrokeCap.Square)
        }
        when (grid) {
            GridType.NONE -> {}
            GridType.RULE_OF_THIRDS, GridType.GOLDEN_RATIO, GridType.SQUARE -> {
                val divisions = if (grid == GridType.GOLDEN_RATIO) floatArrayOf(0.382f, 0.618f) else floatArrayOf(1f / 3f, 2f / 3f)
                divisions.forEach { f ->
                    vertical(size.width * f)
                    horizontal(size.height * f)
                }
            }
        }
    }
}
