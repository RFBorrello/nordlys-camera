package com.nordlys.camera.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroForeground
import com.nordlys.camera.ui.theme.MetroForegroundDim

/**
 * Settings surface: about facts, control reset, capture-format facts.
 * Cleanroom implementation; every value here is authored for Nordlys.
 */
@Composable
fun SettingsSheet(
    onReset: () -> Unit,
    hasRaw: Boolean,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xF6101010),
        onClick = onClose,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.padding(vertical = 12.dp))

            Text("About", style = MaterialTheme.typography.titleMedium, color = MetroAccent)
            SettingRow("Name", "Nordlys")
            SettingRow("Version", "0.1.0 (pre-release)")
            SettingRow("Design", "Metro system, Nordic colorway")
            SettingRow("Raw sensor", if (hasRaw) "Supported for DNG stills" else "Not on this lens")
            SettingRow("Typeface", "Selawik, SIL Open Font License")

            Spacer(Modifier.padding(vertical = 12.dp))
            Text("Controls", style = MaterialTheme.typography.titleMedium, color = MetroAccent)
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = "Reset wheel, formats, timers, and lens state to defaults",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetroForegroundDim,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Reset now",
                    style = MaterialTheme.typography.titleSmall,
                    color = MetroAccent,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onReset,
                        )
                        .padding(vertical = 6.dp, horizontal = 10.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            Text(
                text = "Close",
                style = MaterialTheme.typography.titleMedium,
                color = MetroForeground,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    )
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.titleSmall, color = MetroForegroundDim, modifier = Modifier.weight(0.4f))
        Text(v, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
