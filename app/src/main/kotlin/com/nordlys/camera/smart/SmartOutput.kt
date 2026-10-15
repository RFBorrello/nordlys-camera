package com.nordlys.camera.smart

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Display bitmap of a sequence frame, upright, longest side at most [maxSide]. */
fun YuvFrame.toPreviewBitmap(rotation: Int, maxSide: Int): Bitmap? {
    val jpeg = toJpegBytes(85)
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    if (rotation % 360 == 0) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
}

/** Library-ready JPEG of a composite, tagged with the orientation it was shot in. */
fun YuvFrame.toOrientedJpeg(rotation: Int, scratch: File): ByteArray {
    val jpeg = toJpegBytes(95)
    val tag = when ((rotation % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> return jpeg
    }
    return runCatching {
        val tmp = File.createTempFile("seq", ".jpg", scratch)
        try {
            tmp.writeBytes(jpeg)
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, tag.toString())
                setAttribute(ExifInterface.TAG_SOFTWARE, "Nordlys")
                saveAttributes()
            }
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }.getOrDefault(jpeg)
}
