package com.streamcam.camera

/**
 * Geometry for showing a camera buffer inside a differently shaped view.
 *
 * `TextureView` stretches its buffer across its bounds without preserving the buffer's aspect
 * ratio. [geometry] is the single computation that derives both the display-rotation correction
 * and the letterbox scale, so the two can never disagree: the whole frame stays visible with
 * square pixels (letterbox), matching the streamed framing, with bars on the longer axis of the
 * view.
 *
 * Correction is expressed relative to the implicit full-bounds stretch, so the caller applies it
 * about the view centre *after* rotating the content: `postRotate` first, then `postScale`
 * (Android's `post*` methods prepend, so that call order yields matrix `M = S·R`).
 */
object PreviewTransform {

    /**
     * @param rotationDegrees clockwise display correction to apply to the preview.
     * @param scaleX horizontal correction, relative to the implicit full-bounds stretch.
     * @param scaleY vertical correction, relative to the implicit full-bounds stretch.
     * @param renderedWidth content width in view pixels after the full transform.
     * @param renderedHeight content height in view pixels after the full transform.
     */
    data class Geometry(
        val rotationDegrees: Int,
        val scaleX: Float,
        val scaleY: Float,
        val renderedWidth: Int,
        val renderedHeight: Int,
    )

    private val IDENTITY = Geometry(
        rotationDegrees = 0,
        scaleX = 1f,
        scaleY = 1f,
        renderedWidth = 0,
        renderedHeight = 0,
    )

    /**
     * Display-only preview geometry.
     *
     * The scale is derived from two invariants applied to the content's post-rotation footprint:
     * the rendered content keeps the buffer's aspect ratio, and it fits inside the view while
     * being as large as possible (bars on exactly one axis). A quarter-turn rotation transposes
     * the footprint — the full-view rectangle rotates with the content — so the branch follows
     * the *applied rotation's* parity.
     *
     * Degenerate view or buffer sizes return the identity geometry untouched.
     */
    fun geometry(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        displayRotationDegrees: Int,
    ): Geometry {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return IDENTITY
        }
        val rotation = PreviewOrientation.displayCorrectionDegrees(displayRotationDegrees)
        val transposed = rotation % 180 != 0
        val footprintWidth = if (transposed) viewHeight.toFloat() else viewWidth.toFloat()
        val footprintHeight = if (transposed) viewWidth.toFloat() else viewHeight.toFloat()
        val bufferAspect = bufferWidth.toFloat() / bufferHeight

        val (scaleX, scaleY) = if (bufferAspect * viewHeight <= viewWidth) {
            // Height-fitted content is at most as wide as the view: bars left/right.
            (bufferAspect * viewHeight / footprintWidth) to (viewHeight / footprintHeight)
        } else {
            // The view is narrower than the content: fit width, bars top/bottom.
            (viewWidth / footprintWidth) to (viewWidth / (bufferAspect * footprintHeight))
        }
        return Geometry(
            rotationDegrees = rotation,
            scaleX = scaleX,
            scaleY = scaleY,
            renderedWidth = (footprintWidth * scaleX).toInt(),
            renderedHeight = (footprintHeight * scaleY).toInt(),
        )
    }
}
