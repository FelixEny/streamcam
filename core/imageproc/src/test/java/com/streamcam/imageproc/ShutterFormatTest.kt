package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterFormatTest {

    @Test
    fun `formats common video shutters`() {
        assertEquals("1/60", ShutterFormat.format(16_666_667L))
        assertEquals("1/30", ShutterFormat.format(33_333_333L))
        assertEquals("1/50", ShutterFormat.format(20_000_000L))
        assertEquals("1/24", ShutterFormat.format(41_666_667L))
    }

    @Test
    fun `formats still-camera shutters`() {
        assertEquals("1/8", ShutterFormat.format(125_000_000L))
        assertEquals("1/125", ShutterFormat.format(8_000_000L))
        assertEquals("1/1000", ShutterFormat.format(1_000_000L))
        assertEquals("1/4000", ShutterFormat.format(250_000L))
    }

    @Test
    fun `formats long exposures in seconds`() {
        assertEquals("1s", ShutterFormat.format(1_000_000_000L))
        assertEquals("2s", ShutterFormat.format(2_000_000_000L))
        assertEquals("1.5s", ShutterFormat.format(1_500_000_000L))
        assertEquals("15s", ShutterFormat.format(15_000_000_000L))
    }

    @Test
    fun `non-positive is em dash`() {
        assertEquals("—", ShutterFormat.format(0L))
        assertEquals("—", ShutterFormat.format(-5L))
    }
}
