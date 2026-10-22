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
}
