package com.nordlys.camera.smart

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Creative composites over a timed sequence (docs/LUMIA_PARITY.md §5).
 * Every routine works on stabilised frames: each frame is first shifted onto
 * the reference by its dominant (camera) translation, so only things that
 * really moved in the scene differ from frame to frame.
 */
object SmartCompositor {

    /** Frames shifted onto [ref]'s framing by their dominant translation. */
    fun stabilise(frames: List<YuvFrame>, ref: Int): List<YuvFrame> {
        val reference = frames[ref]
        return frames.mapIndexed { i, f ->
            if (i == ref) f else {
                val (dx, dy) = dominantShift(reference, f)
                shift(f, dx, dy)
            }
        }
    }

    /** Median of the stabilised stack: anything that does not stay put long enough disappears. */
    fun removeMoving(frames: List<YuvFrame>): YuvFrame {
        val w = frames[0].width
        val h = frames[0].height
        val n = frames.size
        val stack = IntArray(n)
        fun medianPlane(pick: (YuvFrame) -> ByteArray, size: Int): ByteArray {
            val out = ByteArray(size)
            val planes = frames.map(pick)
            for (i in 0 until size) {
                for (k in 0 until n) stack[k] = planes[k][i].toInt() and 0xFF
                insertionSort(stack, n)
                out[i] = stack[n / 2].toByte()
            }
            return out
        }
        return YuvFrame(w, h, medianPlane({ it.y }, w * h), medianPlane({ it.u }, w * h / 4), medianPlane({ it.v }, w * h / 4))
    }

    /**
     * Stroboscopic action shot: the clean background with the moving subject
     * pasted in from every selected frame, in order.
     */
    fun actionShot(frames: List<YuvFrame>, selected: List<Int>, background: YuvFrame = removeMoving(frames)): YuvFrame {
        val w = background.width
        val h = background.height
        val y = background.y.copyOf()
        val u = background.u.copyOf()
        val v = background.v.copyOf()
        for (i in selected.sorted()) {
            val f = frames[i]
            val mask = motionMask(f, background)
            blendInto(y, u, v, f, mask, w, h)
        }
        return YuvFrame(w, h, y, u, v)
    }

    /**
     * Motion focus: frames are aligned on the subject at ([cx], [cy]) (normalized)
     * instead of the background, then averaged. The subject stays crisp while the
     * rest smears along the motion; the reference subject is restored on top.
     */
    fun motionFocus(frames: List<YuvFrame>, ref: Int, cx: Float, cy: Float): YuvFrame {
        val reference = frames[ref]
        val w = reference.width
        val h = reference.height
        val box = (min(w, h) * 0.22f).toInt().coerceAtLeast(32)
        val x0 = (cx * w - box / 2).toInt().coerceIn(0, w - box)
        val y0 = (cy * h - box / 2).toInt().coerceIn(0, h - box)
        val aligned = frames.mapIndexed { i, f ->
            if (i == ref) f else {
                val (dx, dy) = regionShift(reference, f, x0, y0, box, radius = max(w, h) / 10)
                shift(f, dx, dy)
            }
        }
        val n = aligned.size
        fun mean(pick: (YuvFrame) -> ByteArray, size: Int): ByteArray {
            val out = ByteArray(size)
            val planes = aligned.map(pick)
            for (i in 0 until size) {
                var sum = 0
                for (p in planes) sum += p[i].toInt() and 0xFF
                out[i] = (sum / n).toByte()
            }
            return out
        }
        val y = mean({ it.y }, w * h)
        val u = mean({ it.u }, w * h / 4)
        val v = mean({ it.v }, w * h / 4)

        // restore the subject from the reference with a soft elliptical edge
        val rx = box * 0.9f
        val ry = box * 0.9f
        val ccx = x0 + box / 2f
        val ccy = y0 + box / 2f
        for (row in 0 until h) for (col in 0 until w) {
            val d = hypot((col - ccx) / rx, (row - ccy) / ry)
            if (d >= 1f) continue
            val a = smooth(1f - d, 0f, 0.35f)
            val i = row * w + col
            y[i] = mix(y[i], reference.y[i], a)
            if ((row and 1) == 0 && (col and 1) == 0) {
                val ci = (row / 2) * (w / 2) + col / 2
                u[ci] = mix(u[ci], reference.u[ci], a)
                v[ci] = mix(v[ci], reference.v[ci], a)
            }
        }
        return YuvFrame(w, h, y, u, v)
    }

