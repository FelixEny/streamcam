package com.streamcam.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomFormatTest {

    @Test
    fun `whole ratios drop the decimal`() {
        assertEquals("1x", formatZoom(1f))
        assertEquals("2x", formatZoom(2f))
        assertEquals("10x", formatZoom(10f))
    }

    @Test
    fun `fractional ratios keep one decimal`() {
        assertEquals("0.5x", formatZoom(0.5f))
        assertEquals("1.5x", formatZoom(1.5f))
        assertEquals("2.7x", formatZoom(2.7f))
    }

    @Test
    fun `float noise does not produce a spurious decimal`() {
        // Values that round to a whole number after persistence/coercion must read as whole.
        assertEquals("1x", formatZoom(1.0000001f))
        assertEquals("1x", formatZoom(0.99999994f))
        assertEquals("2x", formatZoom(1.9999999f))
    }

    @Test
    fun `a lens wide end reads as one x`() {
        // The Pixel main lens reports a minimum of ~0.973x; at one-decimal display it is 1x,
        // matching the highlighted 1x preset chip.
        assertEquals("1x", formatZoom(0.9731855f))
    }
}
