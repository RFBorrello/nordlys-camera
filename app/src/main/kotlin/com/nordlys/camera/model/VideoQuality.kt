package com.nordlys.camera.model

/** Video resolution and frame-rate choices, in the reference's order (§9). */
enum class VideoQuality(val width: Int, val height: Int, val fps: Int) {
    FHD_30(1920, 1080, 30),
    FHD_25(1920, 1080, 25),
    FHD_24(1920, 1080, 24),
    HD_30(1280, 720, 30),
    HD_25(1280, 720, 25),
    HD_24(1280, 720, 24);

    val label: String get() = "${height}p, $fps frames per second"
}
