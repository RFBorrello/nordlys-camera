package com.nordlys.camera.living

import android.content.Context
import android.net.Uri
import com.nordlys.camera.smart.YuvFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Writes motion sidecars next to a still: app-private `living/<id>/` holds
 * the encoded frames and a cadence manifest; a single `living/index.json`
 * maps the gallery URI to its sidecar directory.
 */
class LivingWriter(private val context: Context) {

    suspend fun write(uri: Uri, payload: LivingPayload): LivingClip? = withContext(Dispatchers.IO) {
        val root = rootDir().apply { mkdirs() }
        val id = UUID.randomUUID().toString().substringBefore('-')
        val dir = File(root, id).apply { mkdirs() }

        val files = mutableListOf<String>()
        payload.frames.forEachIndexed { i, frame ->
            val name = "frame_%03d.jpg".format(i)
            try {
                File(dir, name).outputStream().use { it.write(frame.toJpegBytes()) }
                files.add(name)
            } catch (_: Throwable) {
                // a lost frame shrinks the clip, not the still
            }
        }
        if (files.size < 4) { // too little motion to be worth keeping
            dir.deleteRecursively(); return@withContext null
        }

        val interval = cadenceMs(payload.frames)
        val manifest = JSONObject().apply {
            put("version", 1)
            put("uri", uri.toString())
            put("intervalMs", interval)
            put("frames", JSONArray(files))
        }
        File(dir, MANIFEST).writeText(manifest.toString())

        val index = readIndex()
        index[uri.toString()] = id
        File(root, INDEX).writeText(JSONObject(index).toString())
        LivingClip(
            id = id,
            directoryPath = dir.absolutePath,
            frameFiles = files,
            intervalMs = interval,
        )
    }

    fun removeClip(dirId: String) {
        val root = rootDir()
        File(root, dirId).deleteRecursively()
        val index = readIndex().filterValues { it != dirId }
        runCatching { File(root, INDEX).writeText(JSONObject(index).toString()) }
    }

    private fun cadenceMs(frames: List<YuvFrame>): Int {
        val ts = frames.map { it.tsMs }.filter { it > 0 }
        if (ts.size < 2) return 100
        val span = ts.last() - ts.first()
        return ((span / (ts.size - 1)).coerceIn(33, 600)).toInt()
    }

    private fun rootDir(): File = File(context.filesDir, "living")

    private fun readIndex(): MutableMap<String, String> {
        val f = File(rootDir(), INDEX)
        val out = mutableMapOf<String, String>()
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> out[k] = o.getString(k) }
            }
        }
        return out
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val INDEX = "index.json"
    }
}
