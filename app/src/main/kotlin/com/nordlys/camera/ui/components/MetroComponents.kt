package com.nordlys.camera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroPivotInactive

/**
 * Metro-style header pivot: flat text tabs separated by corrective spacing,
 * animated accent tint on the selected item.
 */
@Composable
fun MetroPivot(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, label ->
            val color by animateColorAsState(
                targetValue = if (index == selected) MetroAccent else MetroPivotInactive,
                label = "pivot-color",
            )
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light),
                color = color,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Flat rectangular Metro chip used for binary toggles in the settings sheet. */
@Composable
fun MetroChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
) {
    val bg = if (selected) MetroAccent else Color(0xFF222222)
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = (height - 18.dp).coerceAtLeast(0.dp)),
    )
}

/** Flat Metro proportional bar used for EV / focus ladders. */
@Composable
fun MetroBar(
    fraction: Float, // 0..1
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.clip(CircleShapeRect)) {
        val h = size.height
        drawRect(Color(0xFF2A2A2A), size = size)
        drawRect(MetroAccent, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
    }
}

private val CircleShapeRect = androidx.compose.ui.graphics.RectangleShape
