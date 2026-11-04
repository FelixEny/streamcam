package com.streamcam.camera

import com.streamcam.imageproc.ZoomRamp

enum class WhiteBalanceMode { AUTO, MANUAL }
enum class ExposureMode { AUTO, MANUAL }
enum class FocusMode { AUTO, MANUAL }

/** Tap-to-focus state carried across repeating-request plans. */
sealed class FocusIntent {
    /** Continuous AF as usual. */
    data object IDLE : FocusIntent()
    /** One-shot tap: regions (when supported) + trigger sent via a single capture. */
    data class TAPPING(val region: FocusRegion?) : FocusIntent()
    /** Focus held at the measured distance (AF_MODE_OFF + explicit LENS_FOCUS_DISTANCE). */
    data class LOCKED(val distanceDiopters: Float) : FocusIntent()
}

enum class FocusState { IDLE, TAPPING, LOCKED, FAILED }

data class CameraSettings(
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val whiteBalanceLock: Boolean = false,
    val kelvin: Int = 5500,
    /** Green/magenta CCT tint, -50..+50. Ignored in AUTO mode. */
    val tint: Int = 0,
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val aeLock: Boolean = false,
    val iso: Int = 100,
    val shutterNs: Long = 33_333_333L,
    val focusMode: FocusMode = FocusMode.AUTO,
    val focusDistanceDiopters: Float = 0f,
    val zoomRatio: Float = 1f,
    /** Flow wheel ramp rate in ln-units/s ([ZoomRamp]); lower is slower. */
    val zoomWheelRate: Float = ZoomRamp.DEFAULT_RATE,
)
