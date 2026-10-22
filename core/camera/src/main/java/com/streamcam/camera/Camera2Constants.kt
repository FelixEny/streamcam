package com.streamcam.camera

// Pure-Kotlin mirror of the Camera2 constants this app uses (verified against API 34).
// Keeps RequestPlanner JVM-testable; RequestApplier maps RequestOp values onto the real keys.
object Camera2Constants {
    // LENS_FACING — values follow android.hardware.camera2.CameraCharacteristics
    // (LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1, LENS_FACING_EXTERNAL = 2).
    const val LENS_FACING_FRONT = 0
    const val LENS_FACING_BACK = 1
    const val LENS_FACING_EXTERNAL = 2

    // CONTROL_AE_MODE
    const val AE_MODE_OFF = 0
    const val AE_MODE_ON = 1

    // CONTROL_AWB_MODE
    const val AWB_MODE_OFF = 0
    const val AWB_MODE_AUTO = 1

    // CONTROL_AF_MODE
    const val AF_MODE_OFF = 0
    const val AF_MODE_AUTO = 1
    const val AF_MODE_MACRO = 2
    const val AF_MODE_CONTINUOUS_VIDEO = 3
    const val AF_MODE_CONTINUOUS_PICTURE = 4

    // COLOR_CORRECTION_MODE
    const val COLOR_CORRECTION_MODE_TRANSFORM_MATRIX = 0

    // CONTROL_MODE
    const val CONTROL_MODE_AUTO = 1
}
