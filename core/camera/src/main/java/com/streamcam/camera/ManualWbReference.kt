package com.streamcam.camera

import com.streamcam.imageproc.RggbGains

/**
 * A frozen snapshot of the device's own colour calibration, captured while auto white balance was
 * running. Used as the reference point for the relative manual-WB fallback on devices without
 * native CCT support:
 *
 * - [colorTransform]: sensor->sRGB `COLOR_CORRECTION_TRANSFORM` reported by the HAL.
 * - [gains]: `COLOR_CORRECTION_GAINS` the HAL applied for [kelvin] under the current illuminant.
 * - [kelvin]: the scene CCT the snapshot corresponds to (`SENSOR_NEUTRAL_COLOR_POINT` converted
 *   to CCT, or a default when the HAL does not report one).
 *
 * Freezing this at the moment the user switches to manual keeps transform and gains consistent
 * with each other — unlike pairing a learned transform with display-derived gains, both halves of
 * the correction then describe the same white point.
 */
data class ManualWbReference(
    val colorTransform: List<Int>,
    val gains: RggbGains,
    val kelvin: Int,
)
