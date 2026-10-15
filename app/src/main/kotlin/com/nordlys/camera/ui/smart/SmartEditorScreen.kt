package com.nordlys.camera.ui.smart

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nordlys.camera.smart.FaceFinder
import com.nordlys.camera.smart.FaceSpot
import com.nordlys.camera.smart.SmartCompositor
import com.nordlys.camera.smart.YuvFrame
import com.nordlys.camera.smart.toOrientedJpeg
import com.nordlys.camera.smart.toPreviewBitmap
import com.nordlys.camera.smart.uprightToSensor
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
    FACES("change faces", "Tap a face, then pick how it looks from the strip below."),
}

/** Long side of the upright frames used for face detection and face chips. */
private const val FACE_SIDE = 640

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
    var uprights by remember { mutableStateOf<List<Bitmap>?>(null) }
    var faces by remember { mutableStateOf<List<FaceSpot>?>(null) }
    var pickedFace by remember { mutableStateOf<Int?>(null) }
    val faceChoice = remember { mutableStateMapOf<Int, Int>() }
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

    // faces are looked for only once the tool is opened: it costs one detector pass per frame
    LaunchedEffect(tool == SmartTool.FACES, stabilised) {
        val st = stabilised ?: return@LaunchedEffect
        if (tool != SmartTool.FACES || faces != null) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val up = st.map { it.toPreviewBitmap(rotation, FACE_SIDE) ?: return@withContext }
            uprights = up
            faces = FaceFinder.across(up)
        }
    }

    LaunchedEffect(tool, bestIndex, actionPicks.toList(), subject, stabilised, faces, faceChoice.toMap()) {
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
                SmartTool.FACES -> {
                    val base = st[bestIndex]
                    val found = faces.orEmpty()
                    val swaps = faceChoice.toMap().mapNotNull { (k, frame) ->
                        found.getOrNull(k)?.takeIf { frame != bestIndex }
                            ?.let { FaceFinder.swapFor(it, frame, base.width, base.height, rotation) }
                    }
                    if (swaps.isEmpty()) base else SmartCompositor.changeFaces(base, st, swaps)
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
            val hint = when {
                tool != SmartTool.FACES -> tool.hint
                faces == null -> "Looking for faces…"
                faces!!.isEmpty() -> "No faces were found in this sequence."
                pickedFace == null -> tool.hint
                else -> "Pick the frame this face should come from."
            }
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim, modifier = Modifier.padding(horizontal = 20.dp))

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
                            if (tool != SmartTool.MOTION && tool != SmartTool.FACES) return@detectTapGestures
                            // invert ContentScale.Fit to image coordinates
                            val scale = minOf(boxSize.width / bmp.width.toFloat(), boxSize.height / bmp.height.toFloat())
                            val iw = bmp.width * scale
                            val ih = bmp.height * scale
                            val u = ((p.x - (boxSize.width - iw) / 2f) / iw).coerceIn(0f, 1f)
                            val v = ((p.y - (boxSize.height - ih) / 2f) / ih).coerceIn(0f, 1f)
                            if (tool == SmartTool.MOTION) subject = u to v
                            else {
                                // distance in displayed pixels from the tap to each face's centre
                                fun reach(f: FaceSpot) = kotlin.math.hypot(f.u * iw - u * iw, f.v * ih + f.eyes * iw * 0.45f - v * ih)
                                pickedFace = faces.orEmpty().withIndex()
                                    .filter { (_, f) -> reach(f) < f.eyes * iw * 1.8f }
                                    .minByOrNull { (_, f) -> reach(f) }?.index
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                val shown = preview
                if (tool == SmartTool.FACES && shown != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val scale = minOf(size.width / shown.width, size.height / shown.height)
                        val iw = shown.width * scale
                        val ih = shown.height * scale
                        val left = (size.width - iw) / 2f
                        val top = (size.height - ih) / 2f
                        faces.orEmpty().forEachIndexed { k, f ->
                            val on = k == pickedFace
                            drawCircle(
                                color = if (on) MetroAccent else Color.White.copy(alpha = 0.7f),
                                radius = f.eyes * iw * 1.5f,
                                center = Offset(left + f.u * iw, top + (f.v + f.eyes * 0.45f * iw / ih) * ih),
                                style = Stroke(width = if (on) 3.dp.toPx() else 1.5.dp.toPx()),
                            )
                        }
                    }
                }
                if (busy) Text("processing…", fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(12.dp))
            }

            val chipsFor = pickedFace?.let { faces?.getOrNull(it) }
            val ups = uprights
            if (tool == SmartTool.FACES && chipsFor != null && ups != null) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val k = pickedFace!!
                    val chosen = faceChoice[k] ?: bestIndex
                    ups.forEachIndexed { i, up -> faceChip(up, chipsFor)?.let { chip ->
                        val on = i == chosen
                        Image(
                            chip.asImageBitmap(), null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .border(if (on) 3.dp else 0.dp, if (on) MetroAccent else Color.Transparent)
                                .clickable { faceChoice[k] = i },
                        )
                    } }
                }
            } else if (tool == SmartTool.BEST || tool == SmartTool.ACTION || tool == SmartTool.FACES) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    thumbs.forEachIndexed { i, t ->
                        val on = if (tool == SmartTool.ACTION) i in actionPicks else i == bestIndex
                        Image(
                            t.asImageBitmap(), null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .border(if (on) 3.dp else 0.dp, if (on) MetroAccent else Color.Transparent)
                                .clickable {
                                    if (tool != SmartTool.ACTION) bestIndex = i
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

private val chipCache = java.util.WeakHashMap<Bitmap, MutableMap<FaceSpot, Bitmap>>()

/** A square crop around [face] from one upright frame, for the face picker strip. */
private fun faceChip(upright: Bitmap, face: FaceSpot): Bitmap? = synchronized(chipCache) {
    chipCache.getOrPut(upright) { HashMap() }.getOrPut(face) {
        val side = (face.eyes * upright.width * 3f).toInt().coerceIn(16, minOf(upright.width, upright.height))
        val x = (face.u * upright.width - side / 2f).toInt().coerceIn(0, upright.width - side)
        val y = (face.v * upright.height - side * 0.4f).toInt().coerceIn(0, upright.height - side)
        Bitmap.createBitmap(upright, x, y, side, side)
    }
}
