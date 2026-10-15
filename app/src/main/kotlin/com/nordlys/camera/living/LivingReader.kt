package com.nordlys.camera.living

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** Reads motion sidecars produced by [LivingWriter]. */
class LivingReader(private val context: Context) {

    fun clipFor(itemUri: Uri): LivingClip? {
        val indexText = readFile(File(rootDir(), "index.json")) ?: return null
        val dirId = runCatching {
            JSONObject(indexText).optString(itemUri.toString(), "").takeIf { it.isNotEmpty() }
        }.getOrNull() ?: return null
        return load(dirId)
    }

    fun deleteClipFor(itemUri: Uri) {
        val indexText = readFile(File(rootDir(), "index.json")) ?: return
        val manifest = runCatching { JSONObject(indexText) }.getOrNull() ?: return
        val dirId = manifest.optString(itemUri.toString(), "").takeIf { it.isNotEmpty() } ?: return
        LivingWriter(context).removeClip(dirId)
    }

    fun load(dirId: String): LivingClip? {
        val dir = File(rootDir(), dirId)
        val manifest = readFile(File(dir, "manifest.json")) ?: return null
        val o = runCatching { JSONObject(manifest) }.getOrNull() ?: return null
        val names = o.optJSONArray("frames") ?: return null
        val files = (0 until names.length()).map { names.getString(it) }
        if (files.isEmpty()) return null
        return LivingClip(
            id = dirId,
            directoryPath = dir.absolutePath,
            frameFiles = files,
            intervalMs = o.optInt("intervalMs", 100),
        )
    }

    /** File is IO-bound; call from an IO coroutine. */
    fun decodeFrame(clip: LivingClip, index: Int): Bitmap? {
        val f = File(clip.directoryPath, clip.frameFiles[index])
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeByteArray(f.readBytes(), 0, f.length().toInt()) }.getOrNull()
    }

    private fun rootDir(): File = File(context.filesDir, "living")

    private fun readFile(f: File): String? =
        if (f.exists()) runCatching { f.readText() }.getOrNull() else null
}
