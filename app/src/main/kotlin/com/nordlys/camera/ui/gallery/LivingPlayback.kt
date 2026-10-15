package com.nordlys.camera.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nordlys.camera.living.LivingClip
import com.nordlys.camera.living.LivingReader
import com.nordlys.camera.ui.theme.MetroShutterRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Cycles a motion sidecar's frames while its page is showing. */
@Composable
fun LivingPlayback(clip: LivingClip, reader: LivingReader, active: Boolean) {
    val playing = true
    var frame by remember(clip.id) { mutableStateOf(0) }
    var bmp by remember(clip.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(clip.id, playing) {
        while (playing && active) {
            bmp = withContext(kotlinx.coroutines.Dispatchers.IO) { reader.decodeFrame(clip, frame) }
                ?: reader.decodeFrame(clip, 0)
            frame = (frame + 1) % clip.frameFiles.size
            delay(clip.intervalMs.toLong())
        }
    }

    // taps belong to the viewer (they show and hide its chrome); motion just loops
    Box(Modifier.fillMaxSize()) {
        val current = bmp
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Motion frame",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!playing) {
            Text(
                text = "Play",
                style = MaterialTheme.typography.titleSmall,
                color = MetroShutterRing,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
