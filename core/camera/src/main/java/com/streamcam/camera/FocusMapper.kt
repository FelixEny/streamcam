package com.streamcam.camera

/**
 * Maps a tap on the [TextureView] to a `MeteringRectangle` in sensor active-array coordinates.
 *
 * The preview's on-screen content is a transformed version of the sensor frame: rotated, scaled,
 * and for front lenses mirrored. A tap lands at the *rotated* position the user sees, so the mapper
 * must invert `PreviewTransform`'s geometry (rotation + letterbox scale + mirror) and clip into the
 * active array.
 *
 * Pure, so it is unit-tested independently of the preview.
 */
object FocusMapper {

    /**
     * Tap region size expressed as a fraction of the sensor active-array. 20 % is the same
     * proportion vMix and the stock camera use — big enough for the AF to converge, small enough
     * for precise framing at tele.
     */
    const val REGION_FRACTION = 0.20f

    /**
     * @param tapX tap X in view pixels (0 = left of the *view*, not the content)
     * @param tapY tap Y in view pixels
     * @param viewWidth view bounds (px)
     * @param viewHeight view bounds (px)
     * @param bufferWidth sensor output width (px)
     * @param bufferHeight sensor output height (px)
     * @param displayRotation 0/90/180/270 clockwise from natural
     * @param sensorOrientation 90 (back Pixel) or 270 (front Pixel)
     * @param lensFacing [Camera2Constants.LENS_FACING_BACK] or [_FRONT]
     * @param activeArrayWidth active array width (px)
     * @param activeArrayHeight active array height (px)
     *
     * Returns a `MeteringRectangle` centred on the tap, clipped to [0,0 .. activeArray].
     * The `weight` is the AF region importance — 1000 is the maximum the HAL will honour.
     */
    fun toMeteringRectangle(
        tapX: Float,
        tapY: Float,
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        displayRotation: Int,
        sensorOrientation: Int,
        lensFacing: Int,
        activeArrayWidth: Int,
        activeArrayHeight: Int,
    ): FocusRegion {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return centerRegion(activeArrayWidth, activeArrayHeight)
        }

        // Apply the same transformation the preview does, then invert it. The preview applies
        // rotate(scale(mirror(point))) centered on the view center; here we apply those in reverse
        // order (mirror → scale → rotate) to bring the tap back into buffer coordinates.
        val mirrored = lensFacing == Camera2Constants.LENS_FACING_FRONT
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f

        var x = tapX - cx
        var y = tapY - cy
        if (mirrored) x = -x
        val rotation = PreviewOrientation.displayCorrectionDegrees(displayRotation)
        val rotated = rotateInverse(x, y, rotation)
        x = rotated.first
        y = rotated.second

        val geometry = PreviewTransform.geometry(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            displayRotationDegrees = displayRotation,
        )
        x /= geometry.scaleX
        y /= geometry.scaleY

        // Now in buffer coordinates (sensor orientation frame). Convert to sensor active-array
        // coordinates. The buffer is a 16:9 output from the full active array (e.g. 4000×3000);
        // each axis scales independently to map the tap into active-array coordinates.
        val scaleX = if (bufferWidth > 0) activeArrayWidth.toFloat() / bufferWidth else 1f
        val scaleY = if (bufferHeight > 0) activeArrayHeight.toFloat() / bufferHeight else 1f
        val sensorX = ((x + bufferWidth / 2f) * scaleX).coerceIn(0f, activeArrayWidth.toFloat())
        val sensorY = ((y + bufferHeight / 2f) * scaleY).coerceIn(0f, activeArrayHeight.toFloat())

        val regionHalfW = (activeArrayWidth * REGION_FRACTION / 2).toInt().coerceAtLeast(1)
        val regionHalfH = (activeArrayHeight * REGION_FRACTION / 2).toInt().coerceAtLeast(1)
        val left = (sensorX - regionHalfW).toInt().coerceIn(0, activeArrayWidth - 2 * regionHalfW)
        val top = (sensorY - regionHalfH).toInt().coerceIn(0, activeArrayHeight - 2 * regionHalfH)
        return FocusRegion(
            left = left,
            top = top,
            width = 2 * regionHalfW,
            height = 2 * regionHalfH,
            weight = 1000,
        )
    }

    private fun rotateInverse(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            90 -> y to -x       // invert a +90° CW rotation: rotate -90°
            180 -> -x to -y
            270 -> -y to x      // invert a +270° CW (= -90°) rotation: rotate +90°
            else -> x to y
        }

    private fun centerRegion(w: Int, h: Int): FocusRegion {
        val rw = (w * REGION_FRACTION).toInt().coerceAtLeast(1)
        val rh = (h * REGION_FRACTION).toInt().coerceAtLeast(1)
        return FocusRegion(left = (w - rw) / 2, top = (h - rh) / 2, width = rw, height = rh, weight = 1000)
    }
}

/** Pure-data equivalent of `android.hardware.camera2.params.MeteringRectangle`. */
data class FocusRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val weight: Int,
) {
    /** Convert to the Android Camera2 `MeteringRectangle`. */
    fun toAndroid(): android.hardware.camera2.params.MeteringRectangle =
        android.hardware.camera2.params.MeteringRectangle(left, top, width, height, weight)
}
