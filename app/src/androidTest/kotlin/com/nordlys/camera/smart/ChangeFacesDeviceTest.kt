package com.nordlys.camera.smart

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the change-faces pipeline on a sequence dumped by the app
 * (files/sequences/<id>, see CameraEngine.dumpSequence), on the device itself:
 *
 *   adb shell am instrument -w -e seq <id> -e base 3 -e from 0 \
 *     com.nordlys.camera.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Writes before/after JPEGs to files/faces-out/ and logs timings under NordlysFaces.
 */
@RunWith(AndroidJUnit4::class)
class ChangeFacesDeviceTest {

    @Test
    fun changeFacesOnDumpedSequence() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("seq")
        assumeTrue("pass -e seq <id>", id != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null)!!
        val dir = File(root, "sequences/$id")
        val rotation = File(dir, "meta.txt").readLines().first { it.startsWith("rotation=") }.substringAfter('=').toInt()
        val frames = dir.listFiles { f -> f.name.endsWith(".i420") }!!
            .sortedBy { it.name.substringAfter('f').substringBefore('-').toInt() }
            .map { f ->
                val (w, h) = Regex("(\\d+)x(\\d+)").find(f.name)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
                val b = f.readBytes()
                val c = w * h / 4
                YuvFrame(w, h, b.copyOfRange(0, w * h), b.copyOfRange(w * h, w * h + c), b.copyOfRange(w * h + c, w * h + 2 * c))
            }
        val base = args.getString("base")?.toInt() ?: (frames.size / 2)
        val from = args.getString("from")?.toInt() ?: 0
        val out = File(root, "faces-out").apply { mkdirs() }

        var t = SystemClock.elapsedRealtime()
        val st = SmartCompositor.stabilise(frames, frames.size / 2)
        Log.i(TAG, "stabilise ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        val ups = st.map { it.toPreviewBitmap(rotation, 640)!! }
        Log.i(TAG, "upright previews ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        val perFrame = ups.map { FaceFinder.detect(it).size }
        val faces = FaceFinder.across(ups)
        Log.i(TAG, "detect: per frame $perFrame, merged ${faces.size} in ${SystemClock.elapsedRealtime() - t} ms (both passes)")
        faces.forEach { Log.i(TAG, "face u=%.3f v=%.3f eyes=%.3f frames=%s".format(it.u, it.v, it.eyes, it.frames.sorted())) }
        assumeTrue("no faces found", faces.isNotEmpty())

        t = SystemClock.elapsedRealtime()
        val swaps = faces.map { FaceFinder.swapFor(it, from, frames[0].width, frames[0].height, rotation) }
        val result = SmartCompositor.changeFaces(st[base], st, swaps)
        Log.i(TAG, "changeFaces ${SystemClock.elapsedRealtime() - t} ms, swaps=$swaps")

        save(st[base], rotation, File(out, "$id-base$base.jpg"))
        save(st[from], rotation, File(out, "$id-source$from.jpg"))
        save(result, rotation, File(out, "$id-changed-$base-from-$from.jpg"))
    }

    private fun save(f: YuvFrame, rotation: Int, file: File) {
        val bmp = f.toPreviewBitmap(rotation, 1440)!!
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    private companion object { const val TAG = "NordlysFaces" }
}
