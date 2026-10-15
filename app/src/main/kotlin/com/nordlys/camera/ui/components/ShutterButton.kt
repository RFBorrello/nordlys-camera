package com.nordlys.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nordlys.camera.ui.theme.MetroShutterRing

/** Metro shutter: plain ring around a disc, pressed state tinted. */
@Composable
fun ShutterButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressing: Boolean = false,
    onClick: () -> Unit,
) {
    val ring = if (pressing) MetroShutterRing.copy(alpha = 0.6f) else MetroShutterRing
    val disc = if (pressing) Color.White else Color(0x00000000)
    Box(
        modifier = modifier
            .size(84.dp)
            .border(width = 3.dp, color = ring, shape = CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        androidx.compose.foundation.layout.Spacer(
            Modifier
                .size(64.dp)
                .border(width = 2.dp, color = ring.copy(alpha = 0.4f), shape = CircleShape),
        )
        Box(
            Modifier
                .size(56.dp)
                .background(disc, CircleShape),
        )
    }
}
