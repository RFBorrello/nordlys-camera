package com.nordlys.camera.smart

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.hypot

/**
 * A face in upright display space, normalized to the picture: ([u], [v]) is the
 * point between the eyes and [eyes] the eye distance as a fraction of the width.
 */
data class FaceSpot(val u: Float, val v: Float, val eyes: Float, val frames: Set<Int> = emptySet())

/** Finds faces across a sequence with the platform's [FaceDetector]. */
object FaceFinder {

    private const val MAX_FACES = 8
    private const val MIN_CONFIDENCE = 0.3f

    /** Faces in one upright picture; the detector wants RGB_565 and an even width. */
    fun detect(upright: Bitmap): List<FaceSpot> {
        val w = upright.width and 1.inv()
        val h = upright.height
        if (w < 32 || h < 32) return emptyList()
        val rgb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        android.graphics.Canvas(rgb).drawBitmap(upright, 0f, 0f, null)
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = runCatching { FaceDetector(w, h, MAX_FACES).findFaces(rgb, found) }.getOrDefault(0)
        rgb.recycle()
        val mid = PointF()
        return (0 until count).mapNotNull { i ->
            val f = found[i] ?: return@mapNotNull null
            if (f.confidence() < MIN_CONFIDENCE) return@mapNotNull null
            f.getMidPoint(mid)
            FaceSpot(mid.x / w, mid.y / h, f.eyesDistance() / w)
        }
    }

    /**
     * Detects on every (stabilised, upright) frame and merges detections of the
     * same person, so a face that blinked or turned in one frame is still found
     * from the others.
     */
    suspend fun across(uprights: List<Bitmap>): List<FaceSpot> {
        val perFrame = coroutineScope { uprights.map { b -> async(Dispatchers.Default) { detect(b) } }.awaitAll() }
        val aspect = uprights.firstOrNull()?.let { it.height.toFloat() / it.width } ?: 1f
        val merged = ArrayList<MutableList<Pair<Int, FaceSpot>>>()
        perFrame.forEachIndexed { fi, spots ->
            for (s in spots) {
                val home = merged.firstOrNull { group ->
                    val g = group[0].second
                    hypot(g.u - s.u, (g.v - s.v) * aspect) < maxOf(g.eyes, s.eyes) * 1.2f
                }
                if (home != null) home.add(fi to s) else merged.add(mutableListOf(fi to s))
            }
        }
        return merged.map { group ->
            val n = group.size
            FaceSpot(
                u = group.sumOf { it.second.u.toDouble() }.toFloat() / n,
                v = group.sumOf { it.second.v.toDouble() }.toFloat() / n,
                eyes = group.maxOf { it.second.eyes },
                frames = group.mapTo(HashSet()) { it.first },
            )
        }.sortedBy { it.u }
    }

    /**
     * The blend ellipse for [face] in sensor pixels of a [w]×[h] frame shot at
     * [rotation]: centred a little below the eyes, about one head across.
     */
    fun swapFor(face: FaceSpot, frame: Int, w: Int, h: Int, rotation: Int): SmartCompositor.FaceSwap {
        val upright90 = (rotation % 180 + 180) % 180 == 90
        val uprightW = if (upright90) h else w
        val uprightH = if (upright90) w else h
        val ed = face.eyes * uprightW
        val cu = face.u
        val cv = (face.v * uprightH + ed * 0.45f) / uprightH
        val (su, sv) = uprightToSensor(cu, cv, rotation)
        val rxUp = ed * 1.35f
        val ryUp = ed * 1.85f
        return SmartCompositor.FaceSwap(
            frame = frame,
            cx = su * w,
            cy = sv * h,
            rx = if (upright90) ryUp else rxUp,
            ry = if (upright90) rxUp else ryUp,
        )
    }
}

/** Upright display coordinates (normalized) → the sensor-oriented frame coordinates. */
fun uprightToSensor(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
    90 -> v to 1f - u
    180 -> 1f - u to 1f - v
    270 -> 1f - v to u
    else -> u to v
}
