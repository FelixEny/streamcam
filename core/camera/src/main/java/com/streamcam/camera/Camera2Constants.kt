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

    // CONTROL_AF_TRIGGER (one-shot trigger values)
    const val AF_TRIGGER_IDLE = 0
    const val AF_TRIGGER_START = 1
    const val AF_TRIGGER_CANCEL = 2

    // CONTROL_AF_STATE values (from CaptureResult.CONTROL_AF_STATE)
    const val AF_STATE_INACTIVE = 0
    const val AF_STATE_PASSIVE_SCAN = 1
    const val AF_STATE_PASSIVE_FOCUSED = 2
    const val AF_STATE_ACTIVE_SCAN = 3
    const val AF_STATE_FOCUSED_LOCKED = 4
    const val AF_STATE_NOT_FOCUSED_LOCKED = 5
    const val AF_STATE_PASSIVE_UNFOCUSED = 6

    // COLOR_CORRECTION_MODE (CCT added in API 36; value from CameraMetadata)
    const val COLOR_CORRECTION_MODE_TRANSFORM_MATRIX = 0
    const val COLOR_CORRECTION_MODE_CCT = 3

    // COLOR_CORRECTION_COLOR_TINT range (API 36 CCT mode; D_uv +/-0.01 around the Planckian locus)
    const val MIN_COLOR_TINT = -50
    const val MAX_COLOR_TINT = 50

    // CONTROL_MODE
    const val CONTROL_MODE_AUTO = 1

    // CONTROL_AF_LOCK (boolean) — locks focus at the current distance.
    // NOTE: CONTROL_AF_LOCK is deprecated in API 28+; prefer AF_MODE_OFF to lock.
    const val CONTROL_AF_LOCK = 1
    const val CONTROL_AF_LOCK_OFF = 0
}
