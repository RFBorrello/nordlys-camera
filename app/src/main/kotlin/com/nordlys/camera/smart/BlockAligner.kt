package com.nordlys.camera.smart

/**
 * Block-based frame alignment versus a reference frame.
 *
 * A fresh implementation: for each 16x16 block of the reference frame,
 * search a bounded neighborhood in the candidate frame for the offset that
 * minimizes sum of absolute differences (SAD) over a stride-subsampled
 * 4x4 grid of the block. A half-resolution first pass halves work while
 * stage two refines at full resolution.
 *
 * Returns one [dx, dy] pair per block, row-major over the block grid.
 * Block rows are searched in parallel bands; [seedDx]/[seedDy] centre the
 * search on a global estimate so large hand drift stays in range.
 */
object BlockAligner {

    const val BLOCK = 16
    private const val SEARCH_RADIUS_COARSE = 8
    private const val SEARCH_RADIUS_FINE = 2

    suspend fun align(
        reference: YuvFrame,
        candidate: YuvFrame,
        seedDx: Int = 0,
        seedDy: Int = 0,
    ): IntArray {
        val w = reference.width
        val h = reference.height
        val blocksX = w / BLOCK
        val blocksY = h / BLOCK
        val offsets = IntArray(blocksX * blocksY * 2)

        forBands(blocksY) { by0, by1 -> for (by in by0 until by1) {
            for (bx in 0 until blocksX) {
                val baseX = bx * BLOCK
                val baseY = by * BLOCK

                // stage 1 — half-resolution SAD, subsampled block content
                var bestDx = seedDx
                var bestDy = seedDy
                var bestCost = Int.MAX_VALUE
                var dy = seedDy - SEARCH_RADIUS_COARSE
                while (dy <= seedDy + SEARCH_RADIUS_COARSE) {
                    var dx = seedDx - SEARCH_RADIUS_COARSE
                    while (dx <= seedDx + SEARCH_RADIUS_COARSE) {
                        val cost = coarseSAD(reference, candidate, baseX, baseY, dx, dy)
                        if (cost < bestCost) {
                            bestCost = cost; bestDx = dx; bestDy = dy
                        }
                        dx += 2
                    }
                    dy += 2
                }

                // stage 2 — full-resolution refinement around the coarse peak
                var fineBest = bestCost
                var fineDx = bestDx
                var fineDy = bestDy
                dy = bestDy - SEARCH_RADIUS_FINE
                while (dy <= bestDy + SEARCH_RADIUS_FINE) {
                    var dx = bestDx - SEARCH_RADIUS_FINE
                    while (dx <= bestDx + SEARCH_RADIUS_FINE) {
                        val cost = fineSAD(reference, candidate, baseX, baseY, dx, dy)
                        if (cost <= fineBest) {
                            fineBest = cost; fineDx = dx; fineDy = dy
                        }
                        dx++
                    }
                    dy++
                }

                val blockIdx = (by * blocksX + bx) * 2
                offsets[blockIdx] = fineDx
                offsets[blockIdx + 1] = fineDy
            }
        } }
        return offsets
    }

    /** Same-buffer planar SAD with 2px grid subsampling (block content 8x8 = 64 pixels). */
    private fun coarseSAD(ref: YuvFrame, cand: YuvFrame, bx: Int, by: Int, dx: Int, dy: Int): Int {
        var cost = 0
        val w = ref.width
        val cw = cand.width
        val y = ref.y
        val cy = cand.y
        var yy = by
        while (yy < by + BLOCK) {
            var xx = bx
            while (xx < bx + BLOCK) {
                val sx = (xx + dx).coerceIn(1, cw - 1)
                val sy = (yy + dy).coerceIn(1, ref.height - 1)
                cost += Math.abs((y[yy * w + xx].toInt() and 0xFF) - (cy[sy * cw + sx].toInt() and 0xFF))
                xx += 2
            }
            yy += 2
        }
        return cost
    }

    /** Full-res SAD with 2px subsampling for refinement speed. */
    private fun fineSAD(ref: YuvFrame, cand: YuvFrame, bx: Int, by: Int, dx: Int, dy: Int): Int {
        var cost = 0
        val w = ref.width
        val cw = cand.width
        val y = ref.y
        val cy = cand.y
        var yy = by
        while (yy < by + BLOCK) {
            var xx = bx
            while (xx < bx + BLOCK) {
                val sx = (xx + dx).coerceIn(1, cw - 1)
                val sy = (yy + dy).coerceIn(1, ref.height - 1)
                cost += Math.abs((y[yy * w + xx].toInt() and 0xFF) - (cy[sy * cw + sx].toInt() and 0xFF))
                xx += 2
            }
            yy += 2
        }
        return cost
    }

    /** Neighborhood sample for statistics: number of blocks for bookkeeping. */
    fun blockCount(frame: YuvFrame): Int = (frame.width / BLOCK) * (frame.height / BLOCK)
}
