package com.nordlys.camera.ui.gallery

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.nordlys.camera.data.GalleryItem
import com.nordlys.camera.ui.theme.MetroShutterRing

/** Plain MediaPlayer into a TextureView-backed surface; tap toggles pause. */
@Composable
fun VideoPlayback(item: GalleryItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(item.id) { MediaPlayer() }
    var playing by remember(item.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) {
        if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
    }) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player.setDisplay(holder)
                            player.setDataSource(context, item.uri)
                            player.isLooping = false
                            runCatching { player.prepare() }
                                .onSuccess { playing = true; player.start() }
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { player.stop(); player.reset() }
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { playing = true; player.start() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    DisposableEffect(item.id) {
        onDispose { runCatching { player.release() } }
    }
}
