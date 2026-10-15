package com.nordlys.camera.ui.viewfinder

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

enum class CellId { FLASH, SCENE, TORCH, WB, FOCUS, ISO, SHUTTER, EV, MODE_TOGGLE }

/** One indicator: a small glyph over a value (advanced) or one large glyph (basic). */
data class StripCell(
    val id: CellId,
    val glyph: Glyph,
    val text: String? = null,
    val valueGlyph: Glyph? = null,
    val valueDimmed: Boolean = false,
    val danger: Boolean = false,
    val dimmed: Boolean = false,
    val big: Boolean = false,
)

private const val CELL = 50f
private const val LEAD = 25f
private const val EDGE_INSET = 22f
private val EXPO_OUT = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * Indicator pill on the long edge (docs/LUMIA_PARITY.md §4): basic shows flash
 * and scene, advanced shows every manual parameter with its live value. The
 * last cell toggles between the two.
 */
@Composable
fun IndicatorStrip(
    frame: VfFrame,
    visible: Boolean,
    advanced: Boolean,
    cells: List<StripCell>,
    glyphRotation: Float,
    onCell: (CellId) -> Unit,
) {
    val s = frame.s
    val density = LocalDensity.current
    val slide by animateFloatAsState(if (visible) 0f else 1f, tween(300, easing = EXPO_OUT), label = "strip-slide")
    val length by animateFloatAsState(LEAD + CELL * (cells.size + 1), tween(200, easing = EXPO_OUT), label = "strip-length")
    if (slide >= 0.999f) return

    val pillW = CELL * s
    val pillL = length * s
    val x = frame.width - (EDGE_INSET + CELL) * s + slide * 72f * s
    val y0 = frame.height / 2f - pillL / 2f
    fun px(v: Float) = with(density) { v.toDp() }

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y0.roundToInt()) }
            .size(px(pillW), px(pillL))
            .graphicsLayer { alpha = 1f - slide },
    ) {
        Canvas(Modifier.size(px(pillW), px(pillL))) {
            drawRoundRect(Color.Black.copy(alpha = 0.4f), cornerRadius = CornerRadius(pillW / 2f), size = Size(pillW, pillL))
        }
        cells.forEachIndexed { i, cell ->
            val top = (LEAD + CELL * i) * s
            Canvas(
                Modifier
                    .offset { IntOffset(0, top.roundToInt()) }
                    .size(px(pillW), px(CELL * s))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onCell(cell.id) },
            ) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val a = if (cell.dimmed) 0.4f else 1f
                rotate(glyphRotation, pivot = c) {
                    if (cell.big) {
                        drawGlyph(cell.glyph, c, 40f * s, Color.White.copy(alpha = a))
                    } else {
                        drawGlyph(cell.glyph, Offset(c.x, c.y - 12f * s), 20f * s, Color.White.copy(alpha = a))
                        val vc = Offset(c.x, c.y + 9f * s)
                        val va = a * (if (cell.valueDimmed) 0.55f else 1f)
                        if (cell.valueGlyph != null) drawGlyph(cell.valueGlyph, vc, 22f * s, Color.White.copy(alpha = va))
                        else if (cell.text != null) drawCanvasText(cell.text, vc, (if (cell.text.length > 4) 13f else 17f) * s, Color.White.copy(alpha = va), NordlysTypefaces.semibold)
                    }
                    if (cell.danger) {
                        drawLine(Color(0xFFE81123), Offset(c.x - 23.5f * s, c.y + 23.5f * s), Offset(c.x + 23.5f * s, c.y + 23.5f * s), strokeWidth = 3f * s)
                    }
                }
            }
        }
        // collapse / expand toggle at the shutter end of the pill
        val top = (LEAD + CELL * cells.size) * s
        Canvas(
            Modifier
                .offset { IntOffset(0, top.roundToInt()) }
                .size(px(pillW), px(CELL * s))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onCell(CellId.MODE_TOGGLE) },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            rotate(if (advanced) -90f else 90f, pivot = c) { drawGlyph(Glyph.CHEVRON_RIGHT, c, 26f * s) }
        }
    }
}
