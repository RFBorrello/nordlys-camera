package com.nordlys.camera.ui.viewfinder

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Bridges the Camera2 engine to a Compose-hosted SurfaceView.
 * The raw surface is required for full manual control and RAW streams;
 * PreviewView (CameraX) is intentionally not used by Nordlys.
 */
@Composable
fun CameraPreviewSurface(
    modifier: Modifier = Modifier,
    onSurfaceReady: (android.view.Surface) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setKeepScreenOn(true)
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
                        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                            onSurfaceReady(holder.surface)
                        }
                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                    })
                }
            },
            update = {},
        )
    }
}
