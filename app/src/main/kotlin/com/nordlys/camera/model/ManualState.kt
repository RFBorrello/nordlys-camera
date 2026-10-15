package com.nordlys.camera.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Shutter-wheel modes, in the order they sit on the wheel (see docs/LUMIA_PARITY.md §2). */
enum class CaptureMode { VIDEO, PHOTO, SMART }

enum class GridType { NONE, THIRDS, GOLDEN, CROSSHAIR, SQUARE }

enum class FlashSetting { AUTO, OFF, ON }

enum class SceneSetting { AUTO, NIGHT, SPORT }

enum class WbSetting { AUTO, DAYLIGHT, CLOUDY, FLUORESCENT, TUNGSTEN }

/** Preview / still aspect. */
enum class AspectSetting(val ratio: Float) { FOUR_THREE(4f / 3f), SIXTEEN_NINE(16f / 9f) }

/** What lands in the library besides the oversampled photo. */
enum class DualCapture { OFF, FULL_RES, DNG }

/** Focus is either automatic, a normalized manual position (0 = nearest, 1 = far), or infinity. */
sealed interface FocusSetting {
    data object Auto : FocusSetting
    data class Manual(val position: Float) : FocusSetting
    data object Infinity : FocusSetting
}

/** Full manual parameter set applied to the repeating preview request and to every still. */
data class ManualState(
    val iso: Int? = null,                 // null = auto
    val shutterNs: Long? = null,          // null = auto
    val evThirds: Int = 0,                // exposure compensation in 1/3 EV
    val wb: WbSetting = WbSetting.AUTO,
    val focus: FocusSetting = FocusSetting.Auto,
    val zoomRatio: Float = 1f,
    val flash: FlashSetting = FlashSetting.AUTO,
    val torch: Boolean = false,           // video light
    val scene: SceneSetting = SceneSetting.AUTO,
) {
    /** Keeps flash, torch, scene and zoom; returns every dial to auto. */
    fun withDialsReset() = copy(iso = null, shutterNs = null, evThirds = 0, wb = WbSetting.AUTO, focus = FocusSetting.Auto)
}

/** Ranges read from camera characteristics; the dials build their ladders from these. */
data class CameraRanges(
    val isoMin: Int,
    val isoMax: Int,
    val shutterMinNs: Long,
    val shutterMaxNs: Long,
    val evMinSteps: Int,
    val evMaxSteps: Int,
    val evStepF: Float,
    val focusMaxD: Float,
    val maxZoom: Float,
    val hasRaw: Boolean,
    val hasFlash: Boolean,
) {
    val canFocus: Boolean get() = focusMaxD > 0f

    /** Largest symmetric compensation the device offers, in whole thirds. */
    val evMaxThirds: Int
        get() {
            val maxEv = minOf(abs(evMinSteps), abs(evMaxSteps)) * evStepF
            return floor(maxEv * 3f + 1e-3f).toInt().coerceIn(0, 12)
        }

    fun evThirdsToSteps(thirds: Int): Int {
        if (evStepF <= 0f) return 0
        return (thirds / 3f / evStepF).roundToInt().coerceIn(evMinSteps, evMaxSteps)
    }
}

/** Standard third-stop shutter ladder (in seconds), short to long. */
private val SHUTTER_LADDER_S: List<Double> = listOf(
    1 / 16000.0, 1 / 12800.0, 1 / 10000.0, 1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0, 1 / 3200.0,
    1 / 2500.0, 1 / 2000.0, 1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0, 1 / 640.0, 1 / 500.0,
    1 / 400.0, 1 / 320.0, 1 / 250.0, 1 / 200.0, 1 / 160.0, 1 / 125.0, 1 / 100.0, 1 / 80.0, 1 / 60.0,
    1 / 50.0, 1 / 40.0, 1 / 30.0, 1 / 25.0, 1 / 20.0, 1 / 15.0, 1 / 13.0, 1 / 10.0, 1 / 8.0, 1 / 6.0,
    1 / 5.0, 1 / 4.0, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0,
    10.0, 13.0, 15.0, 20.0, 25.0, 30.0,
)

/** Whole-stop ISO ladder; whole stops keep the dial quick to read. */
private val ISO_LADDER = listOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)

fun shutterStopsNs(r: CameraRanges): List<Long> =
    SHUTTER_LADDER_S.map { (it * 1e9).roundToLong() }.filter { it in r.shutterMinNs..r.shutterMaxNs }

fun isoStops(r: CameraRanges): List<Int> = ISO_LADDER.filter { it in r.isoMin..r.isoMax }

/** "1/250", "0.5s", "2s" — the dial and indicator wording for a shutter time. */
fun shutterLabel(ns: Long): String {
    val s = ns / 1e9
    if (s >= 0.3 - 1e-6) {
        return if (s >= 1.0 && abs(s - s.roundToInt()) < 0.05) "${s.roundToInt()}s" else "%.1fs".format(s)
    }
    val d = 1.0 / s
    val rounded = when {
        d >= 1000 -> (d / 100).roundToInt() * 100
        d >= 100 -> (d / 10).roundToInt() * 10
        else -> d.roundToInt()
    }
    return "1/$rounded"
}

fun evLabel(thirds: Int): String = if (thirds == 0) "0.0" else "%+.1f".format(thirds / 3f)

/** Values that deserve the red underline in the indicator strip. */
fun isoIsRisky(iso: Int) = iso >= 800
fun shutterIsRisky(ns: Long) = ns >= 500_000_000L
