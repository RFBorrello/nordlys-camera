package com.nordlys.camera.ui.smart

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nordlys.camera.smart.SmartCompositor
import com.nordlys.camera.smart.YuvFrame
import com.nordlys.camera.smart.toOrientedJpeg
import com.nordlys.camera.smart.toPreviewBitmap
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroForegroundDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SmartTool(val title: String, val hint: String) {
    BEST("best shot", "Swipe through the sequence and keep the moment you like."),
    ACTION("action shot", "Pick the frames whose subject should appear in the picture."),
    MOTION("motion focus", "Tap the moving subject to keep it sharp while the rest streaks."),
    REMOVE("remove moving", "People and cars passing through are taken out of the scene."),
}

/**
 * Editor for a smart sequence: one shot, several outcomes. Results are
 * computed off the main thread and saved as ordinary photos.
 */
@Composable
fun SmartEditorScreen(
    frames: List<YuvFrame>,
    rotation: Int,
    onSave: suspend (ByteArray) -> Boolean,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = frames.size / 2
    var tool by remember { mutableStateOf(SmartTool.BEST) }
    var thumbs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var stabilised by remember { mutableStateOf<List<YuvFrame>?>(null) }
    var background by remember { mutableStateOf<YuvFrame?>(null) }
    var bestIndex by remember { mutableStateOf(ref) }
    val actionPicks = remember { mutableStateListOf<Int>().apply { addAll(frames.indices.filter { it % 2 == 0 }) } }
    var subject by remember { mutableStateOf(0.5f to 0.5f) }
    var result by remember { mutableStateOf<YuvFrame?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(frames) {
        withContext(Dispatchers.Default) {
            thumbs = frames.mapNotNull { it.toPreviewBitmap(rotation, 240) }
            val st = SmartCompositor.stabilise(frames, ref)
            stabilised = st
            background = SmartCompositor.removeMoving(st)
        }
    }

    LaunchedEffect(tool, bestIndex, actionPicks.toList(), subject, stabilised) {
        val st = stabilised ?: return@LaunchedEffect
        busy = true
        saved = false
        val out = withContext(Dispatchers.Default) {
            when (tool) {
                SmartTool.BEST -> frames[bestIndex]
                SmartTool.REMOVE -> background ?: SmartCompositor.removeMoving(st)
                SmartTool.ACTION -> SmartCompositor.actionShot(st, actionPicks.toList(), background ?: SmartCompositor.removeMoving(st))
                SmartTool.MOTION -> {
                    // subject was tapped on the upright preview; map back to sensor orientation
                    val (u, v) = uprightToSensor(subject.first, subject.second, rotation)
                    SmartCompositor.motionFocus(frames, ref, u, v)
                }
            }
        }
        result = out
        preview = withContext(Dispatchers.Default) { out.toPreviewBitmap(rotation, 1600) }
        busy = false
    }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Text("SMART SEQUENCE", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp)) {
                SmartTool.entries.forEach { t ->
                    Text(
                        t.title,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Light,
                        color = if (t == tool) Color.White else Color(0xFF6E6E6E),
                        modifier = Modifier
                            .clickable(remember { MutableInteractionSource() }, indication = null) { tool = t }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Text(tool.hint, style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim, modifier = Modifier.padding(horizontal = 20.dp))

            var boxSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .onSizeChanged { boxSize = it }
                    .pointerInput(tool, preview) {
                        detectTapGestures { p ->
                            val bmp = preview ?: return@detectTapGestures
                            if (tool != SmartTool.MOTION) return@detectTapGestures
                            // invert ContentScale.Fit to image coordinates
                            val scale = minOf(boxSize.width / bmp.width.toFloat(), boxSize.height / bmp.height.toFloat())
                            val iw = bmp.width * scale
                            val ih = bmp.height * scale
                            val u = ((p.x - (boxSize.width - iw) / 2f) / iw).coerceIn(0f, 1f)
                            val v = ((p.y - (boxSize.height - ih) / 2f) / ih).coerceIn(0f, 1f)
                            subject = u to v
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (busy) Text("processing…", fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(12.dp))
            }

            if (tool == SmartTool.BEST || tool == SmartTool.ACTION) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    thumbs.forEachIndexed { i, t ->
                        val on = if (tool == SmartTool.BEST) i == bestIndex else i in actionPicks
                        Image(
                            t.asImageBitmap(), null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .border(if (on) 3.dp else 0.dp, if (on) MetroAccent else Color.Transparent)
                                .clickable {
                                    if (tool == SmartTool.BEST) bestIndex = i
                                    else if (i in actionPicks) { if (actionPicks.size > 1) actionPicks.remove(i) } else actionPicks.add(i)
                                },
                        )
                    }
                }
            } else Spacer(Modifier.height(64.dp))

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                AppBarText("discard", onClose)
                Spacer(Modifier.width(24.dp))
                AppBarText(if (saved) "saved" else "save") {
                    val r = result ?: return@AppBarText
                    if (busy || saved) return@AppBarText
                    scope.launch {
                        busy = true
                        val bytes = withContext(Dispatchers.Default) { r.toOrientedJpeg(rotation, context.cacheDir) }
                        saved = onSave(bytes)
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBarText(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 20.sp, color = Color.White,
        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(8.dp),
    )
}

/** Upright display coordinates → the sensor-oriented frame coordinates. */
private fun uprightToSensor(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
    90 -> v to 1f - u
    180 -> 1f - u to 1f - v
    270 -> 1f - v to u
    else -> u to v
}
