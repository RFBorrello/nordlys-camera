package com.nordlys.camera.ui.gallery

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nordlys.camera.data.GalleryItem
import com.nordlys.camera.ui.theme.MetroAccent
import kotlinx.coroutines.delay

/** Playback state for one clip, shared by its surface and the viewer's control bar. */
@Stable
class VideoController(val player: MediaPlayer) {
    var prepared by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var started by mutableStateOf(false)   // has playback ever begun (hides the big play button)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    private var resumeAfterScrub = false

    fun play() {
        if (!prepared) return
        if (positionMs >= durationMs - 50) player.seekTo(0)
        player.start(); playing = true; started = true
    }
    fun pause() { if (prepared && playing) { player.pause(); playing = false } }
    fun toggle() = if (playing) pause() else play()

    fun beginScrub() { resumeAfterScrub = playing; pause() }
    fun scrubTo(fraction: Float) {
        if (!prepared) return
        positionMs = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
        player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
    }
    fun endScrub() { if (resumeAfterScrub) play() }
}

/**
 * A clip in the viewer: its frame at the true aspect (rotation applied) and,
 * until playback starts, a large centred play button. Transport controls live
 * in the viewer chrome ([VideoControls]) so a tap can hide them with the rest.
 */
@Composable
fun VideoPlayback(item: GalleryItem, controller: VideoController, active: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val aspect = remember(item.id) { displayAspect(context, item) }
    val player = controller.player

    LaunchedEffect(active) { if (!active) controller.pause() }
    // ease the volume up when playback starts, like the reference viewer
    LaunchedEffect(controller.started) {
        if (!controller.started) return@LaunchedEffect
        val steps = 15
        for (i in 0..steps) {
            val v = 0.2f + 0.8f * i / steps
            runCatching { player.setVolume(v, v) }
            delay(100)
        }
    }
    LaunchedEffect(controller.playing) {
        while (controller.playing) {
            controller.positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(controller.positionMs)
            delay(100)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player.setDisplay(holder)
                            if (controller.prepared) return
                            runCatching {
                                player.setDataSource(context, item.uri)
                                player.prepare()
                                player.seekTo(1) // show the first frame
                            }.onSuccess {
                                controller.durationMs = player.duration.toLong()
                                controller.prepared = true
                                player.setOnCompletionListener {
                                    controller.playing = false
                                    controller.positionMs = controller.durationMs
                                }
                            }
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { controller.pause() }
                    })
                }
            },
        )
        if (!controller.started) {
            Canvas(
                Modifier
                    .size(100.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { controller.play() },
            ) {
                drawCircle(Color.Black.copy(alpha = 0.3f), size.minDimension / 2f)
                drawCircle(Color.White, size.minDimension / 2f - 2.dp.toPx(), style = Stroke(3.5f.dp.toPx()))
                drawPlayGlyph(center, size.minDimension * 0.34f, playing = false)
            }
        }
    }

    DisposableEffect(item.id) {
        onDispose { runCatching { player.release() } }
    }
}

/**
 * Transport for the current clip: ringed play/pause disc, a thin scrub line
 * with an accent fill and white knob, and "elapsed / total".
 */
@Composable
fun VideoControls(controller: VideoController, onInteract: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(74.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) { controller.toggle(); onInteract() },
        ) {
            drawCircle(Color.Black.copy(alpha = 0.25f), size.minDimension / 2f)
            drawCircle(Color.White, size.minDimension / 2f - 1.75f.dp.toPx(), style = Stroke(3.5f.dp.toPx()))
            drawPlayGlyph(center, size.minDimension * 0.32f, playing = controller.playing)
        }
        val fraction = if (controller.durationMs > 0) controller.positionMs / controller.durationMs.toFloat() else 0f
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 12.dp)
                .pointerInput(controller) {
                    detectTapGestures { p -> controller.scrubTo(p.x / size.width); onInteract() }
                }
                .pointerInput(controller) {
                    detectHorizontalDragGestures(
                        onDragStart = { p -> controller.beginScrub(); controller.scrubTo(p.x / size.width); onInteract() },
                        onDragEnd = { controller.endScrub(); onInteract() },
                        onDragCancel = { controller.endScrub() },
                    ) { change, _ -> controller.scrubTo(change.position.x / size.width); change.consume() }
                },
        ) {
            val y = size.height / 2f
            val track = 4.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), track)
            drawLine(MetroAccent, Offset(0f, y), Offset(size.width * fraction, y), track)
            drawCircle(Color.White, 9.dp.toPx(), Offset(size.width * fraction, y))
        }
        Text(
            "${clock(controller.positionMs)} / ${clock(controller.durationMs)}",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayGlyph(c: Offset, s: Float, playing: Boolean) {
    if (playing) {
        val w = s * 0.28f
        drawRect(Color.White, Offset(c.x - s * 0.42f, c.y - s / 2f), androidx.compose.ui.geometry.Size(w, s))
        drawRect(Color.White, Offset(c.x + s * 0.14f, c.y - s / 2f), androidx.compose.ui.geometry.Size(w, s))
    } else {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x - s * 0.36f, c.y - s / 2f)
            lineTo(c.x + s * 0.5f, c.y)
            lineTo(c.x - s * 0.36f, c.y + s / 2f)
            close()
        }
        drawPath(path, Color.White)
    }
}

private fun clock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}

/** Width / height as the clip is meant to be seen, honouring its rotation tag. */
private fun displayAspect(context: android.content.Context, item: GalleryItem): Float = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, item.uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloat() ?: 0f
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloat() ?: 0f
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w <= 0f || h <= 0f) 16f / 9f else if (rot % 180 == 0) w / h else h / w
    } finally {
        r.release()
    }
}.getOrDefault(16f / 9f)
