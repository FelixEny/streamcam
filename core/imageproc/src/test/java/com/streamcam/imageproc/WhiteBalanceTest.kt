package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {

    @Test
    fun `green channels are always unity`() {
        for (kelvin in listOf(2000, 2700, 4000, 5500, 6500, 8000, 12000)) {
            val gains = WhiteBalance.kelvinToRggbGains(kelvin)
            assertEquals(1f, gains.greenEven, 0f)
            assertEquals(1f, gains.greenOdd, 0f)
        }
    }

    @Test
    fun `warm source boosts blue and cuts red`() {
        val gains = WhiteBalance.kelvinToRggbGains(2700)
        assertTrue("expected gainRed < 1 but was ${gains.red}", gains.red < 1f)
        assertTrue("expected gainBlue > 1 but was ${gains.blue}", gains.blue > 1f)
        assertTrue(gains.blue > gains.red)
    }

    @Test
    fun `cool source boosts red and cuts blue`() {
        val gains = WhiteBalance.kelvinToRggbGains(8000)
        assertTrue("expected gainRed > 1 but was ${gains.red}", gains.red > 1f)
        assertTrue("expected gainBlue < 1 but was ${gains.blue}", gains.blue < 1f)
    }

    @Test
    fun `blue gain decreases monotonically with kelvin`() {
        val low = WhiteBalance.kelvinToRggbGains(2700).blue
        val mid = WhiteBalance.kelvinToRggbGains(5500).blue
        val high = WhiteBalance.kelvinToRggbGains(8000).blue
        assertTrue(low > mid)
        assertTrue(mid > high)
    }

    @Test
    fun `extreme kelvin values stay within gain bounds`() {
        for (kelvin in listOf(0, 100, 1500, 20000, 100000)) {
            val gains = WhiteBalance.kelvinToRggbGains(kelvin)
            assertTrue(gains.red in 0.1f..10f)
            assertTrue(gains.blue in 0.1f..10f)
        }
    }

    @Test
    fun `very warm source clamps blue gain to max`() {
        val gains = WhiteBalance.kelvinToRggbGains(1500)
        assertEquals(10f, gains.blue, 0f)
    }
}
