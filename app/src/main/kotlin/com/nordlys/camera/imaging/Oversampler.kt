package com.nordlys.camera.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Oversampled stills: the full sensor readout is averaged down to a smaller
 * file so each output pixel integrates several photosites. Noise falls with
 * the averaging and edges stay clean without heavy sharpening.
 *
 * The heavy lifting is the JPEG decoder's own DCT-domain scaling
 * (`inSampleSize`), which averages whole 8×8 blocks rather than dropping
 * pixels; any remaining non-power-of-two factor is finished with a filtered
 * resample. The output is rotated upright so no EXIF orientation is needed.
 */
object Oversampler {

    /** Output megapixels for a sensor of [sourcePixels]: at least 1.5× linear oversampling, 5–12 MP. */
    fun targetPixels(sourcePixels: Long): Long {
        val quarter = sourcePixels / 4
        val fromRatio = (sourcePixels / 2.25).toLong()
        return max(5_000_000L, quarter).coerceAtMost(minOf(12_000_000L, fromRatio))
    }

    data class Result(val jpeg: ByteArray, val width: Int, val height: Int)

    /**
     * @param exif metadata copied onto the output (exposure, ISO, timestamps).
     * @param scratch a writable directory for the EXIF round trip.
     */
    fun oversample(sourceJpeg: ByteArray, scratch: File, quality: Int = 94): Result? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val srcPixels = srcW.toLong() * srcH
        val target = targetPixels(srcPixels)
        val linear = sqrt(srcPixels.toDouble() / target)
        // small sensors keep their native file: oversampling them would cost too much resolution
        if (linear < 1.2 || target < 5_000_000L) return null

        var sample = 1
        while (sample * 2 <= linear) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            sourceJpeg, 0, sourceJpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val outW = (srcW / linear).roundToInt()
        val outH = (srcH / linear).roundToInt()
        val sourceExif = runCatching { ExifInterface(ByteArrayInputStream(sourceJpeg)) }.getOrNull()
        val rotation = when (sourceExif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        val averaged = if (decoded.width == outW && decoded.height == outH) decoded
        else areaResample(decoded, outW, outH).also { decoded.recycle() }
        val shaped = if (rotation == 0) averaged else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(averaged, 0, 0, averaged.width, averaged.height, m, false)
                .also { if (it !== averaged) averaged.recycle() }
        }

        val bytes = ByteArrayOutputStream(shaped.width * shaped.height / 4).also {
            shaped.compress(Bitmap.CompressFormat.JPEG, quality, it)
        }.toByteArray()
        val w = shaped.width
        val h = shaped.height
        shaped.recycle()

        return Result(copyExif(sourceExif, bytes, scratch), w, h)
    }

    /**
     * Area-average resample: every output pixel is the coverage-weighted mean
     * of the source pixels under its footprint. Streams one source row at a
     * time so memory stays at a few rows regardless of sensor size.
     */
    fun areaResample(src: Bitmap, outW: Int, outH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val fx = srcW.toDouble() / outW
        val fy = srcH.toDouble() / outH

        // horizontal footprints: for each output column, its source span and edge weights
        val spanStart = IntArray(outW)
        val spanEnd = IntArray(outW)      // exclusive
        val wFirst = FloatArray(outW)
        val wLast = FloatArray(outW)
        for (ox in 0 until outW) {
            val a = ox * fx
            val b = (ox + 1) * fx
            val s = a.toInt()
            val e = minOf(srcW, kotlin.math.ceil(b).toInt())
            spanStart[ox] = s
            spanEnd[ox] = e
            wFirst[ox] = (minOf(b, s + 1.0) - a).toFloat()
            wLast[ox] = if (e - 1 > s) (b - (e - 1)).toFloat() else 0f
        }
        val invArea = (1.0 / (fx * fy)).toFloat()

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val row = IntArray(srcW)
        val hRow = FloatArray(outW * 3)
        var acc = FloatArray(outW * 3)
        var next = FloatArray(outW * 3)
        val outRow = IntArray(outW)
        var oy = 0

        fun flush() {
            for (ox in 0 until outW) {
                val i = ox * 3
                val r = (acc[i] * invArea + 0.5f).toInt().coerceIn(0, 255)
                val g = (acc[i + 1] * invArea + 0.5f).toInt().coerceIn(0, 255)
                val bl = (acc[i + 2] * invArea + 0.5f).toInt().coerceIn(0, 255)
                outRow[ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
            out.setPixels(outRow, 0, outW, 0, oy, outW, 1)
            val t = acc; acc = next; next = t
            java.util.Arrays.fill(next, 0f)
            oy++
        }

        for (sy in 0 until srcH) {
            src.getPixels(row, 0, srcW, 0, sy, srcW, 1)
            for (ox in 0 until outW) {
                var r = 0f; var g = 0f; var bl = 0f
                val s = spanStart[ox]
                val e = spanEnd[ox]
                for (sx in s until e) {
                    val w = when (sx) { s -> wFirst[ox]; e - 1 -> wLast[ox]; else -> 1f }
                    val p = row[sx]
                    r += w * ((p shr 16) and 0xFF)
                    g += w * ((p shr 8) and 0xFF)
                    bl += w * (p and 0xFF)
                }
                val i = ox * 3
                hRow[i] = r; hRow[i + 1] = g; hRow[i + 2] = bl
            }
            // vertical: this source row covers [sy, sy+1) → output coords [sy/fy, (sy+1)/fy)
            val boundary = (oy + 1) * fy
            if (sy + 1 <= boundary + 1e-9) {
                for (i in hRow.indices) acc[i] += hRow[i]
                if (sy + 1 >= boundary - 1e-9 && oy < outH) flush()
            } else {
                val wa = (boundary - sy).toFloat()
                val wb = 1f - wa
                for (i in hRow.indices) {
                    acc[i] += hRow[i] * wa
                    next[i] += hRow[i] * wb
                }
                if (oy < outH) flush()
            }
        }
        while (oy < outH) flush()
        return out
    }

    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    )

    private fun copyExif(source: ExifInterface?, jpeg: ByteArray, scratch: File): ByteArray {
        if (source == null) return jpeg
        return runCatching {
            val tmp = File.createTempFile("ovs", ".jpg", scratch)
            try {
                tmp.writeBytes(jpeg)
                val out = ExifInterface(tmp.absolutePath)
                for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { out.setAttribute(tag, it) }
                out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                out.setAttribute(ExifInterface.TAG_SOFTWARE, "Nordlys")
                out.saveAttributes()
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        }.getOrDefault(jpeg)
    }
}
