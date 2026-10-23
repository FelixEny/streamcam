package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformTest {

    private fun geometry(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        displayRotationDegrees: Int,
    ) = PreviewTransform.geometry(viewWidth, viewHeight, bufferWidth, bufferHeight, displayRotationDegrees)

    private fun assertScale(expectedX: Float, expectedY: Float, actual: PreviewTransform.Geometry) {
        assertEquals(expectedX, actual.scaleX, 0.001f)
        assertEquals(expectedY, actual.scaleY, 0.001f)
    }

    private fun assertFitsAndKeepsAspect(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        actual: PreviewTransform.Geometry,
    ) {
        val (footprintWidth, footprintHeight) =
            if (actual.rotationDegrees % 180 != 0) viewHeight to viewWidth else viewWidth to viewHeight
        val renderedWidth = footprintWidth * actual.scaleX
        val renderedHeight = footprintHeight * actual.scaleY
        assertEquals(bufferWidth.toFloat() / bufferHeight, renderedWidth / renderedHeight, 0.001f)
        assertTrue("content wider than view", renderedWidth <= viewWidth + 0.5f)
        assertTrue("content taller than view", renderedHeight <= viewHeight + 0.5f)
    }

    private fun assertRendered(
        expectedWidth: Int,
        expectedHeight: Int,
        actual: PreviewTransform.Geometry,
    ) {
        assertEquals(expectedWidth.toFloat(), actual.renderedWidth.toFloat(), 1f)
        assertEquals(expectedHeight.toFloat(), actual.renderedHeight.toFloat(), 1f)
    }

    @Test
    fun `matching aspect ratios in portrait need no correction`() {
        val geometry = geometry(1080, 1920, 1080, 1920, 0)
        assertEquals(0, geometry.rotationDegrees)
        assertScale(1f, 1f, geometry)
        assertRendered(1080, 1920, geometry)
    }

    @Test
    fun `landscape display rotates a quarter turn the other way`() {
        // Display rotations are counterclockwise, so the applied correction is their negation.
        assertEquals(270, geometry(2841, 1188, 1920, 1080, 90).rotationDegrees)
        assertEquals(180, geometry(2841, 1188, 1920, 1080, 180).rotationDegrees)
        assertEquals(90, geometry(2841, 1188, 1920, 1080, 270).rotationDegrees)
        assertEquals(0, geometry(2841, 1188, 1920, 1080, 0).rotationDegrees)
    }

    @Test
    fun `landscape display letterboxes the wide view horizontally`() {
        // Pixel 8 Pro: display ROTATION_90, preview area ~2.39:1, stream 16:9. The quarter-turn
        // rotation transposes the footprint, so the scale fits the transposed axes.
        val geometry = geometry(2841, 1188, 1920, 1080, 90)
        assertScale(1.7778f, 0.4181f, geometry)
        assertRendered(2112, 1188, geometry)
        assertFitsAndKeepsAspect(2841, 1188, 1920, 1080, geometry)
    }

    @Test
    fun `reverse landscape display fills a matching view exactly`() {
        // View aspect equals the buffer aspect; the quarter-turn fit lands on an exact fill.
        val geometry = geometry(1920, 1080, 1920, 1080, 270)
        assertEquals(90, geometry.rotationDegrees)
        assertScale(1.7778f, 0.5625f, geometry)
        assertRendered(1920, 1080, geometry)
        assertFitsAndKeepsAspect(1920, 1080, 1920, 1080, geometry)
    }

    @Test
    fun `portrait display letterboxes the tall view vertically`() {
        // No rotation (display 0): footprint stays the view rect; a 16:9 buffer fills the width
        // and leaves bars top and bottom.
        val geometry = geometry(1080, 1920, 1920, 1080, 0)
        assertEquals(0, geometry.rotationDegrees)
        assertScale(1f, 0.3164f, geometry)
        assertRendered(1080, 607, geometry)
        assertFitsAndKeepsAspect(1080, 1920, 1920, 1080, geometry)
    }

    @Test
    fun `upside down display keeps the portrait letterbox`() {
        val geometry = geometry(1080, 1920, 1920, 1080, 180)
        assertEquals(180, geometry.rotationDegrees)
        assertScale(1f, 0.3164f, geometry)
        assertFitsAndKeepsAspect(1080, 1920, 1920, 1080, geometry)
    }

    @Test
    fun `letterboxed landscape keeps the buffer aspect in every display rotation`() {
        for (display in intArrayOf(0, 90, 180, 270)) {
            assertFitsAndKeepsAspect(2841, 1188, 1920, 1080, geometry(2841, 1188, 1920, 1080, display))
        }
    }

    @Test
    fun `unmeasured view or buffer is left untouched`() {
        val degenerate = listOf(
            geometry(0, 0, 1920, 1080, 90),
            geometry(2841, 1188, 0, 0, 90),
            geometry(-1, 1188, 1920, 1080, 90),
            geometry(2841, -1, 1920, 1080, 90),
        )
        for (value in degenerate) {
            assertEquals(0, value.rotationDegrees)
            assertScale(1f, 1f, value)
            assertEquals(0, value.renderedWidth)
            assertEquals(0, value.renderedHeight)
        }
    }
}