    // ---- internals ---------------------------------------------------------------

    /** Camera shake estimate: the most common block offset between two frames. */
    private fun dominantShift(ref: YuvFrame, f: YuvFrame): Pair<Int, Int> {
        val offsets = BlockAligner.align(ref, f)
        val votes = HashMap<Long, Int>()
        for (i in offsets.indices step 2) {
            val key = (offsets[i].toLong() shl 32) or (offsets[i + 1].toLong() and 0xFFFFFFFFL)
            votes[key] = (votes[key] ?: 0) + 1
        }
        val best = votes.maxByOrNull { it.value }?.key ?: return 0 to 0
        return (best shr 32).toInt() to best.toInt()
    }

    /** Translation that best matches a region of [ref] inside [f]; coarse-to-fine SAD search. */
    private fun regionShift(ref: YuvFrame, f: YuvFrame, x0: Int, y0: Int, box: Int, radius: Int): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var step = 8
        var r = radius
        var cx = 0
        var cy = 0
        while (step >= 1) {
            var best = Long.MAX_VALUE
            var dy = -r
            while (dy <= r) {
                var dx = -r
                while (dx <= r) {
                    val c = regionSad(ref, f, x0, y0, box, cx + dx, cy + dy, sample = max(2, step))
                    if (c < best) { best = c; bestDx = cx + dx; bestDy = cy + dy }
                    dx += step
                }
                dy += step
            }
            cx = bestDx; cy = bestDy
            r = step
            step /= 2
        }
        return bestDx to bestDy
    }

    private fun regionSad(ref: YuvFrame, f: YuvFrame, x0: Int, y0: Int, box: Int, dx: Int, dy: Int, sample: Int): Long {
        val w = ref.width
        val h = ref.height
        var cost = 0L
        var count = 0
        var yy = y0
        while (yy < y0 + box) {
            val sy = (yy + dy).coerceIn(0, h - 1)
            var xx = x0
            while (xx < x0 + box) {
                val sx = (xx + dx).coerceIn(0, w - 1)
                cost += abs((ref.y[yy * w + xx].toInt() and 0xFF) - (f.y[sy * w + sx].toInt() and 0xFF))
                count++
                xx += sample
            }
            yy += sample
        }
        return cost * 1024 / max(1, count)
    }

    /** Copy of [f] moved so that pixel (x+dx, y+dy) lands on (x, y); edges clamp. */
    fun shift(f: YuvFrame, dx: Int, dy: Int): YuvFrame {
        if (dx == 0 && dy == 0) return f
        val w = f.width
        val h = f.height
        val y = ByteArray(w * h)
        for (row in 0 until h) {
            val sr = (row + dy).coerceIn(0, h - 1) * w
            val dr = row * w
            for (col in 0 until w) y[dr + col] = f.y[sr + (col + dx).coerceIn(0, w - 1)]
        }
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        val cdx = dx / 2
        val cdy = dy / 2
        for (row in 0 until ch) {
            val sr = (row + cdy).coerceIn(0, ch - 1) * cw
            val dr = row * cw
            for (col in 0 until cw) {
                val sc = (col + cdx).coerceIn(0, cw - 1)
                u[dr + col] = f.u[sr + sc]
                v[dr + col] = f.v[sr + sc]
            }
        }
        return YuvFrame(w, h, y, u, v, f.tsMs)
    }

    /** Per-pixel weight (0..1) of "this frame differs from the background", on an 8 px grid, feathered. */
    private fun motionMask(f: YuvFrame, bg: YuvFrame): FloatArray {
        val w = f.width
        val h = f.height
        val cell = 8
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val grid = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var sum = 0
            var cnt = 0
            var yy = gy * cell
            while (yy < min(h, gy * cell + cell)) {
                var xx = gx * cell
                while (xx < min(w, gx * cell + cell)) {
                    val i = yy * w + xx
                    val ci = (yy / 2) * (w / 2) + xx / 2
                    sum += abs((f.y[i].toInt() and 0xFF) - (bg.y[i].toInt() and 0xFF)) +
                        (abs((f.u[ci].toInt() and 0xFF) - (bg.u[ci].toInt() and 0xFF)) +
                            abs((f.v[ci].toInt() and 0xFF) - (bg.v[ci].toInt() and 0xFF))) / 2
                    cnt++
                    xx += 2
                }
                yy += 2
            }
            grid[gy * gw + gx] = smooth(sum.toFloat() / max(1, cnt), 14f, 34f)
        }
        // close small holes, then feather: two 3×3 box passes over the grid
        val a = boxBlur(dilate(grid, gw, gh), gw, gh)
        val b = boxBlur(a, gw, gh)
        // expand to pixels (bilinear on the grid)
        val out = FloatArray(w * h)
        for (row in 0 until h) {
            val gyf = (row + 0.5f) / cell - 0.5f
            val gy0 = gyf.toInt().coerceIn(0, gh - 1)
            val gy1 = min(gh - 1, gy0 + 1)
            val ty = (gyf - gy0).coerceIn(0f, 1f)
            for (col in 0 until w) {
                val gxf = (col + 0.5f) / cell - 0.5f
                val gx0 = gxf.toInt().coerceIn(0, gw - 1)
                val gx1 = min(gw - 1, gx0 + 1)
                val tx = (gxf - gx0).coerceIn(0f, 1f)
                val top = b[gy0 * gw + gx0] * (1 - tx) + b[gy0 * gw + gx1] * tx
                val bot = b[gy1 * gw + gx0] * (1 - tx) + b[gy1 * gw + gx1] * tx
                out[row * w + col] = top * (1 - ty) + bot * ty
            }
        }
        return out
    }

    private fun blendInto(y: ByteArray, u: ByteArray, v: ByteArray, f: YuvFrame, mask: FloatArray, w: Int, h: Int) {
        for (i in 0 until w * h) {
            val a = mask[i]
            if (a > 0.01f) y[i] = mix(y[i], f.y[i], a)
        }
        val cw = w / 2
        for (row in 0 until h / 2) for (col in 0 until cw) {
            val a = mask[(row * 2) * w + col * 2]
            if (a > 0.01f) {
                val ci = row * cw + col
                u[ci] = mix(u[ci], f.u[ci], a)
                v[ci] = mix(v[ci], f.v[ci], a)
            }
        }
    }

    private fun dilate(g: FloatArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(g.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = 0f
            for (yy in max(0, y - 1)..min(gh - 1, y + 1)) for (xx in max(0, x - 1)..min(gw - 1, x + 1)) m = max(m, g[yy * gw + xx])
            out[y * gw + x] = m
        }
        return out
    }

    private fun boxBlur(g: FloatArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(g.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0f
            var c = 0
            for (yy in max(0, y - 1)..min(gh - 1, y + 1)) for (xx in max(0, x - 1)..min(gw - 1, x + 1)) { s += g[yy * gw + xx]; c++ }
            out[y * gw + x] = s / c
        }
        return out
    }

    private fun smooth(x: Float, e0: Float, e1: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun mix(a: Byte, b: Byte, t: Float): Byte {
        val av = a.toInt() and 0xFF
        val bv = b.toInt() and 0xFF
        return (av + (bv - av) * t + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    private fun insertionSort(a: IntArray, n: Int) {
        for (i in 1 until n) {
            val x = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > x) { a[j + 1] = a[j]; j-- }
            a[j + 1] = x
        }
    }
}
