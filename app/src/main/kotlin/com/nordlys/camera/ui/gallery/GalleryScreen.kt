package com.nordlys.camera.ui.gallery

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import coil.compose.AsyncImage
import com.nordlys.camera.data.GalleryItem
import com.nordlys.camera.data.MediaStoreRepository
import com.nordlys.camera.living.LivingReader
import com.nordlys.camera.ui.components.MetroPivot
import com.nordlys.camera.ui.theme.MetroAccent
import com.nordlys.camera.ui.theme.MetroForeground
import com.nordlys.camera.ui.theme.MetroForegroundDim
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gallery pivots over the shared library: photos and videos,
 * detail viewer with information sheet and later-edit actions.
 */
@Composable
fun GalleryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { MediaStoreRepository(context) }
    val scope = rememberCoroutineScope()

    var pivot by remember { mutableStateOf(0) }
    var photos by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var videos by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var openIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pivot) {
        launch { photos = repo.loadPhotos() }
        launch { videos = repo.loadVideos() }
    }

    if (openIndex != null) {
        val list = if (pivot == 0) photos else videos
        ViewerScreen(
            items = list,
            startIndex = openIndex!!,
            repo = repo,
            reader = remember { LivingReader(context) },
            onClose = { openIndex = null },
            onDeleted = { r: MediaStoreRepository ->
                scope.launch {
                    photos = repo.loadPhotos(); videos = repo.loadVideos()
                }
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            MetroPivot(
                items = listOf("Gallery", "Videos"),
                selected = pivot,
                onSelect = { pivot = it },
                modifier = Modifier.padding(start = 16.dp),
            )
            val list = if (pivot == 0) photos else videos
            if (list.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Empty for now", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.padding(6.dp))
                    Text("Photos land here as you take them", style = MaterialTheme.typography.bodyMedium, color = MetroForegroundDim)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    gridItems(list) { item ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clickable { openIndex = list.indexOf(item) },
                        ) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.isVideo) {
                                Text(
                                    text = formatDuration(item.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MetroForeground,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000)).padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerScreen(
    items: List<GalleryItem>,
    startIndex: Int,
    repo: MediaStoreRepository,
    reader: LivingReader,
    onClose: () -> Unit,
    onDeleted: (MediaStoreRepository) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var infoOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableStateOf(0L) }
    val controllers = remember { androidx.compose.runtime.mutableStateMapOf<Long, VideoController>() }
    val current = items.getOrNull(pagerState.currentPage)
    val currentVideo = current?.let { controllers[it.id] }
    androidx.activity.compose.BackHandler(enabled = !infoOpen && !confirmDelete, onBack = onClose)

    // tap shows/hides everything, system bars included, so the picture can stand alone
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(chrome) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome) show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            else hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            (view.context as? android.app.Activity)?.window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, view).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // while a clip plays, the chrome steps aside after five quiet seconds
    LaunchedEffect(chrome, currentVideo?.playing, touchedAt) {
        if (chrome && currentVideo?.playing == true) {
            kotlinx.coroutines.delay(5000)
            chrome = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            val clip = remember(item.id) { if (item.isVideo) null else reader.clipFor(item.uri) }
            val toggle = { chrome = !chrome; touchedAt = System.currentTimeMillis() }
            Box(
                Modifier.fillMaxSize().then(
                    if (item.isVideo || clip != null) Modifier.pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) } else Modifier,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isVideo) {
                    val controller = remember(item.id) { VideoController(android.media.MediaPlayer()) }
                    androidx.compose.runtime.DisposableEffect(item.id) {
                        controllers[item.id] = controller
                        onDispose { controllers.remove(item.id) }
                    }
                    VideoPlayback(item, controller, active = pagerState.currentPage == page, chromeVisible = chrome, onInteract = { touchedAt = System.currentTimeMillis() })
                }
                else if (clip != null) {
                    LivingPlayback(clip = clip, reader = reader, active = pagerState.currentPage == page)
                }
                else ZoomableImage(
                    item.uri, item.displayName,
                    onZoomedChange = { z -> zoomed = z; if (z) chrome = false },
                    onTap = toggle,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = chrome,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
        ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MetroForeground,
                    modifier = Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (items.isEmpty()) "" else "${pagerState.currentPage + 1} / ${items.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MetroForegroundDim,
                )
            }
            Spacer(Modifier.weight(1f))
            currentVideo?.let { c ->
                if (c.started) VideoControls(c, onInteract = { touchedAt = System.currentTimeMillis() }, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ViewerAction("Share") {
                    val item = items.getOrNull(pagerState.currentPage) ?: return@ViewerAction
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = context.contentResolver.getType(item.uri) ?: "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, item.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share with"))
                }
                ViewerAction("Edit later") { /* open with external editor chooser – Phase 3+ */ }
                ViewerAction("Remove") { confirmDelete = true }
                ViewerAction("Details") { infoOpen = true }
            }
        }
        }

        if (infoOpen) {
            val item = items.getOrNull(pagerState.currentPage) ?: return@ViewerScreen
            InfoSheet(
                item = item,
                onDismiss = { infoOpen = false },
            )
        }
        if (confirmDelete) {
            val item = items.getOrNull(pagerState.currentPage)
            DeleteConfirm(onConfirm = {
                item?.let {
                    if (!it.isVideo) runCatching { reader.deleteClipFor(it.uri) }
                    repo.delete(it)
                }
                confirmDelete = false
                onDeleted(repo)
                if (items.isEmpty()) onClose()
            }, onDismiss = { confirmDelete = false })
        }
    }
}

@Composable
private fun ViewerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MetroForeground,
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** Flat information sheet: capture facts, dimensions, storage footprint. */
@Composable
private fun InfoSheet(item: GalleryItem, onDismiss: () -> Unit) {
    val name = remember(item) { item.displayName }
    val dims = remember(item) { if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else "1-unknown" }
    val size = remember(item) { formatBytes(item.sizeBytes) }
    val dt = remember(item) {
        SimpleDateFormat("EEE d MMM yyyy  HH:mm", Locale.getDefault()).format(Date(item.dateModifiedSec * 1000))
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xF0101010),
        onClick = onDismiss,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Text("Details", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.padding(10.dp))
            InfoRow("Name", name)
            InfoRow("Lens pixels", dims)
            InfoRow("File size", size)
            InfoRow("Captured", dt)
            InfoRow("Kind", if (item.isVideo) "Clip" else "Photo")
            Spacer(Modifier.weight(1f))
            Text("Close", style = MaterialTheme.typography.titleMedium, color = MetroAccent,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss).padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, style = MaterialTheme.typography.titleSmall, color = MetroForegroundDim, modifier = Modifier.weight(0.34f))
        Text(v, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Metro-styled flat confirm. */
@Composable
private fun DeleteConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xE60A0A0A),
        onClick = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).systemBarsPadding(), verticalArrangement = Arrangement.Center) {
            Text("Remove from library?", style = MaterialTheme.typography.headlineMedium)
            Text(
                "The media file will be gone from all library surfaces",
                style = MaterialTheme.typography.bodyMedium,
                color = MetroForegroundDim,
                modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Keep", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss).padding(10.dp),
                    color = MetroForeground)
                Text("Remove", style = MaterialTheme.typography.titleSmall, color = MetroAccent,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onConfirm).padding(10.dp))
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
