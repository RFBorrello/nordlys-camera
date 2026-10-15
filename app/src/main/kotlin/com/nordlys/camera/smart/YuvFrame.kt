package com.nordlys.camera.smart

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * One compacted YUV_420_888 frame retained during burst processing.
 * All three planes are normalized here: each row holds exactly
 * `width` (Y) or `width / 2` (U, V) bytes with pixel stride 1,
 * so later stages index planes as flat arrays without stride math.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val tsMs: Long = 0L,
) {
    companion object {
        fun from(image: Image): YuvFrame {
            require(image.format == ImageFormat.YUV_420_888) {
                "smart core expects YUV_420_888, got ${image.format}"
            }
            val w = image.width
            val h = image.height
            val p = image.planes
            val frame = YuvFrame(
                width = w,
                height = h,
                y = compact(p[0], rows = h, rowLen = w),
                u = compact(p[1], rows = h / 2, rowLen = w / 2),
                v = compact(p[2], rows = h / 2, rowLen = w / 2),
                tsMs = if (image.timestamp > 0) image.timestamp / 1_000_000 else System.currentTimeMillis(),
            )
            image.close()
            return frame
        }

        /** Fast path for already-tight planes; stride-aware fallback otherwise. */
        private fun compact(plane: Image.Plane, rows: Int, rowLen: Int): ByteArray {
            val out = ByteArray(rows * rowLen)
            val src = plane.buffer
            val total = rows * rowLen
            if (plane.pixelStride == 1 && plane.rowStride == rowLen &&
                src.position() == 0 && src.remaining() >= total
            ) {
                src.get(out)
                src.rewind()
                return out
            }
            var o = 0
            for (r in 0 until rows) {
                val rowStart = r * plane.rowStride
                if (plane.pixelStride == 1) {
                    for (c in 0 until rowLen) out[o + c] = src.get(rowStart + c)
                } else {
                    for (c in 0 until rowLen) out[o + c] = src.get(rowStart + c * plane.pixelStride)
                }
                o += rowLen
            }
            return out
        }
    }

    /** Interleaves V/U into NV21 for (YuvImage) JPEG rendering of the final frame. */
    fun toNv21(): ByteArray {
        val nv21 = ByteArray(width * height * 3 / 2)
        y.copyInto(nv21, 0, 0, y.size)
        val halfChroma = u.size
        var o = y.size
        for (i in 0 until halfChroma) {
            nv21[o++] = v[i]
            nv21[o++] = u[i]
        }
        return nv21
    }

    fun toJpegBytes(quality: Int = 92): ByteArray {
        val yuvImage = YuvImage(toNv21(), ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }
}
