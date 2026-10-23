package com.streamcam.camera

/** Chooses a preview stream size, preferring common video sizes. Pure and testable. */
object StreamSizeChooser {
    private val preferred = listOf(IntSize(1920, 1080), IntSize(1280, 720), IntSize(640, 480))

    fun choosePreviewSize(sizes: List<IntSize>): IntSize? {
        if (sizes.isEmpty()) return null
        for (candidate in preferred) {
            sizes.firstOrNull { it == candidate }?.let { return it }
        }
        return sizes.maxByOrNull { it.width.toLong() * it.height }
    }

    /**
     * Resolves the user's output-size preference against the device's supported sizes. A [preferred]
     * size the device does not report falls back to [choosePreviewSize] (never fails hard).
     */
    fun chooseOutputSize(sizes: List<IntSize>, preferred: IntSize?): IntSize? {
        if (sizes.isEmpty()) return null
        if (preferred != null) {
            sizes.firstOrNull { it == preferred }?.let { return it }
        }
        return choosePreviewSize(sizes)
    }
}
