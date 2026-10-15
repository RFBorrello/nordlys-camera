package com.nordlys.camera.ui.viewfinder

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Bridges the Camera2 engine to a Compose-hosted SurfaceView whose buffer is
 * pinned to [bufferSize] (sensor orientation); the view itself is laid out at
 * the matching upright aspect, so the preview is never stretched.
 * The raw surface keeps full manual control and RAW streams available.
 */
@Composable
fun CameraPreviewSurface(
    bufferSize: Size,
    modifier: Modifier = Modifier,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceGone: () -> Unit,
) {
    val ready by rememberUpdatedState(onSurfaceReady)
    val gone by rememberUpdatedState(onSurfaceGone)
    key(bufferSize) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setKeepScreenOn(true)
                    holder.setFixedSize(bufferSize.width, bufferSize.height)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            val sized = (width == bufferSize.width && height == bufferSize.height) ||
                                (width == bufferSize.height && height == bufferSize.width)
                            if (sized) ready(holder.surface)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = gone()
                    })
                }
            },
        )
    }
}
