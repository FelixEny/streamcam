package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {

    private val unity = RggbGains(red = 1f, greenEven = 1f, greenOdd = 1f, blue = 1f)

    @Test
    fun `target equal to reference returns reference gains unchanged`() {
        val reference = RggbGains(red = 1.2f, greenEven = 1.5f, greenOdd = 1.5f, blue = 2.1f)
        for (kelvin in listOf(2000, 2700, 3200, 4500, 5500, 6500, 8000, 10000)) {
            val gains = WhiteBalance.relativeGains(kelvin, reference, kelvin, tint = 0)
            assertTrue(
                "gains must be identical at target==reference ($kelvin K), got $gains",
                WhiteBalance.nearlyEqual(gains, reference),
            )
        }
    }

    @Test
    fun `warm target boosts blue and cuts red relative to reference`() {
        val gains = WhiteBalance.relativeGains(5500, unity, 3200, tint = 0)
        assertTrue("expected gainRed < 1 but was ${gains.red}", gains.red < 1f)
        assertTrue("expected gainBlue > 1 but was ${gains.blue}", gains.blue > 1f)
        assertTrue(gains.blue > gains.red)
    }

    @Test
    fun `cool target boosts red and cuts blue relative to reference`() {
        val gains = WhiteBalance.relativeGains(5500, unity, 8000, tint = 0)
        assertTrue("expected gainRed > 1 but was ${gains.red}", gains.red > 1f)
        assertTrue("expected gainBlue < 1 but was ${gains.blue}", gains.blue < 1f)
    }

    @Test
    fun `blue gain decreases monotonically with target kelvin`() {
        val low = WhiteBalance.relativeGains(5500, unity, 3200, tint = 0).blue
        val mid = WhiteBalance.relativeGains(5500, unity, 5500, tint = 0).blue
        val high = WhiteBalance.relativeGains(5500, unity, 8000, tint = 0).blue
        assertTrue(low > mid)
        assertTrue(mid > high)
    }

    @Test
    fun `green channels stay equal to each other`() {
        for (tint in listOf(-50, -20, 0, 20, 50)) {
            val gains = WhiteBalance.relativeGains(5500, unity, 4000, tint)
            assertEquals(gains.greenEven, gains.greenOdd, 0f)
        }
    }

    @Test
    fun `positive tint raises green relative to red`() {
        val neutral = WhiteBalance.relativeGains(5500, unity, 5500, tint = 0)
        val greenish = WhiteBalance.relativeGains(5500, unity, 5500, tint = 50)
        assertTrue(
            "expected green/red higher at tint=50",
            greenish.greenEven / greenish.red > neutral.greenEven / neutral.red,
        )
        assertTrue(greenish.greenEven > neutral.greenEven)
        assertTrue(greenish.red < neutral.red)
    }

    @Test
    fun `negative tint lowers green relative to red`() {
        val neutral = WhiteBalance.relativeGains(5500, unity, 5500, tint = 0)
        val magenta = WhiteBalance.relativeGains(5500, unity, 5500, tint = -50)
        assertTrue(
            "expected green/red lower at tint=-50",
            magenta.greenEven / magenta.red < neutral.greenEven / neutral.red,
        )
        assertTrue(magenta.greenEven < neutral.greenEven)
        assertTrue(magenta.red > neutral.red)
    }

    @Test
    fun `tint multiplier is unity at zero and monotonic`() {
        assertEquals(1f, WhiteBalance.tintGainMultiplier(0), 1e-6f)
        assertTrue(WhiteBalance.tintGainMultiplier(-50) < 1f)
        assertTrue(WhiteBalance.tintGainMultiplier(50) > 1f)
        assertTrue(
            WhiteBalance.tintGainMultiplier(-50) <
                WhiteBalance.tintGainMultiplier(-20),
        )
        assertTrue(
            WhiteBalance.tintGainMultiplier(20) <
                WhiteBalance.tintGainMultiplier(50),
        )
    }

    @Test
    fun `extreme kelvin values stay within gain bounds`() {
        for (target in listOf(0, 100, 1500, 20000, 100000)) {
            val gains = WhiteBalance.relativeGains(5500, unity, target)
            assertTrue(gains.red in 0.05f..20f)
            assertTrue(gains.greenEven in 0.05f..20f)
            assertTrue(gains.blue in 0.05f..20f)
        }
    }

    @Test
    fun `temperature and tint compose without cancelling either axis`() {
        val warm = WhiteBalance.relativeGains(5500, unity, 3200, tint = 0)
        val warmGreenish = WhiteBalance.relativeGains(5500, unity, 3200, tint = 50)
        // Tint must not undo the warm-temperature blue boost.
        assertTrue(warmGreenish.blue > 1f)
        // And warm temperature must not flatten the tint axis.
        assertTrue(warmGreenish.greenEven / warmGreenish.red > warm.greenEven / warm.red)
    }

    @Test
    fun `cct from xy matches known illuminants`() {
        // CIE standard illuminant A (incandescent, ~2856 K).
        val a = WhiteBalance.cctFromXy(0.4476f, 0.4074f)
        assertTrue("expected ~2856K for illuminant A but got $a", a != null && a in 2700..3000)
        // D65 daylight (~6504 K).
        val d65 = WhiteBalance.cctFromXy(0.3127f, 0.3290f)
        assertTrue("expected ~6500K for D65 but got $d65", d65 != null && d65 in 6300..6700)
    }

    @Test
    fun `cct from xy rejects degenerate input`() {
        assertEquals(null, WhiteBalance.cctFromXy(0.3f, 0f))
        assertEquals(null, WhiteBalance.cctFromXy(Float.NaN, 0.3f))
        assertEquals(null, WhiteBalance.cctFromXy(0.3f, Float.NaN))
    }
}
