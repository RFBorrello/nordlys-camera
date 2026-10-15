package com.nordlys.camera.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Range
import com.nordlys.camera.model.CameraRanges
import com.nordlys.camera.model.FlashSetting
import com.nordlys.camera.model.FocusSetting
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.model.SceneSetting
import com.nordlys.camera.model.WbSetting
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Device facts the request mapping needs beyond the numeric ranges. */
data class DeviceTraits(
    val ranges: CameraRanges,
    val sceneModes: Set<Int>,
    val fpsRanges: List<Range<Int>>,
)

/**
 * Translates Nordlys manual state into Camera2 request fields.
 *
 * Camera2 only honours sensitivity and exposure time with AE off, so a single
 * manual value (ISO *or* shutter) is turned into a full manual exposure using
 * [meteredProduct] — the ISO × exposure-time product last measured while AE
 * was running. Exposure compensation scales that product.
 */
fun applyManualState(
    b: CaptureRequest.Builder,
    state: ManualState,
    traits: DeviceTraits,
    meteredProduct: Double?,
    still: Boolean,
) {
    val r = traits.ranges
    val evScale = 2.0.pow(state.evThirds / 3.0)
    val manualExposure: Pair<Int, Long>? = when {
        state.iso != null && state.shutterNs != null -> state.iso to state.shutterNs
        state.iso != null && meteredProduct != null -> {
            val ns = (meteredProduct * evScale / state.iso).roundToLong().coerceIn(r.shutterMinNs, r.shutterMaxNs)
            state.iso to ns
        }
        state.shutterNs != null && meteredProduct != null -> {
            val iso = (meteredProduct * evScale / state.shutterNs).roundToInt().coerceIn(r.isoMin, r.isoMax)
            iso to state.shutterNs
        }
        else -> null
    }

    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    if (manualExposure != null) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, manualExposure.first)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposure.second)
        // keep the preview from crawling when a long exposure is dialled in
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (still) manualExposure.second else minOf(manualExposure.second, 66_000_000L))
        if (!still && manualExposure.second > 66_000_000L) {
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 66_000_000L)
            val gain = manualExposure.second / 66_000_000.0
            b.set(CaptureRequest.SENSOR_SENSITIVITY, (manualExposure.first * gain).roundToInt().coerceIn(r.isoMin, r.isoMax))
        }
        when {
            state.torch -> b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            still && state.flash == FlashSetting.ON && r.hasFlash -> b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
            else -> b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
    } else {
        val aeMode = when {
            state.torch || !r.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
            state.flash == FlashSetting.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            state.flash == FlashSetting.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, r.evThirdsToSteps(state.evThirds))
        b.set(CaptureRequest.FLASH_MODE, if (state.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        applyScene(b, state.scene, traits)
    }

    b.set(CaptureRequest.CONTROL_AWB_MODE, state.wb.toAwbMode())

    when (val f = state.focus) {
        FocusSetting.Auto -> b.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (r.canFocus) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CameraMetadata.CONTROL_AF_MODE_OFF,
        )
        is FocusSetting.Manual -> {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusPositionToDiopters(f.position, r))
        }
        FocusSetting.Infinity -> {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
    }

    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, state.zoomRatio)
}

/** Near (0) → far (1); squared so the far half of the dial gets the finer travel. */
fun focusPositionToDiopters(position: Float, r: CameraRanges): Float {
    val p = 1f - position.coerceIn(0f, 1f)
    return r.focusMaxD * p * p
}

private fun applyScene(b: CaptureRequest.Builder, scene: SceneSetting, traits: DeviceTraits) {
    val native = when (scene) {
        SceneSetting.AUTO -> null
        SceneSetting.NIGHT -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT
        SceneSetting.SPORT -> CameraMetadata.CONTROL_SCENE_MODE_SPORTS
    }
    if (native != null && native in traits.sceneModes) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
        b.set(CaptureRequest.CONTROL_SCENE_MODE, native)
        return
    }
    // Fallback: steer AE through its frame-rate window. A low floor lets AE
    // lengthen exposures (night); a high floor forces short ones (sport).
    val pick = when (scene) {
        SceneSetting.AUTO -> null
        SceneSetting.NIGHT -> traits.fpsRanges.minWithOrNull(compareBy<Range<Int>> { it.lower }.thenByDescending { it.upper })
        SceneSetting.SPORT -> traits.fpsRanges.filter { it.upper <= 60 }.maxWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
    }
    pick?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
}

private fun WbSetting.toAwbMode(): Int = when (this) {
    WbSetting.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    WbSetting.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
    WbSetting.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    WbSetting.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    WbSetting.TUNGSTEN -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
}
