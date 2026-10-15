package com.nordlys.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MetroColors = darkColorScheme(
    primary = MetroAccent,
    onPrimary = Color.White,
    secondary = MetroForeground,
    onSecondary = MetroBackground,
    background = MetroBackground,
    onBackground = MetroForeground,
    surface = MetroSurface,
    onSurface = MetroForeground,
    surfaceDim = MetroSurfaceDim,
    onSurfaceVariant = MetroForegroundDim,
    outline = MetroForegroundDim,
)

@Composable
fun NordlysTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MetroColors,
        typography = MetroTypography,
        content = content,
    )
}
