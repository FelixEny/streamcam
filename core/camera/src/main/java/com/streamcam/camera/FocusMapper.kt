package com.streamcam.camera

import com.streamcam.imageproc.IntRect

/**
 * Maps a tap on the [TextureView] into a sensor active-array region for AF metering.
 *
 * Forward preview transform (see `CameraPreview.applyPreviewTransform`), all centered on the view
 * centre: `view = mirror(scale(rotate(buffer)))`, i.e. point order rotate → scale → mirror.
 * The inverse applied here is therefore **unmirror → unscale → unrotate** — the exact reverse.
 *
 * Additional inputs make the mapping honest about what the user actually sees:
 * - the rendered content box (letterbox): taps in the black bars return null (nothing to focus);
 * - `scalerCrop`: the live `SCALER_CROP_REGION` of the last capture result, so zoomed framing maps
 *   onto the correct part of the sensor;
 * - `maxAfRegions`: when 0 the camera cannot meter a point, so no region is fabricated.
 *
 * Pure and time-free, so every branch is unit-tested.
 */
object FocusMapper {

    /** Region size as a fraction of the visible crop (20 %≈ stock-camera feel). */
    const val REGION_FRACTION = 0.20f

    /**
     * @return the AF region for the tap, or **null** when the tap is outside the visible content,
     * the geometry is degenerate, or the camera cannot meter regions (`maxAfRegions <= 0`).
     */
    fun toRegion(
        tapX: Float,
        tapY: Float,
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        displayRotation: Int,
        lensFacing: Int,
        activeArrayWidth: Int,
        activeArrayHeight: Int,
        maxAfRegions: Int,
        scalerCrop: IntRect? = null,
    ): FocusRegion? {
        if (maxAfRegions <= 0) return null
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return null
        if (activeArrayWidth <= 0 || activeArrayHeight <= 0) return null

        val geometry = PreviewTransform.geometry(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            displayRotationDegrees = displayRotation,
        )

        // Letterbox rejection: only taps inside the rendered content can be focused.
        val contentLeft = (viewWidth - geometry.renderedWidth) / 2f
        val contentTop = (viewHeight - geometry.renderedHeight) / 2f
        if (tapX < contentLeft || tapX > contentLeft + geometry.renderedWidth ||
            tapY < contentTop || tapY > contentTop + geometry.renderedHeight
        ) return null

        // Inverse of the forward point order: unmirror → unscale → unrotate (about the centre).
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        var x = tapX - cx
        var y = tapY - cy
        if (lensFacing == Camera2Constants.LENS_FACING_FRONT) x = -x
        x /= geometry.scaleX
        y /= geometry.scaleY
        val unrotated = unrotate(x, y, geometry.rotationDegrees)
        x = unrotated.first
        y = unrotated.second

        // Buffer pixel (relative to buffer centre) → sensor active-array pixel. The visible area
        // is the scaler crop when zoomed, else the whole active array.
        val crop = scalerCrop ?: IntRect(0, 0, activeArrayWidth, activeArrayHeight)
        val cropW = crop.width.coerceAtLeast(1)
        val cropH = crop.height.coerceAtLeast(1)
        val sensorX = (crop.left + (x + bufferWidth / 2f) / bufferWidth * cropW)
            .coerceIn(0f, activeArrayWidth.toFloat())
        val sensorY = (crop.top + (y + bufferHeight / 2f) / bufferHeight * cropH)
            .coerceIn(0f, activeArrayHeight.toFloat())

        // Region sized against the visible crop so the weight lands where the user tapped.
        val halfW = (cropW * REGION_FRACTION / 2).toInt().coerceAtLeast(1)
        val halfH = (cropH * REGION_FRACTION / 2).toInt().coerceAtLeast(1)
        val left = (sensorX - halfW).toInt()
            .coerceIn(crop.left, (crop.left + cropW - 2 * halfW).coerceAtLeast(crop.left))
        val top = (sensorY - halfH).toInt()
            .coerceIn(crop.top, (crop.top + cropH - 2 * halfH).coerceAtLeast(crop.top))
        return FocusRegion(
            left = left,
            top = top,
            width = 2 * halfW,
            height = 2 * halfH,
            weight = 1000,
        )
    }

    private fun unrotate(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            90 -> y to -x      // inverse of a +90° rotation
            180 -> -x to -y
            270 -> -y to x     // inverse of a +270° rotation
            else -> x to y
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
    fun toAndroid(): android.hardware.camera2.params.MeteringRectangle =
        android.hardware.camera2.params.MeteringRectangle(left, top, width, height, weight)
}
