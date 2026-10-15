package com.nordlys.camera.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import com.nordlys.camera.model.FlashMode
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.model.WbPreset

/** Translates Nordlys manual state into Camera2 capture-request fields. */
fun applyManualState(b: CaptureRequest.Builder, state: ManualState) {
    if (state.iso != null && state.shutterNs != null) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, state.iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, state.shutterNs)
    } else {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.evSteps)
        if (state.iso != null) b.set(CaptureRequest.SENSOR_SENSITIVITY, state.iso)
        if (state.shutterNs != null) b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, state.shutterNs)
    }

    b.set(CaptureRequest.CONTROL_AWB_MODE, state.wb.toAwbMode())

    if (state.focusDiopters != null) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDiopters)
    } else {
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, state.zoomRatio)

    when (state.flash) {
        FlashMode.OFF -> {
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        FlashMode.ON -> {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
        }
        FlashMode.TORCH -> {
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        }
    }
}

private fun WbPreset.toAwbMode(): Int = when (this) {
    WbPreset.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    WbPreset.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
    WbPreset.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    WbPreset.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    WbPreset.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    WbPreset.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
}
