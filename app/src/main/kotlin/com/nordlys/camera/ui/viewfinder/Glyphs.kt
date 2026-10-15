package com.nordlys.camera.ui.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Nordlys line-art glyphs. Every glyph is drawn into a square of side [s]
 * centred on [c], white by default, with a stroke proportional to the size,
 * so the same art serves 20 px indicator labels and 56 px dial values.
 */
enum class Glyph {
    CAMERA, VIDEO, SEQUENCE, GALLERY, MORE, CHEVRON_LEFT, CHEVRON_RIGHT,
    FLASH_AUTO, FLASH_ON, FLASH_OFF, TORCH_ON, TORCH_OFF,
    SCENE_AUTO, SCENE_NIGHT, SCENE_SPORT,
    WB, WB_DAYLIGHT, WB_CLOUDY, WB_FLUORESCENT, WB_TUNGSTEN,
    EV, SHUTTER, ISO, FOCUS, FOCUS_INFINITY, FOCUS_MANUAL,
    SWITCH_LENS, TIMER,
}

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp, modifier: Modifier = Modifier, color: Color = Color.White, rotation: Float = 0f) {
    Canvas(modifier.size(size)) {
        rotate(rotation) { drawGlyph(glyph, center, this.size.minDimension, color) }
    }
}

fun DrawScope.drawGlyph(glyph: Glyph, c: Offset, s: Float, color: Color = Color.White) {
    val w = s / 13f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(c.x + x * s, c.y + y * s)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = w) =
        drawLine(color, p(x0, y0), p(x1, y1), strokeWidth = width, cap = StrokeCap.Round)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false, fill: Boolean = false) {
        val path = Path().apply {
            moveTo(c.x + pts[0].first * s, c.y + pts[0].second * s)
            for (i in 1 until pts.size) lineTo(c.x + pts[i].first * s, c.y + pts[i].second * s)
            if (close) close()
        }
        drawPath(path, color, style = if (fill) Fill else stroke)
    }
    fun text(t: String, size: Float, bold: Boolean = true) = drawGlyphText(t, c, s * size, color, bold)
    fun bolt(dx: Float = 0f, scale: Float = 1f) = path(
        dx + 0.06f * scale to -0.36f * scale, dx - 0.16f * scale to 0.04f * scale, dx + 0.0f to 0.04f * scale,
        dx - 0.06f * scale to 0.36f * scale, dx + 0.17f * scale to -0.06f * scale, dx + 0.01f * scale to -0.06f * scale,
        close = true, fill = true,
    )

    when (glyph) {
        Glyph.CAMERA -> {
            drawRoundRect(color, topLeft = p(-0.34f, -0.2f), size = Size(0.68f * s, 0.46f * s), cornerRadius = CornerRadius(0.06f * s), style = stroke)
            path(-0.14f to -0.2f, -0.09f to -0.29f, 0.09f to -0.29f, 0.14f to -0.2f)
            drawCircle(color, 0.12f * s, p(0f, 0.03f), style = stroke)
        }
        Glyph.VIDEO -> {
            drawRoundRect(color, topLeft = p(-0.36f, -0.18f), size = Size(0.48f * s, 0.36f * s), cornerRadius = CornerRadius(0.05f * s), style = stroke)
            path(0.16f to -0.06f, 0.36f to -0.17f, 0.36f to 0.17f, 0.16f to 0.06f, close = true)
        }
        Glyph.SEQUENCE -> {
            for (i in 0..2) {
                val o = i * 0.09f
                drawRoundRect(
                    color.copy(alpha = 1f - i * 0.3f), topLeft = p(-0.33f + o, -0.27f + o),
                    size = Size(0.44f * s, 0.32f * s), cornerRadius = CornerRadius(0.04f * s), style = stroke,
                )
            }
        }
        Glyph.GALLERY -> {
            drawRoundRect(color, topLeft = p(-0.3f, -0.24f), size = Size(0.6f * s, 0.48f * s), cornerRadius = CornerRadius(0.04f * s), style = stroke)
            path(-0.22f to 0.15f, -0.07f to -0.02f, 0.04f to 0.08f, 0.11f to 0.01f, 0.22f to 0.15f)
            drawCircle(color, 0.045f * s, p(0.12f, -0.1f))
        }
        Glyph.MORE -> for (i in -1..1) drawCircle(color, 0.05f * s, p(i * 0.18f, 0f))
        Glyph.CHEVRON_LEFT -> path(0.08f to -0.18f, -0.08f to 0f, 0.08f to 0.18f)
        Glyph.CHEVRON_RIGHT -> path(-0.08f to -0.18f, 0.08f to 0f, -0.08f to 0.18f)
        Glyph.FLASH_ON -> bolt()
        Glyph.FLASH_AUTO -> {
            bolt(dx = -0.08f)
            drawGlyphText("A", p(0.2f, 0.2f), s * 0.3f, color, true)
        }
        Glyph.FLASH_OFF -> {
            bolt()
            line(-0.26f, -0.3f, 0.26f, 0.3f)
        }
        Glyph.TORCH_ON, Glyph.TORCH_OFF -> {
            path(-0.3f to -0.08f, 0.02f to -0.08f, 0.12f to -0.16f, 0.12f to 0.16f, 0.02f to 0.08f, -0.3f to 0.08f, close = true)
            if (glyph == Glyph.TORCH_ON) {
                line(0.22f, 0f, 0.34f, 0f); line(0.2f, -0.15f, 0.3f, -0.24f); line(0.2f, 0.15f, 0.3f, 0.24f)
            } else line(-0.28f, -0.28f, 0.28f, 0.28f)
        }
        Glyph.SCENE_AUTO -> {
            drawCircle(color, 0.3f * s, c, style = stroke)
            text("A", 0.36f)
        }
        Glyph.SCENE_NIGHT -> {
            val moon = Path().apply {
                addOval(Rect(p(-0.26f, -0.28f), Size(0.54f * s, 0.54f * s)))
            }
            val bite = Path().apply { addOval(Rect(p(-0.08f, -0.38f), Size(0.5f * s, 0.5f * s))) }
            val crescent = Path().apply { op(moon, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
            drawPath(crescent, color)
            drawCircle(color, 0.03f * s, p(0.25f, 0.12f))
        }
        Glyph.SCENE_SPORT -> {
            drawCircle(color, 0.15f * s, p(0.1f, 0f))
            line(-0.34f, -0.1f, -0.12f, -0.1f); line(-0.3f, 0.02f, -0.08f, 0.02f); line(-0.26f, 0.14f, -0.08f, 0.14f)
        }
        Glyph.WB -> text("WB", 0.34f)
        Glyph.WB_DAYLIGHT -> {
            drawCircle(color, 0.13f * s, c)
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                line(0.21f * cos(a), 0.21f * sin(a), 0.32f * cos(a), 0.32f * sin(a))
            }
        }
        Glyph.WB_CLOUDY -> {
            val cloud = Path().apply {
                addOval(Rect(p(-0.3f, -0.02f), Size(0.26f * s, 0.24f * s)))
                addOval(Rect(p(-0.16f, -0.2f), Size(0.34f * s, 0.34f * s)))
                addOval(Rect(p(0.06f, -0.06f), Size(0.26f * s, 0.26f * s)))
                addRect(Rect(p(-0.17f, 0.04f), Size(0.36f * s, 0.18f * s)))
            }
            drawPath(cloud, color)
        }
        Glyph.WB_FLUORESCENT -> {
            drawRoundRect(color, topLeft = p(-0.3f, -0.07f), size = Size(0.6f * s, 0.14f * s), cornerRadius = CornerRadius(0.07f * s), style = stroke)
            for (x in listOf(-0.2f, 0f, 0.2f)) { line(x, -0.16f, x, -0.26f); line(x, 0.16f, x, 0.26f) }
        }
        Glyph.WB_TUNGSTEN -> {
            drawCircle(color, 0.17f * s, p(0f, -0.08f), style = stroke)
            line(-0.07f, 0.13f, 0.07f, 0.13f); line(-0.06f, 0.21f, 0.06f, 0.21f)
            path(-0.05f to -0.04f, 0f to -0.12f, 0.05f to -0.04f)
        }
        Glyph.EV -> {
            drawCircle(color, 0.3f * s, c, style = stroke)
            line(-0.2f, -0.06f, -0.04f, -0.06f); line(-0.12f, -0.14f, -0.12f, 0.02f)
            line(0.04f, 0.1f, 0.2f, 0.1f)
            line(-0.21f, 0.21f, 0.21f, -0.21f)
        }
        Glyph.SHUTTER -> {
            drawCircle(color, 0.3f * s, c, style = stroke)
            drawArc(color, -90f, 250f, useCenter = false, topLeft = p(-0.18f, -0.18f), size = Size(0.36f * s, 0.36f * s), style = Stroke(w * 1.6f, cap = StrokeCap.Butt))
        }
        Glyph.ISO -> {
            drawCircle(color, 0.3f * s, c, style = stroke)
            text("ISO", 0.22f)
        }
        Glyph.FOCUS -> {
            drawCircle(color, 0.3f * s, c, style = stroke)
            drawRoundRect(color, topLeft = p(-0.08f, -0.08f), size = Size(0.16f * s, 0.16f * s), cornerRadius = CornerRadius(0.03f * s), style = stroke)
        }
        Glyph.FOCUS_INFINITY -> text("∞", 0.5f, bold = false)
        Glyph.FOCUS_MANUAL -> text("MF", 0.3f)
        Glyph.SWITCH_LENS -> {
            drawArc(color, 200f, 140f, false, topLeft = p(-0.26f, -0.26f), size = Size(0.52f * s, 0.52f * s), style = stroke)
            drawArc(color, 20f, 140f, false, topLeft = p(-0.26f, -0.26f), size = Size(0.52f * s, 0.52f * s), style = stroke)
            path(0.18f to -0.24f, 0.25f to -0.13f, 0.12f to -0.1f)
            path(-0.18f to 0.24f, -0.25f to 0.13f, -0.12f to 0.1f)
        }
        Glyph.TIMER -> {
            drawCircle(color, 0.26f * s, p(0f, 0.04f), style = stroke)
            line(0f, 0.04f, 0f, -0.1f); line(-0.07f, -0.3f, 0.07f, -0.3f)
        }
    }
}

/** Centred text for glyphs that are words ("ISO", "WB", "A"). */
fun DrawScope.drawGlyphText(t: String, c: Offset, px: Float, color: Color, bold: Boolean) =
    drawCanvasText(t, c, px, color, if (bold) NordlysTypefaces.bold else NordlysTypefaces.regular)
