package com.nordlys.camera.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Smart-mode orchestrator: takes a captured YUV burst, aligns every helper
 * frame against the reference, fuses luminance through the trimmed statistic,
 * and emits a display-ready result frame. All work runs off the UI thread.
 */
class SmartProcessor {

    /** Pipeline phase reported to the view as feedback text. */
    enum class Phase { ALIGN, FUSE }

    suspend fun process(
        burst: List<YuvFrame>,
        onPhase: (Phase, Int, Int) -> Unit = { _, _, _ -> },
    ): YuvFrame? = withContext(Dispatchers.Default) {
        if (burst.size < 2) return@withContext null
        val reference = burst.first()
        val helpers = burst.drop(1)

        val alignments = ArrayList<IntArray>(helpers.size)
        helpers.forEachIndexed { i, helper ->
            onPhase(Phase.ALIGN, i + 1, helpers.size)
            alignments.add(BlockAligner.align(reference, helper))
        }

        onPhase(Phase.FUSE, 0, 1)
        TemporalMerge.merge(reference, helpers, alignments)
    }
}
