package com.nordlys.camera.smart

import com.nordlys.camera.smart.BlockAligner
import kotlin.math.abs

/**
 * Temporal fusion of an aligned frame stack.
 *
 * Fresh implementation: every output luminance pixel is the median of the
 * aligned contributor stack (reference + shifted candidates). The median
 * rejects both sensor grain and residual ghost pixels; chroma retains the
 * reference frame so fusion cost stays proportional to luminance only.
 */
object TemporalMerge {

    fun merge(reference: YuvFrame, helpers: List<YuvFrame>, offsets: List<IntArray>): YuvFrame {
        val w = reference.width
        val h = reference.height
        val blocksX = w / BlockAligner.BLOCK
        val blocksY = h / BlockAligner.BLOCK
        val outY = ByteArray(reference.y.size)
        val stack = IntArray(helpers.size + 1)

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val offsetIdx = (by * blocksX + bx) * 2
                val dx = if (offsets.isEmpty()) 0 else offsets[offsetIdx][0]
                val dy = if (offsets.isEmpty()) 0 else offsets[offsetIdx][1]

                val x0 = bx * BlockAligner.BLOCK
                val y0 = by * BlockAligner.BLOCK
                val y1 = (y0 + BlockAligner.BLOCK).coerceAtMost(h)
                val x1 = (x0 + BlockAligner.BLOCK).coerceAtMost(w)
                for (row in y0 until y1) {
                    val refRow = row * w
                    for (col in x0 until x1) {
                        // build the contributor stack
                        var n = 1
                        stack[0] = reference.y[refRow + col].toInt() and 0xFF
                        for (f in helpers.indices) {
                            val sx = (col + dx).coerceIn(0, w - 1)
                            val sy = (row + dy).coerceIn(0, h - 1)
                            stack[n++] = helpers[f].y[sy * w + sx].toInt() and 0xFF
                        }
                        // insertion sort; tiny stack keeps this cheap
                        for (i in 1 until n) {
                            val v = stack[i]
                            var j = i - 1
                            while (j >= 0 && stack[j] > v) {
                                stack[j + 1] = stack[j]; j--
                            }
                            stack[j + 1] = v
                        }
                        outY[refRow + col] = stack[n / 2].toByte()
                    }
                }
            }
        }

        return YuvFrame(
            width = reference.width,
            height = reference.height,
            y = outY,
            u = reference.u,
            v = reference.v,
        )
    }
}
