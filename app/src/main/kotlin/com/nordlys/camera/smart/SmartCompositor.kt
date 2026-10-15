package com.nordlys.camera.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Creative composites over a timed sequence (docs/LUMIA_PARITY.md §5).
 * Every routine works on stabilised frames: each frame is first shifted onto
 * the reference by its dominant (camera) translation, so only things that
 * really moved in the scene differ from frame to frame.
 *
 * Per-pixel passes run in row bands on the default pool ([forBands]).
 */
object SmartCompositor {

    /** Mean |Δ| (luma + half chroma) below which a cell counts as background. */
    var maskLow = 14f
    /** Mean |Δ| above which a cell is fully "moved". */
    var maskHigh = 34f
    /** Motion-focus subject box, as a fraction of the shorter frame side. */
    var subjectBox = 0.22f

    /** Frames shifted onto [ref]'s framing by their dominant translation. */
    suspend fun stabilise(frames: List<YuvFrame>, ref: Int): List<YuvFrame> = coroutineScope {
        val reference = frames[ref]
        frames.mapIndexed { i, f ->
            async(Dispatchers.Default) {
                if (i == ref) f else {
                    val (dx, dy) = dominantShift(reference, f)
                    shift(f, dx, dy)
                }
            }
        }.awaitAll()
    }

    /** Median of the stabilised stack: anything that does not stay put long enough disappears. */
    suspend fun removeMoving(frames: List<YuvFrame>): YuvFrame {
        val w = frames[0].width
        val h = frames[0].height
        val n = frames.size
        suspend fun medianPlane(pick: (YuvFrame) -> ByteArray, rows: Int, rowLen: Int): ByteArray {
            val out = ByteArray(rows * rowLen)
            val planes = frames.map(pick)
            forBands(rows) { r0, r1 ->
                val stack = IntArray(n)
                for (i in r0 * rowLen until r1 * rowLen) {
                    for (k in 0 until n) stack[k] = planes[k][i].toInt() and 0xFF
                    insertionSort(stack, n)
                    out[i] = stack[n / 2].toByte()
                }
            }
            return out
        }
        return YuvFrame(w, h, medianPlane({ it.y }, h, w), medianPlane({ it.u }, h / 2, w / 2), medianPlane({ it.v }, h / 2, w / 2))
    }

    /**
     * Stroboscopic action shot: the clean background with the moving subject
     * pasted in from every selected frame, in order.
     */
    suspend fun actionShot(frames: List<YuvFrame>, selected: List<Int>, background: YuvFrame? = null): YuvFrame {
        val bg = background ?: removeMoving(frames)
        val w = bg.width
        val h = bg.height
        val y = bg.y.copyOf()
        val u = bg.u.copyOf()
        val v = bg.v.copyOf()
        val sorted = selected.sorted()
        // masks are independent; only the pasting has to follow frame order
        val masks = coroutineScope { sorted.map { i -> async(Dispatchers.Default) { motionMask(frames[i], bg) } }.awaitAll() }
        sorted.forEachIndexed { k, i -> blendInto(y, u, v, frames[i], masks[k], w, h) }
        return YuvFrame(w, h, y, u, v)
    }

