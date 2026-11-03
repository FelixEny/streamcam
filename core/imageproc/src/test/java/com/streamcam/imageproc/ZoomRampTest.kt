package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomRampTest {

    private val ramp = ZoomRamp(ratePerSec = 0.8f, eventHoldMs = 120L)

    @Test
    fun `first tick only establishes the baseline`() {
        ramp.onWheel(1, nowMs = 0L)
        assertNull(ramp.advance(0L, current = 1f, minZoom = 0.5f, maxZoom = 10f))
        val next = ramp.advance(50L, current = 1f, minZoom = 0.5f, maxZoom = 10f)
        assertEquals(1.0408f, next!!, 1e-4f) // e^(0.8 * 0.05)
    }

    @Test
    fun `ramping in and out moves in the requested direction`() {
        ramp.onWheel(1, nowMs = 0L)
        ramp.advance(0L, 2f, 0.5f, 10f)
        val up = ramp.advance(50L, 2f, 0.5f, 10f)!!
        assertTrue(up > 2f)

        ramp.onWheel(-1, nowMs = 60L)
        val down = ramp.advance(110L, up, 0.5f, 10f)!!
        assertTrue(down < up)
    }

    @Test
    fun `ramp stops after the event hold window`() {
        ramp.onWheel(1, nowMs = 0L)
        ramp.advance(0L, 1f, 0.5f, 10f)
        assertTrue(ramp.isActive(100L))
        assertFalse(ramp.isActive(200L))
        assertNull(ramp.advance(200L, 1.2f, 0.5f, 10f))
    }

    @Test
    fun `clamps at both ends and reports no change`() {
        ramp.onWheel(1, nowMs = 0L)
        ramp.advance(0L, 9.9f, 0.5f, 10f)
        assertEquals(10f, ramp.advance(50L, 9.9f, 0.5f, 10f)!!, 1e-5f)
        assertNull(ramp.advance(100L, 10f, 0.5f, 10f))

        ramp.onWheel(-1, nowMs = 200L)
        ramp.advance(200L, 0.51f, 0.5f, 10f)
        assertEquals(0.5f, ramp.advance(250L, 0.51f, 0.5f, 10f)!!, 1e-5f)
        assertNull(ramp.advance(300L, 0.5f, 0.5f, 10f))
    }

    @Test
    fun `a stalled tick cannot jump the zoom`() {
        ramp.onWheel(1, nowMs = 0L)
        ramp.advance(0L, 1f, 0.5f, 10f)
        // A 110 ms tick is still inside the hold window but is capped at MAX_TICK_MS (100 ms),
        // so a slow frame cannot cause a visibly larger jump.
        val next = ramp.advance(110L, 1f, 0.5f, 10f)!!
        assertEquals(1.0833f, next, 1e-3f) // e^(0.8 * 0.1)
    }

    @Test
    fun `reaches the ten x ceiling in about twelve seconds of held input`() {
        val ramp = ZoomRamp(ratePerSec = 0.2f, eventHoldMs = 120L)
        var ratio = 1f
        var now = 0L
        ramp.onWheel(1, now)
        ramp.advance(now, ratio, 0.5f, 10f)
        repeat(235) { // 11.75 s of wheel events arriving every 50 ms
            now += 50L
            ramp.onWheel(1, now)
            ramp.advance(now, ratio, 0.5f, 10f)?.let { ratio = it }
        }
        assertEquals(10f, ratio, 0.05f)
    }

    @Test
    fun `speed control maps logarithmically between the min and max rate`() {
        assertEquals(ZoomRamp.MIN_RATE, ZoomRamp.rateFromSlider(0f), 1e-6f)
        assertEquals(ZoomRamp.MAX_RATE, ZoomRamp.rateFromSlider(1f), 1e-5f)
        assertEquals(ZoomRamp.DEFAULT_RATE, ZoomRamp.rateFromSlider(ZoomRamp.sliderFromRate(0.2f)), 1e-4f)
        // The slow half of the slider covers the sub-0.1 rates.
        assertTrue(ZoomRamp.rateFromSlider(0.5f) < 0.15f)
    }

    @Test
    fun `speed label reports seconds to ten x`() {
        assertEquals(115f, ZoomRamp.secondsToTenX(0.02f), 1.0f)
        assertEquals(23f, ZoomRamp.secondsToTenX(0.1f), 0.5f)
        assertEquals(3.8f, ZoomRamp.secondsToTenX(0.6f), 0.1f)
    }
}
