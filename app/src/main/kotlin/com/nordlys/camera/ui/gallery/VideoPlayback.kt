package com.nordlys.camera.ui.gallery

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordlys.camera.data.GalleryItem
import com.nordlys.camera.ui.theme.MetroShutterRing

/**
 * Plain MediaPlayer into a SurfaceView sized to the clip's displayed aspect
 * (rotation metadata applied), letter-boxed in the viewer; tap toggles pause.
 */
@Composable
fun VideoPlayback(item: GalleryItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(item.id) { MediaPlayer() }
    var playing by remember(item.id) { mutableStateOf(false) }
    val aspect = remember(item.id) { displayAspect(context, item) }

    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {
            if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
        },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player.setDisplay(holder)
                            runCatching {
                                player.setDataSource(context, item.uri)
                                player.isLooping = false
                                player.prepare()
                            }.onSuccess { playing = true; player.start() }
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { player.stop(); player.reset() } }
                    })
                }
            },
        )
        if (!playing) {
            Text(
                text = "Play",
                style = MaterialTheme.typography.titleSmall,
                color = MetroShutterRing,
                modifier = Modifier
                    .padding(16.dp)
                    .border(2.dp, MetroShutterRing, CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { playing = true; player.start() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    DisposableEffect(item.id) {
        onDispose { runCatching { player.release() } }
    }
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
