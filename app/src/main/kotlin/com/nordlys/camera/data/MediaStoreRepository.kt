package com.nordlys.camera.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** One browsable library entry, either photo or video. */
data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

/**
 * Read library through ContentResolver. No proprietary catalog logic —
 * plain MediaStore projections with composite fallbacks for missing dims.
 */
class MediaStoreRepository(private val context: Context) {

    fun loadPhotos(): List<GalleryItem> = query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        isVideo = false,
        extraProjection = emptyList(),
    )

    fun loadVideos(): List<GalleryItem> = query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        isVideo = true,
        extraProjection = listOf(MediaStore.Video.Media.DURATION),
    )

    /** Deletes an app-owned item directly; system items ask the user via API 30+ flow. */
    fun delete(item: GalleryItem): Boolean {
        val direct = runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }
        if (direct.getOrDefault(false)) return true
        return runCatching {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
            (context as? android.app.Activity)
                ?.startIntentSenderForResult(pi.intentSender, 7100, null, 0, 0, 0)
                ?: false
            true
        }.getOrDefault(false)
    }

    private fun query(uri: Uri, isVideo: Boolean, extraProjection: List<String>): List<GalleryItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            addAll(extraProjection)
        }
        val items = mutableListOf<GalleryItem>()
        context.contentResolver.query(
            uri,
            projection.toTypedArray(),
            null,
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val iDur = if (isVideo) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            while (c.moveToNext()) {
                items.add(
                    GalleryItem(
                        id = c.getLong(iId),
                        uri = ContentUris.withAppendedId(uri, c.getLong(iId)),
                        isVideo = isVideo,
                        displayName = c.getString(iName) ?: "",
                        sizeBytes = c.getLong(iSize),
                        dateModifiedSec = c.getLong(iDate),
                        width = if (iW >= 0) c.getInt(iW) else 0,
                        height = if (iH >= 0) c.getInt(iH) else 0,
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                    ),
                )
            }
        }
        return items
    }
}
