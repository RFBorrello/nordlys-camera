package com.nordlys.camera.ui.gallery

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import com.nordlys.camera.living.LivingClip

/**
 * A living image's clip, laid exactly over its still (§8): when [play] turns on
 * (the pager has settled on the photo) the clip runs once, muted, then fades
 * into the still beneath. Turning [play] off (swiped away, zooming) hides it at
 * once; turning it on again replays from the start.
 */
@Composable
fun LivingClipOverlay(clip: LivingClip, play: Boolean) {
    val aspect = remember(clip.file) { clipAspect(clip) }
    val alpha = remember { Animatable(0f) }
    var run by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(play) {
        alpha.snapTo(0f)
        rendering = false; finished = false
        visible = play
        if (play) run++
    }
    // reveal only once a real frame is up, so the still never blinks
    LaunchedEffect(rendering) { if (rendering) alpha.snapTo(1f) }
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        alpha.animateTo(0f, tween(if (clip.flashFired) 150 else 300))
        visible = false
    }
    if (!visible) return

    androidx.compose.runtime.key(run) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val player = remember(run) { MediaPlayer() }
        DisposableEffect(player) { onDispose { runCatching { player.release() } } }
        AndroidView(
            modifier = Modifier.aspectRatio(aspect).graphicsLayer { this.alpha = alpha.value },
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            runCatching {
                                player.setSurface(Surface(st))
                                player.setDataSource(clip.file.absolutePath)
                                player.setVolume(0f, 0f)
                                player.setOnInfoListener { _, what, _ ->
                                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true
                                    false
                                }
                                player.setOnCompletionListener { finished = true }
                                player.setOnErrorListener { _, _, _ -> finished = true; true }
                                player.setOnPreparedListener { it.start() }
                                player.prepareAsync()
                            }.onFailure { finished = true }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
        )
    } }
}

/** Width / height of the clip as it should appear, honouring its rotation tag. */
private fun clipAspect(clip: LivingClip): Float = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(clip.file.absolutePath)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloat() ?: 0f
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloat() ?: 0f
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w <= 0f || h <= 0f) 3f / 4f else if (rot % 180 == 0) w / h else h / w
    } finally {
        r.release()
    }
}.getOrDefault(3f / 4f)
