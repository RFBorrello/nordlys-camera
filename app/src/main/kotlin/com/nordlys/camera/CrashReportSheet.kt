package com.nordlys.camera

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroForeground
import com.nordlys.camera.ui.theme.MetroForegroundDim
import java.io.File
import androidx.compose.runtime.remember

/**
 * Post-fault report sheet: shows the stored crash fault text on the next run
 * and allows sharing or clearing it. Device-local; no network by default.
 */
@Composable
fun CrashReportSheet(
    report: String,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xF60A0A0A),
        onClick = onClose,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            Text("Last fault", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Share this text, then the fault can be fixed precisely",
                style = MaterialTheme.typography.bodySmall,
                color = MetroForegroundDim,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Text(
                text = report.takeLast(6000),
                style = MaterialTheme.typography.bodySmall,
                color = MetroForeground,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(Color(0xFF161616))
                    .padding(10.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                SheetAction("Share") {
                    val send = Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Nordlys crash report")
                            putExtra(Intent.EXTRA_TEXT, report)
                        },
                        "Share fault",
                    )
                    context.startActivity(send)
                }
                Spacer(Modifier.padding(start = 16.dp))
                SheetAction("Clear", onClick = onClear)
                Spacer(Modifier.padding(start = 16.dp))
                SheetAction("Close", onClick = onClose)
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MetroAccent,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp, horizontal = 10.dp),
    )
}
