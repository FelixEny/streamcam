package com.streamcam.camera

enum class WhiteBalanceMode { AUTO, MANUAL }
enum class ExposureMode { AUTO, MANUAL }
enum class FocusMode { AUTO, MANUAL }

data class CameraSettings(
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val whiteBalanceLock: Boolean = false,
    val kelvin: Int = 5500,
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val aeLock: Boolean = false,
    val iso: Int = 100,
    val shutterNs: Long = 33_333_333L,
    val focusMode: FocusMode = FocusMode.AUTO,
    val focusDistanceDiopters: Float = 0f,
    val zoomRatio: Float = 1f,
    val minimalProcessing: Boolean = false,
)
