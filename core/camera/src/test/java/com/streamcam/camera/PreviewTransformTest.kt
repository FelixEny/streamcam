package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformTest {

    private fun assertScale(expectedX: Float, expectedY: Float, actual: PreviewTransform.Scale) {
        assertEquals(expectedX, actual.x, 0.001f)
        assertEquals(expectedY, actual.y, 0.001f)
    }

    @Test
    fun `matching aspect ratios need no correction`() {
        val scale = PreviewTransform.letterboxScale(1920, 1080, 1920, 1080, 0)
        assertScale(1f, 1f, scale)
    }

    @Test
    fun `wider view letterboxes a sixteen by nine buffer horizontally`() {
        // Pixel 8 Pro landscape preview area is ~2.39:1, the stream is 16:9.
        val scale = PreviewTransform.letterboxScale(2841, 1188, 1920, 1080, 0)
        assertScale(0.7434f, 1f, scale)
    }

    @Test
    fun `letterboxed content keeps the buffer aspect ratio`() {
        val viewWidth = 2841f
        val viewHeight = 1188f
        val scale = PreviewTransform.letterboxScale(2841, 1188, 1920, 1080, 0)
        val renderedWidth = viewWidth * scale.x
        val renderedHeight = viewHeight * scale.y
        assertEquals(16f / 9f, renderedWidth / renderedHeight, 0.001f)
        assertEquals(1188f, renderedHeight, 0.5f)
    }

    @Test
    fun `taller view letterboxes a sixteen by nine buffer vertically`() {
        val scale = PreviewTransform.letterboxScale(1080, 1920, 1920, 1080, 0)
        assertScale(1f, 0.3164f, scale)
    }

    @Test
    fun `quarter turn transposes the axes and keeps pixels square`() {
        // Portrait view, landscape buffer rotated 90: content fills the view width.
        val portrait = PreviewTransform.letterboxScale(1080, 1920, 1920, 1080, 90)
        assertScale(0.5625f, 1.7778f, portrait)

        // Landscape view, landscape buffer rotated 270: content fits the view height.
        val landscape = PreviewTransform.letterboxScale(1920, 1080, 1920, 1080, 270)
        assertScale(0.5625f, 0.5625f, landscape)
    }

    @Test
    fun `half turn keeps the buffer aspect ratio`() {
        val scale = PreviewTransform.letterboxScale(2841, 1188, 1920, 1080, 180)
        assertScale(0.7434f, 1f, scale)
    }

    @Test
    fun `unmeasured view or buffer is left untouched`() {
        assertScale(1f, 1f, PreviewTransform.letterboxScale(0, 0, 1920, 1080, 0))
        assertScale(1f, 1f, PreviewTransform.letterboxScale(2841, 1188, 0, 0, 0))
        assertScale(1f, 1f, PreviewTransform.fillScale(0, 0, 1920, 1080, 0))
        assertScale(1f, 1f, PreviewTransform.fillScale(2841, 1188, 0, 0, 0))
    }

    @Test
    fun `fill covers the wide view by cropping the buffer vertically`() {
        val scale = PreviewTransform.fillScale(2841, 1188, 1920, 1080, 0)
        assertScale(1f, 1.3452f, scale)

        val renderedWidth = 2841f * scale.x
        val renderedHeight = 1188f * scale.y
        assertEquals(16f / 9f, renderedWidth / renderedHeight, 0.001f)
        assertEquals(2841f, renderedWidth, 0.5f)
        assertTrue("content must overflow vertically", renderedHeight > 1188f)
    }

    @Test
    fun `fill covers the tall view by cropping the buffer horizontally`() {
        val scale = PreviewTransform.fillScale(1080, 1920, 1920, 1080, 0)
        assertScale(3.1605f, 1f, scale)
    }

    @Test
    fun `fill quarter turn keeps pixels square and covers the view`() {
        val landscape = PreviewTransform.fillScale(1920, 1080, 1920, 1080, 270)
        assertScale(1.7778f, 1.7778f, landscape)
    }

    @Test
    fun `fill of matching aspect ratios needs no correction`() {
        assertScale(1f, 1f, PreviewTransform.fillScale(1920, 1080, 1920, 1080, 0))
        assertScale(1f, 1f, PreviewTransform.fillScale(1920, 1080, 1920, 1080, 180))
    }
}
