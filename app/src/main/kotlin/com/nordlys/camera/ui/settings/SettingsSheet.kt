package com.nordlys.camera.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nordlys.camera.data.CameraPrefs
import com.nordlys.camera.model.AspectSetting
import com.nordlys.camera.model.DualCapture
import com.nordlys.camera.model.GridType
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroForegroundDim

/**
 * Settings page in the Metro idiom: a big light title, then pickers that show
 * their value and expand into a flat list of choices in place.
 */
@Composable
fun SettingsSheet(
    prefs: CameraPrefs,
    hasRaw: Boolean,
    onChange: (CameraPrefs) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text("NORDLYS CAMERA", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 18.dp))
            Text("settings", fontSize = 56.sp, fontWeight = FontWeight.Light, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(bottom = 18.dp))

            Section("photos")
            Picker(
                "Framing grid", prefs.grid,
                listOf(GridType.NONE to "off", GridType.THIRDS to "thirds", GridType.GOLDEN to "golden ratio", GridType.CROSSHAIR to "crosshair", GridType.SQUARE to "square"),
            ) { onChange(prefs.copy(grid = it)) }
            Picker(
                "Aspect ratio", prefs.aspect,
                listOf(AspectSetting.FOUR_THREE to "4:3", AspectSetting.SIXTEEN_NINE to "16:9"),
            ) { onChange(prefs.copy(aspect = it)) }
            Picker(
                "Capture format", prefs.dual,
                buildList {
                    add(DualCapture.OFF to "oversampled photo")
                    add(DualCapture.FULL_RES to "oversampled + full resolution")
                    if (hasRaw) add(DualCapture.DNG to "oversampled + DNG")
                },
            ) { onChange(prefs.copy(dual = it)) }
            Hint("Every photo is averaged down from the full sensor readout for lower noise. Keep the full-size file or the raw sensor data alongside it if you want to edit later.")
            Picker("Living images", prefs.living, listOf(false to "off", true to "on")) { onChange(prefs.copy(living = it)) }
            Hint("Keeps a moment of motion around each photo; it plays when you open the photo in the library.")

            Spacer(Modifier.height(12.dp))
            Action("reset camera settings", onReset)

            Section("about")
            Fact("Nordlys", "an independent, cleanroom camera for Android")
            Fact("Typeface", "Selawik, SIL Open Font License")
            Fact("Raw sensor", if (hasRaw) "available on this camera" else "not available on this camera")
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Light, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun <T> Picker(label: String, value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim)
        if (!open) {
            Text(
                options.firstOrNull { it.first == value }?.second ?: "",
                fontSize = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(Color(0xFF1F1F1F))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(Color(0xFF1F1F1F)).padding(vertical = 4.dp)) {
                options.forEach { (v, text) ->
                    Text(
                        text,
                        fontSize = 20.sp,
                        color = if (v == value) MetroAccent else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(v); open = false }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 20.sp, color = MetroAccent,
        modifier = Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun Fact(k: String, v: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim)
        Text(v, fontSize = 18.sp)
    }
}
