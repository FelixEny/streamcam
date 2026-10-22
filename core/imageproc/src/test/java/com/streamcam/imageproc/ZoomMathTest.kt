package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {

    @Test
    fun `ease endpoints and midpoint`() {
        assertEquals(0f, ZoomMath.easeInOutCubic(0f), 1e-6f)
        assertEquals(1f, ZoomMath.easeInOutCubic(1f), 1e-6f)
        assertEquals(0.5f, ZoomMath.easeInOutCubic(0.5f), 1e-6f)
    }

    @Test
    fun `ease is clamped outside unit range`() {
        assertEquals(0f, ZoomMath.easeInOutCubic(-1f), 1e-6f)
        assertEquals(1f, ZoomMath.easeInOutCubic(2f), 1e-6f)
    }

    @Test
    fun `interpolate hits endpoints`() {
        assertEquals(1f, ZoomMath.interpolateZoom(1f, 3f, 0f), 1e-6f)
        assertEquals(3f, ZoomMath.interpolateZoom(1f, 3f, 1f), 1e-6f)
    }

    @Test
    fun `interpolate midpoint with ease`() {
        assertEquals(2f, ZoomMath.interpolateZoom(1f, 3f, 0.5f), 1e-6f)
    }

    @Test
    fun `crop region at unity zoom equals active array`() {
        val active = IntRect(0, 0, 4000, 3000)
        assertEquals(active, ZoomMath.cropRegionForZoom(1f, active))
    }

    @Test
    fun `crop region centers and shrinks at 2x`() {
        val active = IntRect(0, 0, 4000, 3000)
        assertEquals(IntRect(1000, 750, 3000, 2250), ZoomMath.cropRegionForZoom(2f, active))
    }

    @Test
    fun `crop region clamps ratio below 1`() {
        val active = IntRect(0, 0, 4000, 3000)
        assertEquals(active, ZoomMath.cropRegionForZoom(0.5f, active))
    }

    @Test
    fun `crop area shrinks monotonically as zoom grows`() {
        val active = IntRect(0, 0, 4000, 3000)
        var prevArea = Int.MAX_VALUE
        for (ratio in listOf(1f, 1.5f, 2f, 3f, 4f)) {
            val crop = ZoomMath.cropRegionForZoom(ratio, active)
            val area = crop.width * crop.height
            assertTrue("area should shrink at ratio=$ratio", area < prevArea)
            prevArea = area
        }
    }

    @Test
    fun `crop region respects non-zero origin`() {
        val active = IntRect(100, 200, 2100, 1700)
        val crop = ZoomMath.cropRegionForZoom(2f, active)
        assertEquals(1000, crop.width)
        assertEquals(750, crop.height)
        assertTrue(crop.left >= active.left)
        assertTrue(crop.top >= active.top)
        assertTrue(crop.right <= active.right)
        assertTrue(crop.bottom <= active.bottom)
    }
}
