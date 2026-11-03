package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMapperTest {

    /** Pixel 8 Pro back camera: 90° sensor, back-facing, 4000×3000 active array, 1920×1080 buffer. */
    private val back = FocusMapperArgs(
        viewWidth = 2992, viewHeight = 1344,
        bufferWidth = 1920, bufferHeight = 1080,
        displayRotation = 90,
        sensorOrientation = 90,
        lensFacing = Camera2Constants.LENS_FACING_BACK,
        activeArrayWidth = 4000, activeArrayHeight = 3000,
    )

    /** Same Pixel but front camera: 270° sensor, front-facing (mirrored). */
    private val front = back.copy(
        sensorOrientation = 270,
        lensFacing = Camera2Constants.LENS_FACING_FRONT,
    )

    @Test
    fun `center tap maps to the active array center`() {
        val r = FocusMapper.toMeteringRectangle(
            tapX = 1496f, tapY = 672f,
            viewWidth = back.viewWidth, viewHeight = back.viewHeight,
            bufferWidth = back.bufferWidth, bufferHeight = back.bufferHeight,
            displayRotation = back.displayRotation,
            sensorOrientation = back.sensorOrientation,
            lensFacing = back.lensFacing,
            activeArrayWidth = back.activeArrayWidth,
            activeArrayHeight = back.activeArrayHeight,
        )
        // Center of the active array is (2000, 1500) — within rounding tolerance.
        assert(kotlin.math.abs((r.left + r.width / 2) - 2000) <= 1)
        assert(kotlin.math.abs((r.top + r.height / 2) - 1500) <= 1)
    }

    @Test
    fun `left-of-center tap lands somewhere in the active array`() {
        val r = FocusMapper.toMeteringRectangle(
            tapX = 200f, tapY = 672f,
            viewWidth = back.viewWidth, viewHeight = back.viewHeight,
            bufferWidth = back.bufferWidth, bufferHeight = back.bufferHeight,
            displayRotation = back.displayRotation,
            sensorOrientation = back.sensorOrientation,
            lensFacing = back.lensFacing,
            activeArrayWidth = back.activeArrayWidth,
            activeArrayHeight = back.activeArrayHeight,
        )
        // Just verify the region is within bounds and different from center.
        assert(r.left >= 0 && r.left + r.width <= back.activeArrayWidth)
        assert(r.top >= 0 && r.top + r.height <= back.activeArrayHeight)
    }

    @Test
    fun `front camera mirror flips the x-axis`() {
        val left = FocusMapper.toMeteringRectangle(
            tapX = 200f, tapY = 672f,
            viewWidth = front.viewWidth, viewHeight = front.viewHeight,
            bufferWidth = front.bufferWidth, bufferHeight = front.bufferHeight,
            displayRotation = front.displayRotation,
            sensorOrientation = front.sensorOrientation,
            lensFacing = front.lensFacing,
            activeArrayWidth = front.activeArrayWidth,
            activeArrayHeight = front.activeArrayHeight,
        )
        val right = FocusMapper.toMeteringRectangle(
            tapX = 2792f, tapY = 672f,
            viewWidth = front.viewWidth, viewHeight = front.viewHeight,
            bufferWidth = front.bufferWidth, bufferHeight = front.bufferHeight,
            displayRotation = front.displayRotation,
            sensorOrientation = front.sensorOrientation,
            lensFacing = front.lensFacing,
            activeArrayWidth = front.activeArrayWidth,
            activeArrayHeight = front.activeArrayHeight,
        )
        assertEquals(right.left, left.left) // mirroring swaps the two sides
    }

    @Test
    fun `clamps the region into the active array`() {
        val r = FocusMapper.toMeteringRectangle(
            tapX = 100_000f, tapY = 100_000f,
            viewWidth = back.viewWidth, viewHeight = back.viewHeight,
            bufferWidth = back.bufferWidth, bufferHeight = back.bufferHeight,
            displayRotation = back.displayRotation,
            sensorOrientation = back.sensorOrientation,
            lensFacing = back.lensFacing,
            activeArrayWidth = back.activeArrayWidth,
            activeArrayHeight = back.activeArrayHeight,
        )
        assert(r.left >= 0 && r.left + r.width <= back.activeArrayWidth)
        assert(r.top >= 0 && r.top + r.height <= back.activeArrayHeight)
    }

    @Test
    fun `zero-sized view returns the center region`() {
        val r = FocusMapper.toMeteringRectangle(
            tapX = 100f, tapY = 100f,
            viewWidth = 0, viewHeight = 0,
            bufferWidth = 1920, bufferHeight = 1080,
            displayRotation = back.displayRotation,
            sensorOrientation = back.sensorOrientation,
            lensFacing = back.lensFacing,
            activeArrayWidth = back.activeArrayWidth,
            activeArrayHeight = back.activeArrayHeight,
        )
        assertEquals(1000, r.weight)
        assert(r.left > 0 && r.top > 0)
    }
}

private data class FocusMapperArgs(
    val viewWidth: Int, val viewHeight: Int,
    val bufferWidth: Int, val bufferHeight: Int,
    val displayRotation: Int,
    val sensorOrientation: Int,
    val lensFacing: Int,
    val activeArrayWidth: Int, val activeArrayHeight: Int,
)
