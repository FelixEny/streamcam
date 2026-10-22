package com.streamcam.camera

/**
 * Rotation helpers for putting Camera2 buffers on screen.
 *
 * Camera2 delivers preview buffers in the sensor's native frame. Before display they must be
 * rotated by the *relative rotation* between the sensor orientation and the current display
 * rotation. Back and front sensors are mounted in opposite frames, so the same sensor orientation
 * value means different things for them; the relative rotation is identical in form for both and
 * the front camera additionally needs mirroring for a natural selfie view.
 */
object PreviewOrientation {

    /**
     * Clockwise rotation, in degrees, to apply to a camera buffer so it appears upright on the
     * given display rotation.
     *
     * @param sensorOrientation the camera's `SENSOR_ORIENTATION` value (0..359).
     * @param displayRotationDegrees the display rotation in degrees (0, 90, 180 or 270).
     */
    fun relativeRotation(sensorOrientation: Int, displayRotationDegrees: Int): Int =
        ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360

    /**
     * True when the camera faces the user and its buffers should be mirrored for display.
     */
    fun isFrontFacing(lensFacing: Int): Boolean = lensFacing == Camera2Constants.LENS_FACING_FRONT
}
