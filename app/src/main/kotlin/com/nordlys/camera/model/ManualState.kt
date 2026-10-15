package com.nordlys.camera.model

import kotlin.math.roundToInt

/** Static/discrete choices surfaced by the Metro wheel. */
enum class GridType { NONE, RULE_OF_THIRDS, SQUARE, GOLDEN_RATIO }

enum class FlashMode { OFF, ON, TORCH }
enum class WbPreset(val label: String) {
    AUTO("Auto"),
    DAYLIGHT("Daylight"),
    CLOUDY("Cloudy"),
    SHADE("Shade"),
    FLUORESCENT("Fluorescent"),
    INCANDESCENT("Incandescent"),
}

/** One selectable shutter value along the Metro shutter ladder, in nanoseconds. */
data class ShutterStop(val ns: Long) {
    val label: String get() = ShutterStop.label(ns)

    companion object {
        fun label(ns: Long): String {
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0) "%.0fs".format(seconds) else "1/%d".format((1.0 / seconds).roundToInt())
        }
    }
}

/** Full manual parameter set applied to a repeating preview request and to every still. */
data class ManualState(
    val iso: Int? = null,                // null = auto exposure
    val shutterNs: Long? = null,          // null = auto exposure
    val evSteps: Int = 0,                 // AE compensation, in device steps
    val wb: WbPreset = WbPreset.AUTO,
    val focusDiopters: Float? = null,     // null = continuous AF
    val zoomRatio: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val grid: GridType = GridType.NONE,
)

/** Ranges read from camera characteristics; the wheel uses these to build stops. */
data class CameraRanges(
    val isoMin: Int,
    val isoMax: Int,
    val shutterMinNs: Long,
    val shutterMaxNs: Long,
    val evMinSteps: Int,
    val evMaxSteps: Int,
    val evStepF: Float,
    val focusMinD: Float,
    val focusMaxD: Float,
    val maxZoom: Float,
    val hasRaw: Boolean,
)

/** Stops for the ISO ladder: quick, photographically reasonable steps. */
fun isoStops(r: CameraRanges): List<Int> {
    val stops = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 6400)
    return stops.filter { it in r.isoMin..r.isoMax }
}

/** Shutter ladder used by the wheel, clamped to the device's EXPOSURE_TIME range. */
fun shutterStops(r: CameraRanges): List<ShutterStop> {
    val denominators = listOf(1.0 / 2000, 1.0 / 1500, 1.0 / 1000, 1.0 / 750, 1.0 / 500, 1.0 / 350,
        1.0 / 250, 1.0 / 180, 1.0 / 125, 1.0 / 90, 1.0 / 60, 1.0 / 45, 1.0 / 30,
        1.0 / 15, 1.0 / 8, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
    return denominators.map { (it * 1_000_000_000).roundToInt().toLong() }
        .filter { it in r.shutterMinNs..r.shutterMaxNs }
        .map(::ShutterStop)
}
