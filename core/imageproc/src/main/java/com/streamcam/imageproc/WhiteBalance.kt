package com.streamcam.imageproc

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

data class RggbGains(val red: Float, val greenEven: Float, val greenOdd: Float, val blue: Float)

object WhiteBalance {
    private const val MIN_KELVIN = 1500
    private const val MAX_KELVIN = 15000
    private const val MIN_GAIN = 0.1f
    private const val MAX_GAIN = 10.0f

    /**
     * Converts a correlated color temperature (kelvin) to Camera2 COLOR_CORRECTION_GAINS.
     *
     * The gains neutralize an illuminant of that temperature: gainX = green / X (linear),
     * normalized so green = 1. A warm (low-kelvin) source yields gainBlue > 1 and gainRed < 1.
     */
    fun kelvinToRggbGains(kelvin: Int): RggbGains {
        val t = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN).toDouble()
        val (r8, g8, b8) = tannerHellandRgb(t)
        val r = srgbToLinear(r8)
        val g = srgbToLinear(g8)
        val b = srgbToLinear(b8)
        val redGain = (g / max(r, 1e-4)).toFloat()
        val blueGain = (g / max(b, 1e-4)).toFloat()
        return RggbGains(
            red = redGain.coerceIn(MIN_GAIN, MAX_GAIN),
            greenEven = 1f,
            greenOdd = 1f,
            blue = blueGain.coerceIn(MIN_GAIN, MAX_GAIN),
        )
    }

    // Tanner Helland approximation of blackbody color; returns sRGB components in 0..1.
    private fun tannerHellandRgb(kelvin: Double): Triple<Double, Double, Double> {
        val temp = kelvin / 100.0
        val r: Double
        val g: Double
        val b: Double
        if (temp <= 66.0) {
            r = 255.0
            g = 99.4708025861 * ln(temp) - 161.1195681661
        } else {
            r = 329.698727446 * (temp - 60.0).pow(-0.1332047592)
            g = 288.1221695283 * (temp - 60.0).pow(-0.0755148492)
        }
        if (temp >= 66.0) {
            b = 255.0
        } else if (temp <= 19.0) {
            b = 0.0
        } else {
            b = 138.5177312231 * ln(temp - 10.0) - 305.0447927307
        }
        return Triple(
            (r / 255.0).coerceIn(0.0, 1.0),
            (g / 255.0).coerceIn(0.0, 1.0),
            (b / 255.0).coerceIn(0.0, 1.0),
        )
    }

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}
