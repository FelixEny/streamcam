package com.streamcam.camera

/**
 * Rotation helpers for putting Camera2 buffers on screen.
 *
 * [TextureView rotates the sensor buffer for its own orientation](https://developer.android.com/media/camera/camera2/camera-preview)
 * but does not apply device rotation or preserve aspect ratio — that is the application's job.
 * The app therefore applies a *display-only* correction: clockwise rotation of `-displayRotation`
 * (display rotations are reported counterclockwise), the same transform the
 * [camera2 codelab](https://developer.android.com/codelabs/android-camera2-preview) applies via
 * `postRotate(-surfaceRotationDegrees)` and the
 * [ChromeOS camera-orientation guide](https://developer.android.com/develop/devices/chromeos/learn/camera-orientation)
 * describes as rotating the TextureView by displayRotation counterclockwise.
 *
 * The encoder surface is fed directly by the camera, so none of this reaches the stream: the
 * streamed picture keeps the sensor's framing.
 */
object PreviewOrientation {

    /**
     * Clockwise rotation, in degrees, the preview view must apply for the given display
     * rotation. Display rotations are reported counterclockwise, so the correction is their
     * negation, normalised into 0..359.
     *
     * @param displayRotationDegrees display rotation in degrees (0, 90, 180 or 270).
     */
    fun displayCorrectionDegrees(displayRotationDegrees: Int): Int =
        ((-displayRotationDegrees) % 360 + 360) % 360

    /**
     * Relative rotation between sensor frame and display frame, 0..359. Back and front sensors
     * are mounted in opposite frames, so the display rotation subtracts with opposite sign.
     *
     * Diagnostic only — the applied rotation is [displayCorrectionDegrees]. Use it to interpret
     * logs and screenshots, never as a transform input.
     *
     * @param sensorOrientation the camera's `SENSOR_ORIENTATION` value (0..359).
     * @param displayRotationDegrees display rotation in degrees (0, 90, 180 or 270).
     * @param lensFacing one of [Camera2Constants]'s `LENS_FACING_*` values.
     */
    fun relativeRotation(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        lensFacing: Int,
    ): Int {
        val displaySign = if (isFrontFacing(lensFacing)) 1 else -1
        return ((sensorOrientation - displayRotationDegrees * displaySign) % 360 + 360) % 360
    }

    /**
     * True when the camera faces the user; its preview is mirrored for a natural selfie view.
     * The stream stays unmirrored.
     */
    fun isFrontFacing(lensFacing: Int): Boolean = lensFacing == Camera2Constants.LENS_FACING_FRONT
}
