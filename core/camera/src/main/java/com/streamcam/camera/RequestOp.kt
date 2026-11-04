package com.streamcam.camera

import com.streamcam.imageproc.IntRect
import com.streamcam.imageproc.RggbGains

sealed interface RequestOp {
    // Exposure
    data class SetAeMode(val mode: Int) : RequestOp
    data class SetAeLock(val locked: Boolean) : RequestOp
    data class SetSensorSensitivity(val iso: Int) : RequestOp
    data class SetSensorExposureTime(val ns: Long) : RequestOp
    data class SetAeTargetFpsRange(val min: Int, val max: Int) : RequestOp

    // White balance
    data class SetAwbMode(val mode: Int) : RequestOp
    data class SetAwbLock(val locked: Boolean) : RequestOp
    data class SetColorCorrectionMode(val mode: Int) : RequestOp
    data class SetColorCorrectionTransform(val matrix: List<Int>) : RequestOp
    data class SetColorCorrectionGains(val gains: RggbGains) : RequestOp
    /** API 36 native CCT mode: target color temperature in Kelvin. */
    data class SetColorTemperature(val kelvin: Int) : RequestOp
    /** API 36 native CCT mode: green/magenta tint, -50..+50. */
    data class SetColorTint(val tint: Int) : RequestOp

    // Focus
    data class SetAfMode(val mode: Int) : RequestOp
    data class SetLensFocusDistance(val diopters: Float) : RequestOp
    /** Tap-to-focus: AF regions + START trigger, applied once; cleared by [ClearAfRegions]. */
    data class SetAfRegions(val regions: List<FocusRegion>) : RequestOp
    data class SetAfTrigger(val trigger: Int) : RequestOp
    /** Clears tap-to-focus regions so the next AUTO plan does not carry stale ones. */
    data object ClearAfRegions : RequestOp

    // Zoom
    data class SetZoomRatio(val ratio: Float) : RequestOp
    data class SetScalerCropRegion(val rect: IntRect) : RequestOp
}
