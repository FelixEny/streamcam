package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoStopsTest {

    @Test
    fun `third stops are ascending and follow the 1-3 stop ratio`() {
        val stops = IsoStops.THIRD_STOPS
        assertTrue(stops.first() == 50)
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
        // Each step is ~1.26x the previous (rounded), i.e. three steps double the ISO.
        assertEquals(100, stops[3])
        assertEquals(200, stops[6])
        assertEquals(400, stops[9])
        assertEquals(800, stops[12])
        assertEquals(1600, stops[15])
    }

    @Test
    fun `pixel 8 pro main camera range keeps the whole ladder`() {
        val stops = IsoStops.forRange(21..10666)
        assertEquals(50, stops.first())
        assertEquals(10000, stops.last())
        assertTrue(500 in stops)
        assertTrue(640 in stops)
        assertTrue(1000 in stops)
        assertTrue(1250 in stops)
    }

    @Test
    fun `front camera range is clipped to its supported stops`() {
        val stops = IsoStops.forRange(16..1044)
        assertEquals(50, stops.first())
        assertEquals(1000, stops.last())
        assertTrue(stops.all { it in 16..1044 })
    }

    @Test
    fun `narrow range returns only stops inside it`() {
        assertEquals(listOf(400, 500, 640, 800, 1000, 1250), IsoStops.forRange(400..1500))
    }

    @Test
    fun `empty or stopless range still yields one usable value`() {
        assertEquals(listOf(1), IsoStops.forRange(0..0))
        assertEquals(listOf(1), IsoStops.forRange(1..1))
        assertEquals(listOf(45), IsoStops.forRange(45..47))
    }
}
