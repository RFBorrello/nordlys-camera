package com.nordlys.camera.living

import com.nordlys.camera.smart.YuvFrame

/** Result of a Living-image capture: the still moment plus its motion frames. */
data class LivingPayload(
    val stillJpeg: ByteArray,
    /** Chronologically ordered analysis frames spanning the motion window. */
    val frames: List<YuvFrame>,
)

/** A stored motion sidecar: ordered JPEG frame files plus cadence metadata. */
data class LivingClip(
    val id: String,
    val directoryPath: String,
    val frameFiles: List<String>,
    val intervalMs: Int,
)
