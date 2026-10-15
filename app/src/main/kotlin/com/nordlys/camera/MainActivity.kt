package com.nordlys.camera

import android.Manifest
import android.content.pm.PackageManager
import java.io.File
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nordlys.camera.ui.gallery.GalleryScreen
import com.nordlys.camera.ui.theme.NordlysTheme
import com.nordlys.camera.ui.viewfinder.ViewfinderScreen

/** Minimal in-app destinations: camera shell and the media library. */
sealed interface Destination {
    data object Camera : Destination
    data object Library : Destination
}

class MainActivity : ComponentActivity() {

    private var cameraGranted by mutableStateOf(false)
    private var audioGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            cameraGranted = result[Manifest.permission.CAMERA] == true || hasCamera()
            audioGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        cameraGranted = hasCamera()
        audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wanted = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!audioGranted) add(Manifest.permission.RECORD_AUDIO)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
        setContent {
            NordlysTheme {
                // self-reported fault surface (no ADB required)
                var crashReport by rememberSaveable { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val f = File(filesDir, "crash.log")
                    if (f.exists() && !File(filesDir, "crash.seen").exists()) {
                        runCatching { crashReport = f.readText() }
                    }
                }
                if (crashReport != null) {
                    CrashReportSheet(
                        report = crashReport!!,
                        onClear = {
                            runCatching {
                                File(filesDir, "crash.log").delete()
                                File(filesDir, "crash.seen").writeText("seen")
                            }
                            crashReport = null
                        },
                        onClose = {
                            runCatching { File(filesDir, "crash.seen").writeText("seen") }
                            crashReport = null
                        },
                    )
                } else {
                    var destination by rememberSaveable { mutableStateOf<Destination>(Destination.Camera) }
                    when (destination) {
                        Destination.Camera -> ViewfinderScreen(
                            hasPermission = cameraGranted,
                            audioGranted = audioGranted,
                            onOpenGallery = { destination = Destination.Library },
                        )
                        Destination.Library -> GalleryScreen(onClose = { destination = Destination.Camera })
                    }
                }
            }
        }
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
