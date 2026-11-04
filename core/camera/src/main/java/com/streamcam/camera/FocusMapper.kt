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
     * The region of the sensor active array that the configured preview stream actually covers.
     *
     * A preview stream is rarely the same aspect ratio as the sensor (e.g. a 16:9 preview on a
     * 4:3 sensor). Camera2 crops the sensor to the stream's aspect ratio before producing the
     * preview, so a tap at the top of the preview must not map to the top of the active array - it
     * maps to the top of that crop. Per the Camera2 `SCALER_CROP_REGION` contract these
     * per-stream crops are **centred** within the crop region.
     *
     * [scalerCrop] is the live zoom region (or null at zoom 1.0). It is intersected with
     * [activeArray] first, so a stale or out-of-range crop can never move the mapping outside the
     * sensor. The active array is a full rectangle: non-zero `left`/`top` origins are preserved.
     *
     * Exposed so callers can log/diagnose the mapping; [toRegion] uses it internally.
     */
    fun streamCrop(
        activeArray: IntRect,
        bufferWidth: Int,
        bufferHeight: Int,
        scalerCrop: IntRect? = null,
    ): IntRect {
        if (activeArray.width <= 0 || activeArray.height <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0
        ) {
            return IntRect(activeArray.left, activeArray.top, activeArray.left, activeArray.top)
        }
        val crop = safeCrop(activeArray, scalerCrop)
        val cropW = crop.width.coerceAtLeast(1)
        val cropH = crop.height.coerceAtLeast(1)
        val streamAspect = bufferWidth.toFloat() / bufferHeight
        val cropAspect = cropW.toFloat() / cropH
        val (streamW, streamH) = if (streamAspect > cropAspect) {
            cropW to (cropW / streamAspect).toInt().coerceAtLeast(1)
        } else {
            (cropH * streamAspect).toInt().coerceAtLeast(1) to cropH
        }
        val left = crop.left + (cropW - streamW) / 2
        val top = crop.top + (cropH - streamH) / 2
        return IntRect(left, top, left + streamW, top + streamH)
    }

    /**
     * Intersects [scalerCrop] with the active array and falls back to the full active array when
     * the result is empty. Keeps every later calculation inside real sensor bounds.
     */
    private fun safeCrop(activeArray: IntRect, scalerCrop: IntRect?): IntRect {
        if (scalerCrop == null) return activeArray
        val left = maxOf(scalerCrop.left, activeArray.left)
        val top = maxOf(scalerCrop.top, activeArray.top)
        val right = minOf(scalerCrop.right, activeArray.right)
        val bottom = minOf(scalerCrop.bottom, activeArray.bottom)
        return if (right > left && bottom > top) {
            IntRect(left, top, right, bottom)
        } else {
            activeArray
        }
    }

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
        activeArray: IntRect,
        maxAfRegions: Int,
        scalerCrop: IntRect? = null,
    ): FocusRegion? {
        if (maxAfRegions <= 0) return null
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return null
        if (activeArray.width <= 0 || activeArray.height <= 0) return null

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

        // The buffer covers the stream crop of the sensor (aspect-ratio crop of the scaler crop),
        // not the whole scaler crop - account for that before mapping into active-array space.
        val crop = streamCrop(activeArray, bufferWidth, bufferHeight, scalerCrop)
        val cropW = crop.width.coerceAtLeast(1)
        val cropH = crop.height.coerceAtLeast(1)
        val sensorX = (crop.left + (x + bufferWidth / 2f) / bufferWidth * cropW)
            .coerceIn(activeArray.left.toFloat(), activeArray.right.toFloat())
        val sensorY = (crop.top + (y + bufferHeight / 2f) / bufferHeight * cropH)
            .coerceIn(activeArray.top.toFloat(), activeArray.bottom.toFloat())

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
