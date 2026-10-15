package com.nordlys.camera.ui.gallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nordlys.camera.data.GalleryItem
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroForegroundDim
import com.nordlys.camera.video.ClipTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val STRIP_FRAMES = 8
private const val MIN_SPAN_MS = 1000L

/**
 * Basic trimmer for a clip (§9): preview on top, a film strip with start and
 * end handles below, and "save copy", which writes the selection as a new clip
 * without re-encoding. The original stays as it was.
 */
@Composable
fun TrimmerScreen(item: GalleryItem, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    val meta = remember(item.id) { clipMeta(context, item) }
    val durationMs = meta.durationMs.coerceAtLeast(1L)
    var startF by remember { mutableFloatStateOf(0f) }
    var endF by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var strip by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    val player = remember(item.id) { MediaPlayer() }
    DisposableEffect(item.id) { onDispose { runCatching { player.release() } } }

    LaunchedEffect(item.id) {
        strip = withContext(Dispatchers.IO) { filmStrip(context, item, durationMs) }
    }
    // play the selection only, then park on its start
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val endMs = (endF * durationMs).toLong()
        while (playing) {
            val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            if (pos >= endMs || !player.isPlaying) {
                runCatching { player.pause(); player.seekTo((startF * durationMs).toInt()) }
                playing = false
            }
            delay(30)
        }
    }

    fun seekPreview(f: Float) {
        if (!prepared) return
        if (playing) { runCatching { player.pause() }; playing = false }
        runCatching { player.seekTo((f * durationMs).toLong(), MediaPlayer.SEEK_CLOSEST) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Text("trim", fontSize = 56.sp, fontWeight = FontWeight.Light, color = Color.White, modifier = Modifier.padding(top = 12.dp))
        Text(item.displayName, style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim)

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.aspectRatio(meta.aspect),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                player.setDisplay(holder)
                                if (prepared) return
                                runCatching {
                                    player.setDataSource(context, item.uri)
                                    player.prepare()
                                    player.seekTo(1)
                                }.onSuccess { prepared = true }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { player.pause() }; playing = false }
                        })
                    }
                },
            )
        }

        // film strip with the two handles; the parts left out are dimmed
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            Row(Modifier.fillMaxSize()) {
                if (strip.isEmpty()) Box(Modifier.fillMaxSize().background(Color(0xFF1F1F1F)))
                strip.forEach { bmp ->
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            val minSpan = (MIN_SPAN_MS.toFloat() / durationMs).coerceAtMost(1f)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs) {
                        var grabbing = 0 // -1 start, 1 end
                        detectHorizontalDragGestures(
                            onDragStart = { p ->
                                val f = p.x / widthPx
                                grabbing = if (abs(f - startF) <= abs(f - endF)) -1 else 1
                            },
                            onDragEnd = { grabbing = 0 },
                            onDragCancel = { grabbing = 0 },
                        ) { change, _ ->
                            val f = (change.position.x / widthPx).coerceIn(0f, 1f)
                            if (grabbing < 0) { startF = f.coerceAtMost(endF - minSpan).coerceAtLeast(0f); seekPreview(startF) }
                            else if (grabbing > 0) { endF = f.coerceAtLeast(startF + minSpan).coerceAtMost(1f); seekPreview(endF) }
                            change.consume()
                        }
                    },
            ) {
                val sx = startF * size.width
                val ex = endF * size.width
                val dim = Color.Black.copy(alpha = 0.65f)
                drawRect(dim, Offset.Zero, Size(sx, size.height))
                drawRect(dim, Offset(ex, 0f), Size(size.width - ex, size.height))
                val edge = 3.dp.toPx()
                drawRect(MetroAccent, Offset(sx, 0f), Size(ex - sx, edge))
                drawRect(MetroAccent, Offset(sx, size.height - edge), Size(ex - sx, edge))
                val handle = 10.dp.toPx()
                drawRect(MetroAccent, Offset(sx - handle / 2f, 0f), Size(handle, size.height))
                drawRect(MetroAccent, Offset(ex - handle / 2f, 0f), Size(handle, size.height))
            }
        }

        val startMs = (startF * durationMs).toLong()
        val endMs = (endF * durationMs).toLong()
        Text(
            "${stamp(startMs)}  –  ${stamp(endMs)}     ${"%.1f".format((endMs - startMs) / 1000f)} s",
            fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            message ?: "The copy starts at the nearest key frame at or before the start mark.",
            style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim, modifier = Modifier.padding(top = 4.dp),
        )

        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TrimAction(if (playing) "pause" else "play", enabled = prepared && !saving) {
                if (playing) { runCatching { player.pause() }; playing = false }
                else runCatching {
                    player.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                    player.start(); playing = true
                }
            }
            TrimAction(if (saving) "saving…" else "save copy", enabled = !saving && (startF > 0f || endF < 1f)) {
                saving = true
                runCatching { player.pause() }; playing = false
                scope.launch {
                    val uri = ClipTrimmer.trim(context, item.uri, startMs * 1000, endMs * 1000)
                    saving = false
                    if (uri != null) onSaved() else message = "Couldn't save the trimmed copy."
                }
            }
            TrimAction("cancel", enabled = !saving, onClick = onClose)
        }
    }
}

@Composable
private fun TrimAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 20.sp,
        color = if (enabled) Color.White else MetroForegroundDim,
        modifier = Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

private class ClipMeta(val durationMs: Long, val aspect: Float)

private fun clipMeta(context: android.content.Context, item: GalleryItem): ClipMeta = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, item.uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloat() ?: 0f
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloat() ?: 0f
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: item.durationMs
        ClipMeta(dur, if (w <= 0f || h <= 0f) 16f / 9f else if (rot % 180 == 0) w / h else h / w)
    } finally {
        r.release()
    }
}.getOrDefault(ClipMeta(item.durationMs, 16f / 9f))

private fun filmStrip(context: android.content.Context, item: GalleryItem, durationMs: Long): List<Bitmap> = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, item.uri)
        (0 until STRIP_FRAMES).mapNotNull { i ->
            val t = durationMs * 1000 * (2 * i + 1) / (2 * STRIP_FRAMES)
            r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                val scale = 160f / maxOf(full.width, full.height)
                Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
                    .also { if (it !== full) full.recycle() }
            }
        }
    } finally {
        r.release()
    }
}.getOrDefault(emptyList())

private fun stamp(ms: Long): String {
    val tenths = ms / 100
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}