    /**
     * Motion focus: frames are aligned on the subject at ([cx], [cy]) (normalized)
     * instead of the background, then averaged. The subject stays crisp while the
     * rest smears along the motion. The reference is restored around the subject
     * wherever it agrees with the average, so the restore follows the subject's
     * outline instead of pasting a hard disc of unblurred background.
     */
    suspend fun motionFocus(frames: List<YuvFrame>, ref: Int, cx: Float, cy: Float): YuvFrame {
        val reference = frames[ref]
        val w = reference.width
        val h = reference.height
        val box = (min(w, h) * subjectBox).toInt().coerceAtLeast(32)
        val x0 = (cx * w - box / 2).toInt().coerceIn(0, w - box)
        val y0 = (cy * h - box / 2).toInt().coerceIn(0, h - box)
        val aligned = coroutineScope {
            frames.mapIndexed { i, f ->
                async(Dispatchers.Default) {
                    if (i == ref) f else {
                        val (dx, dy) = regionShift(reference, f, x0, y0, box, radius = max(w, h) / 10)
                        shift(f, dx, dy)
                    }
                }
            }.awaitAll()
        }
        val n = aligned.size
        suspend fun mean(pick: (YuvFrame) -> ByteArray, rows: Int, rowLen: Int): ByteArray {
            val out = ByteArray(rows * rowLen)
            val planes = aligned.map(pick)
            forBands(rows) { r0, r1 ->
                for (i in r0 * rowLen until r1 * rowLen) {
                    var sum = 0
                    for (p in planes) sum += p[i].toInt() and 0xFF
                    out[i] = (sum / n).toByte()
                }
            }
            return out
        }
        val y = mean({ it.y }, h, w)
        val u = mean({ it.u }, h / 2, w / 2)
        val v = mean({ it.v }, h / 2, w / 2)

        // restore the reference inside a generous disc, weighted by agreement with the mean
        val r = box * 1.1f
        val ccx = x0 + box / 2f
        val ccy = y0 + box / 2f
        val rowFrom = max(0, (ccy - r).toInt())
        val rowTo = min(h, (ccy + r).toInt() + 1)
        val colFrom = max(0, (ccx - r).toInt())
        val colTo = min(w, (ccx + r).toInt() + 1)
        val cw = w / 2
        forBands(rowTo - rowFrom) { b0, b1 ->
            for (row in rowFrom + b0 until rowFrom + b1) for (col in colFrom until colTo) {
                val d = hypot((col - ccx) / r, (row - ccy) / r)
                if (d >= 1f) continue
                val i = row * w + col
                val diff = abs((reference.y[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)).toFloat()
                val a = smooth(1f - d, 0f, 0.3f) * (1f - smooth(diff, 10f, 28f))
                if (a <= 0.01f) continue
                y[i] = mix(y[i], reference.y[i], a)
                if ((row and 1) == 0 && (col and 1) == 0) {
                    val ci = (row / 2) * cw + col / 2
                    u[ci] = mix(u[ci], reference.u[ci], a)
                    v[ci] = mix(v[ci], reference.v[ci], a)
                }
            }
        }
        return YuvFrame(w, h, y, u, v)
    }

    /** One face to take from another frame: an ellipse in sensor pixels. */
    data class FaceSwap(val frame: Int, val cx: Float, val cy: Float, val rx: Float, val ry: Float)

    /**
     * Change faces: each swap's elliptical region is taken from its source frame,
     * nudged by a small local search so a head that moved still lines up, and
     * blended over [base] with a feathered edge.
     */
    suspend fun changeFaces(base: YuvFrame, frames: List<YuvFrame>, swaps: List<FaceSwap>): YuvFrame {
        val w = base.width
        val h = base.height
        val y = base.y.copyOf()
        val u = base.u.copyOf()
        val v = base.v.copyOf()
        val cw = w / 2
        for (s in swaps) {
            val src = frames[s.frame]
            if (src === base) continue
            val box = (2 * min(s.rx, s.ry)).toInt().coerceIn(16, min(w, h))
            val bx = (s.cx - box / 2f).toInt().coerceIn(0, w - box)
            val by = (s.cy - box / 2f).toInt().coerceIn(0, h - box)
            val (dx, dy) = regionShift(base, src, bx, by, box, radius = (box / 3).coerceAtLeast(8))
            val rowFrom = max(0, (s.cy - s.ry).toInt())
            val rowTo = min(h, (s.cy + s.ry).toInt() + 1)
            val colFrom = max(0, (s.cx - s.rx).toInt())
            val colTo = min(w, (s.cx + s.rx).toInt() + 1)
            forBands(rowTo - rowFrom) { b0, b1 ->
                for (row in rowFrom + b0 until rowFrom + b1) {
                    val sr = (row + dy).coerceIn(0, h - 1)
                    for (col in colFrom until colTo) {
                        val d = hypot((col - s.cx) / s.rx, (row - s.cy) / s.ry)
                        if (d >= 1f) continue
                        val a = smooth(1f - d, 0f, 0.35f)
                        val sc = (col + dx).coerceIn(0, w - 1)
                        val i = row * w + col
                        y[i] = mix(y[i], src.y[sr * w + sc], a)
                        if ((row and 1) == 0 && (col and 1) == 0) {
                            val ci = (row / 2) * cw + col / 2
                            val si = (sr / 2) * cw + sc / 2
                            u[ci] = mix(u[ci], src.u[si], a)
                            v[ci] = mix(v[ci], src.v[si], a)
                        }
                    }
                }
            }
        }
        return YuvFrame(w, h, y, u, v)
    }

    // ---- internals ---------------------------------------------------------------

    /** Camera shake estimate: the most common block offset between two frames. */
    private suspend fun dominantShift(ref: YuvFrame, f: YuvFrame): Pair<Int, Int> {
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
    suspend fun shift(f: YuvFrame, dx: Int, dy: Int): YuvFrame {
        if (dx == 0 && dy == 0) return f
        val w = f.width
        val h = f.height
        val y = ByteArray(w * h)
        forBands(h) { r0, r1 ->
            for (row in r0 until r1) {
                val sr = (row + dy).coerceIn(0, h - 1) * w
                val dr = row * w
                for (col in 0 until w) y[dr + col] = f.y[sr + (col + dx).coerceIn(0, w - 1)]
            }
        }
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        val cdx = dx / 2
        val cdy = dy / 2
        forBands(ch) { r0, r1 ->
            for (row in r0 until r1) {
                val sr = (row + cdy).coerceIn(0, ch - 1) * cw
                val dr = row * cw
                for (col in 0 until cw) {
                    val sc = (col + cdx).coerceIn(0, cw - 1)
                    u[dr + col] = f.u[sr + sc]
                    v[dr + col] = f.v[sr + sc]
                }
            }
        }
        return YuvFrame(w, h, y, u, v, f.tsMs)
    }

    /** Per-pixel weight (0..1) of "this frame differs from the background", on an 8 px grid, feathered. */
    private suspend fun motionMask(f: YuvFrame, bg: YuvFrame): FloatArray {
        val w = f.width
        val h = f.height
        val cell = 8
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val grid = FloatArray(gw * gh)
        val lo = maskLow
        val hi = maskHigh
        forBands(gh) { g0, g1 ->
            for (gy in g0 until g1) for (gx in 0 until gw) {
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
                grid[gy * gw + gx] = smooth(sum.toFloat() / max(1, cnt), lo, hi)
            }
        }
        // drop isolated specks (noise, shimmer), close small holes, then feather
        val a = boxBlur(dilate(erode(grid, gw, gh), gw, gh), gw, gh)
        val b = boxBlur(a, gw, gh)
        // expand to pixels (bilinear on the grid)
        val out = FloatArray(w * h)
        forBands(h) { r0, r1 ->
            for (row in r0 until r1) {
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
        }
        return out
    }

    private suspend fun blendInto(y: ByteArray, u: ByteArray, v: ByteArray, f: YuvFrame, mask: FloatArray, w: Int, h: Int) {
        forBands(h) { r0, r1 ->
            for (i in r0 * w until r1 * w) {
                val a = mask[i]
                if (a > 0.01f) y[i] = mix(y[i], f.y[i], a)
            }
        }
        val cw = w / 2
        forBands(h / 2) { r0, r1 ->
            for (row in r0 until r1) for (col in 0 until cw) {
                val a = mask[(row * 2) * w + col * 2]
                if (a > 0.01f) {
                    val ci = row * cw + col
                    u[ci] = mix(u[ci], f.u[ci], a)
                    v[ci] = mix(v[ci], f.v[ci], a)
                }
            }
        }
    }

    private fun erode(g: FloatArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(g.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = 1f
            for (yy in max(0, y - 1)..min(gh - 1, y + 1)) for (xx in max(0, x - 1)..min(gw - 1, x + 1)) m = min(m, g[yy * gw + xx])
            out[y * gw + x] = m
        }
        return out
    }

    private fun dilate(g: FloatArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(g.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = 0f
            for (yy in max(0, y - 2)..min(gh - 1, y + 2)) for (xx in max(0, x - 2)..min(gw - 1, x + 2)) m = max(m, g[yy * gw + xx])
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
