package com.streamcam.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Range

/** Thin Android layer: maps pure [RequestOp]s onto a [CaptureRequest.Builder]. */
object RequestApplier {

    fun apply(builder: CaptureRequest.Builder, ops: List<RequestOp>) {
        for (op in ops) {
            when (op) {
                is RequestOp.SetAeMode ->
                    builder.set(CaptureRequest.CONTROL_AE_MODE, op.mode)
                is RequestOp.SetAeLock ->
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, op.locked)
                is RequestOp.SetSensorSensitivity ->
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, op.iso)
                is RequestOp.SetSensorExposureTime ->
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, op.ns)
                is RequestOp.SetAeTargetFpsRange ->
                    builder.set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(op.min, op.max),
                    )
                is RequestOp.SetAwbMode ->
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, op.mode)
                is RequestOp.SetAwbLock ->
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, op.locked)
                is RequestOp.SetColorCorrectionMode ->
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, op.mode)
                is RequestOp.SetColorCorrectionTransform ->
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                        ColorSpaceTransform(op.matrix.toIntArray()),
                    )
                is RequestOp.SetColorCorrectionGains ->
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_GAINS,
                        RggbChannelVector(
                            op.gains.red,
                            op.gains.greenEven,
                            op.gains.greenOdd,
                            op.gains.blue,
                        ),
                    )
                is RequestOp.SetColorTemperature -> {
                    if (Build.VERSION.SDK_INT >= 36) {
                        builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, op.kelvin)
                    }
                }
                is RequestOp.SetColorTint -> {
                    if (Build.VERSION.SDK_INT >= 36) {
                        builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, op.tint)
                    }
                }
                is RequestOp.SetAfMode ->
                    builder.set(CaptureRequest.CONTROL_AF_MODE, op.mode)
                is RequestOp.SetLensFocusDistance ->
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, op.diopters)
                is RequestOp.SetZoomRatio -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, op.ratio)
                    }
                }
                is RequestOp.SetScalerCropRegion ->
                    builder.set(
                        CaptureRequest.SCALER_CROP_REGION,
                        Rect(op.rect.left, op.rect.top, op.rect.right, op.rect.bottom),
                    )
                is RequestOp.SetNoiseReductionMode ->
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, op.mode)
                is RequestOp.SetEdgeMode ->
                    builder.set(CaptureRequest.EDGE_MODE, op.mode)
                is RequestOp.SetFaceDetectMode ->
                    builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, op.mode)
                is RequestOp.SetSceneMode ->
                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, op.mode)
                is RequestOp.SetVideoStabilizationMode ->
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, op.mode)
                is RequestOp.SetAberrationMode ->
                    builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, op.mode)
            }
        }
    }
}
