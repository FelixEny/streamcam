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

    // Focus
    data class SetAfMode(val mode: Int) : RequestOp
    data class SetLensFocusDistance(val diopters: Float) : RequestOp

    // Zoom
    data class SetZoomRatio(val ratio: Float) : RequestOp
    data class SetScalerCropRegion(val rect: IntRect) : RequestOp

    // Minimal processing
    data class SetNoiseReductionMode(val mode: Int) : RequestOp
    data class SetEdgeMode(val mode: Int) : RequestOp
    data class SetFaceDetectMode(val mode: Int) : RequestOp
    data class SetSceneMode(val mode: Int) : RequestOp
    data class SetVideoStabilizationMode(val mode: Int) : RequestOp
    data class SetAberrationMode(val mode: Int) : RequestOp
}
