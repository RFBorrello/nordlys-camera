package com.nordlys.camera.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min

private val BANDS = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(2, 16)

/**
 * Runs [body] over [rows] split into contiguous bands on the default pool and
 * waits for all of them. Bands never overlap, so each may write its own rows
 * of a shared output array without locking.
 */
internal suspend fun forBands(rows: Int, body: (from: Int, to: Int) -> Unit) {
    if (rows <= 0) return
    val step = (rows + BANDS - 1) / BANDS
    coroutineScope {
        var s = 0
        while (s < rows) {
            val from = s
            val to = min(rows, s + step)
            launch(Dispatchers.Default) { body(from, to) }
            s = to
        }
    }
}
