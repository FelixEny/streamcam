package com.streamcam.camera

import com.streamcam.imageproc.ProcessingPolicy
import com.streamcam.imageproc.WhiteBalance
import com.streamcam.imageproc.ZoomMath

/**
 * Pure translation of user [CameraSettings] + probed [CameraCapabilities] into a list of
 * [RequestOp]s. Android-free so it is unit-testable; [RequestApplier] applies the ops.
 */
object RequestPlanner {

    fun plan(
        settings: CameraSettings,
        caps: CameraCapabilities,
        targetFps: FpsRange? = null,
        manualWb: ManualWbReference? = null,
    ): List<RequestOp> {
        val ops = mutableListOf<RequestOp>()

        targetFps?.let { ops += RequestOp.SetAeTargetFpsRange(it.min, it.max) }

        planWhiteBalance(settings, caps, manualWb, ops)
        planExposure(settings, caps, ops)
        planFocus(settings, caps, ops)
        planZoom(settings, caps, ops)
        planMinimalProcessing(settings, caps, ops)

        return ops
    }

    private fun planWhiteBalance(
        settings: CameraSettings,
        caps: CameraCapabilities,
        manualWb: ManualWbReference?,
        ops: MutableList<RequestOp>,
    ) {
        when (settings.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> {
                ops += RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO)
                if (caps.awbLockAvailable) {
                    ops += RequestOp.SetAwbLock(settings.whiteBalanceLock)
                }
            }
            WhiteBalanceMode.MANUAL -> when {
                caps.supportsCctWhiteBalance -> {
                    // Native CCT (API 36): the HAL owns the calibration; we only name the white
                    // point. AWB must be off or CCT is ignored.
                    val range = requireNotNull(caps.cctKelvinRange)
                    ops += RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_OFF)
                    ops += RequestOp.SetAwbLock(false)
                    ops += RequestOp.SetColorCorrectionMode(Camera2Constants.COLOR_CORRECTION_MODE_CCT)
                    ops += RequestOp.SetColorTemperature(
                        settings.kelvin.coerceIn(range.first, range.last),
                    )
                    ops += RequestOp.SetColorTint(
                        settings.tint.coerceIn(
                            Camera2Constants.MIN_COLOR_TINT,
                            Camera2Constants.MAX_COLOR_TINT,
                        ),
                    )
                }
                caps.supportsManualWhiteBalance && manualWb != null -> {
                    // Fallback for devices without CCT: keep the HAL's learned transform (it is the
                    // real sensor->sRGB matrix for this sensor) and adjust only the Bayer gains,
                    // relative to the gains the HAL itself reported for the reference illuminant.
                    // Pixel HALs silently ignore gains unless the mode is TRANSFORM_MATRIX with a
                    // real matrix, so the learned transform is sent alongside.
                    ops += RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_OFF)
                    ops += RequestOp.SetAwbLock(false)
                    ops += RequestOp.SetColorCorrectionMode(
                        Camera2Constants.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                    )
                    ops += RequestOp.SetColorCorrectionTransform(manualWb.colorTransform)
                    ops += RequestOp.SetColorCorrectionGains(
                        WhiteBalance.relativeGains(
                            referenceKelvin = manualWb.kelvin,
                            referenceGains = manualWb.gains,
                            targetKelvin = settings.kelvin,
                            tint = settings.tint,
                        ),
                    )
                }
                else -> {
                    // Neither native CCT nor a learned reference yet; keep auto WB rather than
                    // sending uncalibrated gains.
                    ops += RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO)
                }
            }
        }
    }

    private fun planExposure(
        settings: CameraSettings,
        caps: CameraCapabilities,
        ops: MutableList<RequestOp>,
    ) {
        when (settings.exposureMode) {
            ExposureMode.AUTO -> {
                ops += RequestOp.SetAeMode(Camera2Constants.AE_MODE_ON)
                if (caps.aeLockAvailable) {
                    ops += RequestOp.SetAeLock(settings.aeLock)
                }
            }
            ExposureMode.MANUAL -> {
                val canManual = Camera2Constants.AE_MODE_OFF in caps.aeModes &&
                    caps.sensitivityRange.first <= caps.sensitivityRange.last &&
                    caps.exposureTimeRange.first <= caps.exposureTimeRange.last
                if (canManual) {
                    val iso = settings.iso.coerceIn(
                        caps.sensitivityRange.first,
                        caps.sensitivityRange.last,
                    )
                    val shutter = settings.shutterNs.coerceIn(
                        caps.exposureTimeRange.first,
                        caps.exposureTimeRange.last,
                    )
                    ops += RequestOp.SetAeMode(Camera2Constants.AE_MODE_OFF)
                    ops += RequestOp.SetAeLock(false)
                    ops += RequestOp.SetSensorSensitivity(iso)
                    ops += RequestOp.SetSensorExposureTime(shutter)
                } else {
                    ops += RequestOp.SetAeMode(Camera2Constants.AE_MODE_ON)
                }
            }
        }
    }

    private fun planFocus(
        settings: CameraSettings,
        caps: CameraCapabilities,
        ops: MutableList<RequestOp>,
    ) {
        when (settings.focusMode) {
            FocusMode.AUTO -> ops += RequestOp.SetAfMode(chooseAutoAfMode(caps.afModes))
            FocusMode.MANUAL -> {
                if (caps.supportsManualFocus && Camera2Constants.AF_MODE_OFF in caps.afModes) {
                    ops += RequestOp.SetAfMode(Camera2Constants.AF_MODE_OFF)
                    val diopters = settings.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistance)
                    ops += RequestOp.SetLensFocusDistance(diopters)
                } else {
                    ops += RequestOp.SetAfMode(chooseAutoAfMode(caps.afModes))
                }
            }
        }
    }

    private fun chooseAutoAfMode(afModes: Set<Int>): Int = when {
        Camera2Constants.AF_MODE_CONTINUOUS_VIDEO in afModes -> Camera2Constants.AF_MODE_CONTINUOUS_VIDEO
        Camera2Constants.AF_MODE_CONTINUOUS_PICTURE in afModes -> Camera2Constants.AF_MODE_CONTINUOUS_PICTURE
        Camera2Constants.AF_MODE_AUTO in afModes -> Camera2Constants.AF_MODE_AUTO
        Camera2Constants.AF_MODE_MACRO in afModes -> Camera2Constants.AF_MODE_MACRO
        else -> Camera2Constants.AF_MODE_OFF
    }

    private fun planZoom(
        settings: CameraSettings,
        caps: CameraCapabilities,
        ops: MutableList<RequestOp>,
    ) {
        if (caps.supportsZoomRatio) {
            val range = requireNotNull(caps.zoomRatioRange)
            val ratio = settings.zoomRatio.coerceIn(range.start, range.endInclusive)
            ops += RequestOp.SetZoomRatio(ratio)
        } else {
            val ratio = settings.zoomRatio.coerceIn(1f, caps.maxZoom)
            if (ratio > 1f) {
                ops += RequestOp.SetScalerCropRegion(
                    ZoomMath.cropRegionForZoom(ratio, caps.activeArraySize),
                )
            }
        }
    }

    private fun planMinimalProcessing(
        settings: CameraSettings,
        caps: CameraCapabilities,
        ops: MutableList<RequestOp>,
    ) {
        if (!settings.minimalProcessing) return
        val sel = ProcessingPolicy.minimalProcessingSelection(
            availableNoiseReductionModes = caps.noiseReductionModes,
            availableEdgeModes = caps.edgeModes,
            availableFaceDetectModes = caps.faceDetectModes,
            availableSceneModes = caps.sceneModes,
            availableVideoStabilizationModes = caps.videoStabilizationModes,
            availableAberrationModes = caps.aberrationModes,
        )
        sel.noiseReductionMode?.let { ops += RequestOp.SetNoiseReductionMode(it) }
        sel.edgeMode?.let { ops += RequestOp.SetEdgeMode(it) }
        sel.faceDetectMode?.let { ops += RequestOp.SetFaceDetectMode(it) }
        sel.sceneMode?.let { ops += RequestOp.SetSceneMode(it) }
        sel.videoStabilizationMode?.let { ops += RequestOp.SetVideoStabilizationMode(it) }
        sel.aberrationMode?.let { ops += RequestOp.SetAberrationMode(it) }
    }

    /** Picks a stable 30 fps target from the device's AE FPS ranges. */
    fun chooseTargetFps(ranges: List<FpsRange>, desired: Int = 30): FpsRange? {
        if (ranges.isEmpty()) return null
        ranges.firstOrNull { it.min == desired && it.max == desired }?.let { return it }
        ranges.filter { it.contains(desired) }
            .minByOrNull { it.max - it.min }
            ?.let { return it }
        return ranges.minByOrNull { kotlin.math.abs(it.max - desired) }
    }
}
