package com.nordlys.camera.living

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A photo's motion sidecar: a short muted MP4 recorded around the shutter press. */
data class LivingClip(val file: File, val flashFired: Boolean)

/**
 * Living-image sidecars live in app-private `living/`, one MP4 per photo, so they
 * stay out of the shared gallery (the reference hides them behind an extension the
 * gallery ignores). `living/index.json` maps a library URI to its clip.
 */
class LivingStore(private val context: Context) {

    /** A fresh file for the recorder to write into before the photo has a URI. */
    fun newClipFile(): File = File(rootDir().apply { mkdirs() }, UUID.randomUUID().toString().substringBefore('-') + ".mp4")

    @Synchronized
    fun attach(uri: Uri, clip: File, flashFired: Boolean) {
        val index = readIndex()
        index.put(uri.toString(), JSONObject().put("file", clip.name).put("flash", flashFired))
        File(rootDir(), INDEX).writeText(index.toString())
    }

    fun clipFor(uri: Uri): LivingClip? {
        val entry = readIndex().optJSONObject(uri.toString()) ?: return null
        val f = File(rootDir(), entry.optString("file"))
        return if (f.isFile && f.length() > 0) LivingClip(f, entry.optBoolean("flash")) else null
    }

    @Synchronized
    fun deleteClipFor(uri: Uri) {
        val index = readIndex()
        index.optJSONObject(uri.toString())?.let { File(rootDir(), it.optString("file")).delete() }
        index.remove(uri.toString())
        runCatching { File(rootDir(), INDEX).writeText(index.toString()) }
    }

    /**
     * Drops clips nobody points at, and the frame-folder sidecars of the first
     * Living Images pass (they are not clips and nothing plays them any more).
     */
    @Synchronized
    fun sweep() {
        val root = rootDir()
        if (!root.isDirectory) return
        val index = readIndex()
        val live = mutableSetOf<String>()
        index.keys().asSequence().toList().forEach { k ->
            val name = index.optJSONObject(k)?.optString("file")
            if (name.isNullOrEmpty()) index.remove(k) else live += name
        }
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively()
            else if (f.name.endsWith(".mp4") && f.name !in live && System.currentTimeMillis() - f.lastModified() > 60_000) f.delete()
        }
        runCatching { File(root, INDEX).writeText(index.toString()) }
    }

    private fun rootDir(): File = File(context.filesDir, "living")

    private fun readIndex(): JSONObject {
        val f = File(rootDir(), INDEX)
        return if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) else JSONObject()
    }

    companion object { private const val INDEX = "index.json" }
}
