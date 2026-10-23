package com.streamcam.camera

import com.streamcam.imageproc.RggbGains

/**
 * White-balance values reported by the HAL in a capture result, as opposed to what the UI
 * requested. The HAL may clamp requested values (e.g. CCT outside the device range, tint outside
 * what a temperature can physically reach), so the UI shows these when available.
 *
 * @param colorCorrectionMode `COLOR_CORRECTION_MODE` actually applied
 * @param awbMode `CONTROL_AWB_MODE` actually applied
 * @param colorTemperature applied CCT in Kelvin (API 36 CCT mode), null otherwise
 * @param colorTint applied tint -50..+50 (API 36 CCT mode), null otherwise
 * @param gains `COLOR_CORRECTION_GAINS` actually applied
 * @param sceneCct scene CCT estimated from `SENSOR_NEUTRAL_COLOR_POINT`, when reported
 */
data class AppliedWhiteBalance(
    val colorCorrectionMode: Int?,
    val awbMode: Int?,
    val colorTemperature: Int?,
    val colorTint: Int?,
    val gains: RggbGains?,
    val sceneCct: Int?,
)
