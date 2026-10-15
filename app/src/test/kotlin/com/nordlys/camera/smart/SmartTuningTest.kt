package com.nordlys.camera.smart

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Off-device tuning harness for the smart-sequence composites. Skipped unless
 * NORDLYS_SEQ names one or more sequence dumps (see CameraEngine.dumpSequence),
 * each optionally followed by the upright subject point for motion focus:
 *
 *   NORDLYS_SEQ="/path/seqA@0.4,0.5:/path/seqB" NORDLYS_OUT=/tmp/out ./gradlew :app:testDebugUnitTest --tests '*SmartTuning*'
 *
 * NORDLYS_MASKS lists low,high pairs for the motion mask ("14,34;10,26").
 */
class SmartTuningTest {

    @Test
    fun renderComposites() = runBlocking {
        val spec = System.getenv("NORDLYS_SEQ")
        assumeTrue("set NORDLYS_SEQ to run the tuning harness", !spec.isNullOrBlank())
        val out = File(System.getenv("NORDLYS_OUT") ?: "build/smart-tuning").apply { mkdirs() }
        val masks = (System.getenv("NORDLYS_MASKS") ?: "14,34").split(';').map { p ->
            p.split(',').let { it[0].toFloat() to it[1].toFloat() }
        }
        for (entry in spec!!.split(':')) {
            val (path, subject) = entry.split('@').let { it[0] to it.getOrNull(1) }
            val dir = File(path)
            val rotation = File(dir, "meta.txt").readLines().first { it.startsWith("rotation=") }.substringAfter('=').toInt()
            val frames = dir.listFiles { f -> f.name.endsWith(".i420") }!!
                .sortedBy { it.name.substringAfter('f').substringBefore('-').toInt() }
                .map(::load)
            val ref = frames.size / 2
            val tag = dir.name

            repeat(2) { pass -> // the first pass warms the JIT
                val t0 = System.nanoTime()
                val st = SmartCompositor.stabilise(frames, ref)
                val t1 = System.nanoTime()
                SmartCompositor.removeMoving(st)
                val t2 = System.nanoTime()
                SmartCompositor.actionShot(st, frames.indices.toList())
                val t3 = System.nanoTime()
                if (pass == 1) println(
                    "$tag timing ms: stabilise ${(t1 - t0) / 1_000_000}, median ${(t2 - t1) / 1_000_000}, " +
                        "action ${(t3 - t2) / 1_000_000} (${Runtime.getRuntime().availableProcessors()} cores)",
                )
            }

            frames.forEachIndexed { i, f -> if (i != ref) println("$tag motion f$i: ${SmartCompositor.cameraMotion(frames[ref], f)}") }
            val st = SmartCompositor.stabilise(frames, ref)
            val bg = SmartCompositor.removeMoving(st)
            save(bg, rotation, File(out, "$tag-remove.ppm"))
            for ((lo, hi) in masks) {
                SmartCompositor.maskLow = lo
                SmartCompositor.maskHigh = hi
                save(SmartCompositor.actionShot(st, frames.indices.filter { it % 2 == 0 }, bg), rotation, File(out, "$tag-action-${lo.toInt()}-${hi.toInt()}.ppm"))
            }
            subject?.let { s ->
                val (u, v) = s.split(',').map(String::toFloat)
                val (su, sv) = uprightToSensor(u, v, rotation)
                save(SmartCompositor.motionFocus(frames, ref, su, sv), rotation, File(out, "$tag-motion.ppm"))
            }
        }
    }

    private fun load(f: File): YuvFrame {
        val (w, h) = Regex("(\\d+)x(\\d+)").find(f.name)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
        val b = f.readBytes()
        val c = w * h / 4
        return YuvFrame(w, h, b.copyOfRange(0, w * h), b.copyOfRange(w * h, w * h + c), b.copyOfRange(w * h + c, w * h + 2 * c))
    }

    private fun save(f: YuvFrame, rotation: Int, file: File) {
        val w = f.width
        val h = f.height
        val upright90 = rotation % 180 == 90
        val ow = if (upright90) h else w
        val oh = if (upright90) w else h
        val rgb = ByteArray(ow * oh * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val yy = (f.y[y * w + x].toInt() and 0xFF).toFloat()
            val ci = (y / 2) * (w / 2) + x / 2
            val u = (f.u[ci].toInt() and 0xFF) - 128f
            val v = (f.v[ci].toInt() and 0xFF) - 128f
            val (ox, oy) = when (rotation) {
                90 -> (h - 1 - y) to x
                180 -> (w - 1 - x) to (h - 1 - y)
                270 -> y to (w - 1 - x)
                else -> x to y
            }
            val o = (oy * ow + ox) * 3
            rgb[o] = (yy + 1.402f * v).toInt().coerceIn(0, 255).toByte()
            rgb[o + 1] = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255).toByte()
            rgb[o + 2] = (yy + 1.772f * u).toInt().coerceIn(0, 255).toByte()
        }
        file.outputStream().use { it.write("P6\n$ow $oh\n255\n".toByteArray()); it.write(rgb) }
    }
}
