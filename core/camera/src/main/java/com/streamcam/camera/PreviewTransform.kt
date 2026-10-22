package com.streamcam.camera

/**
 * Geometry for showing a camera buffer inside a differently shaped view.
 *
 * `TextureView` stretches its buffer across its bounds without preserving the buffer's aspect
 * ratio. This helper computes the correction scale that must be applied to the view transform so
 * the buffer is rendered with square pixels ("letterbox": the whole frame is visible, with bars on
 * the longer axis of the view).
 *
 * Correction is expressed relative to the implicit full-bounds stretch, so the caller applies it
 * about the view centre after rotating the content by [rotationDegrees].
 */
object PreviewTransform {

    data class Scale(val x: Float, val y: Float)

    private val IDENTITY = Scale(1f, 1f)

    /**
     * Correction that fills the view with the buffer ("center crop"): the whole view is covered,
     * pixels stay square and the overflowing axis is cropped.
     */
    fun fillScale(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ): Scale {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return IDENTITY
        }
        val quarterTurn = ((rotationDegrees % 360 + 360) % 360) % 180 != 0
        val bufferAspect = bufferWidth.toFloat() / bufferHeight

        return if (quarterTurn) {
            val rotatedAspect = bufferHeight.toFloat() / bufferWidth
            val width = maxOf(viewWidth.toFloat(), viewHeight.toFloat() * rotatedAspect)
            val height = width / rotatedAspect
            Scale(x = width / viewHeight, y = height / viewWidth)
        } else {
            val width = maxOf(viewWidth.toFloat(), viewHeight.toFloat() * bufferAspect)
            val height = width / bufferAspect
            Scale(x = width / viewWidth, y = height / viewHeight)
        }
    }

    /**
     * @param viewWidth current view width in pixels.
     * @param viewHeight current view height in pixels.
     * @param bufferWidth camera buffer width before rotation.
     * @param bufferHeight camera buffer height before rotation.
     * @param rotationDegrees clockwise rotation applied to the buffer before display.
     */
    fun letterboxScale(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ): Scale {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return IDENTITY
        }
        val quarterTurn = ((rotationDegrees % 360 + 360) % 360) % 180 != 0
        val bufferAspect = bufferWidth.toFloat() / bufferHeight

        return if (quarterTurn) {
            // Buffer axes land on the transposed view axes: buffer width spans view height.
            val rotatedAspect = bufferHeight.toFloat() / bufferWidth
            val width = minOf(viewWidth.toFloat(), viewHeight.toFloat() * rotatedAspect)
            val height = width / rotatedAspect
            Scale(x = width / viewHeight, y = height / viewWidth)
        } else {
            val width = minOf(viewWidth.toFloat(), viewHeight.toFloat() * bufferAspect)
            val height = width / bufferAspect
            Scale(x = width / viewWidth, y = height / viewHeight)
        }
    }
}
