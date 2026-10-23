package com.streamcam.imageproc

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class RggbGains(val red: Float, val greenEven: Float, val greenOdd: Float, val blue: Float)

/**
 * Camera-aware manual white-balance math.
 *
 * ## Why not an absolute formula
 *
 * A display-space blackbody approximation (the Tanner Helland sRGB curve) is not a substitute for a
 * sensor calibration. Every sensor has its own spectral sensitivity, so absolute gains computed from
 * a generic curve produce uncalibrated, extreme casts — e.g. R=0.51 / B=2.11 at 3400 K. Those
 * numbers are not meaningful for any particular sensor.
 *
 * ## Relative model
 *
 * This object only computes gains *relative to a reference illuminant the device itself reported*.
 * The reference gains come from `COLOR_CORRECTION_GAINS` on a real capture result, and the reference
 * temperature is the scene CCT derived from `SENSOR_NEUTRAL_COLOR_POINT` (via [cctFromXy]) or a
 * previously requested CCT.
 * Given a reference pair `(referenceKelvin, referenceGains)`, the gains for a target temperature are:
 *
 *     gains(target) = referenceGains * chroma(referenceKelvin) / chroma(targetKelvin)
 *
 * where `chroma(k)` is the Planckian (blackbody) chromaticity at `k` mapped into linear sRGB.
 * The ratio is a *correlated* correction: the blackbody curve only describes how the illuminant
 * shifts with temperature, and the unknown absolute sensor calibration cancels because the same
 * sensor response appears in numerator and denominator. At target == reference the ratio is exactly
 * 1 and the device's own gains are returned unchanged — correct by construction.
 *
 * ## Tint
 *
 * [tint] is the green/magenta axis in Android CCT units, -50..+50, where +50 is greener and -50 is
 * more magenta. It is applied as a green-vs-red/blue imbalance on top of the temperature correction,
 * independent of the target kelvin, so the two controls stay separable.
 */
object WhiteBalance {
    const val MIN_KELVIN = 1500
    const val MAX_KELVIN = 15000
    const val MIN_TINT = -50
    const val MAX_TINT = 50
    private const val MIN_GAIN = 0.05f
    private const val MAX_GAIN = 20.0f
    private const val TINT_EXTREMUM = 0.12f

    /**
     * Computes RGGB gains for [targetKelvin] and [tint] relative to a device-reported reference.
     *
     * @param referenceKelvin scene CCT the reference gains correspond to (Kelvin)
     * @param referenceGains device-reported `COLOR_CORRECTION_GAINS` at [referenceKelvin]
     * @param targetKelvin desired white point (Kelvin), clamped to [MIN_KELVIN]..[MAX_KELVIN]
     * @param tint green/magenta bias, clamped to [MIN_TINT]..[MAX_TINT]
     */
    fun relativeGains(
        referenceKelvin: Int,
        referenceGains: RggbGains,
        targetKelvin: Int,
        tint: Int = 0,
    ): RggbGains {
        val refK = referenceKelvin.coerceIn(MIN_KELVIN, MAX_KELVIN).toDouble()
        val tgtK = targetKelvin.coerceIn(MIN_KELVIN, MAX_KELVIN).toDouble()
        val tintClamped = tint.coerceIn(MIN_TINT, MAX_TINT)

        val (dr, dg, db) = planckianRatio(refK, tgtK)
        val greenMul = tintGainMultiplier(tintClamped)
        val inverseGreenMul = 1f / greenMul

        return RggbGains(
            red = clampGain(referenceGains.red * dr * inverseGreenMul),
            greenEven = clampGain(referenceGains.greenEven * dg * greenMul),
            greenOdd = clampGain(referenceGains.greenOdd * dg * greenMul),
            blue = clampGain(referenceGains.blue * db * inverseGreenMul),
        )
    }

    /**
     * Green-channel multiplier for a tint value. +50 raises green (image goes greener), -50 lowers
     * green (image goes magenta). Linear in tint with a 12% swing at full deflection — a visible
     * but bounded bias. Red and blue receive the reciprocal so overall exposure is roughly held.
     */
    fun tintGainMultiplier(tint: Int): Float {
        val t = tint.coerceIn(MIN_TINT, MAX_TINT).toFloat() / MAX_TINT.toFloat()
        return 1f + t * TINT_EXTREMUM
    }

    /** Ratio of Planckian chromaticities `chroma(reference)/chroma(target)` per linear-sRGB channel. */
    private fun planckianRatio(refK: Double, tgtK: Double): Triple<Float, Float, Float> {
        val ref = planckianLinearRgb(refK)
        val tgt = planckianLinearRgb(tgtK)
        return Triple(
            (ref.first / tgt.first).toFloat(),
            (ref.second / tgt.second).toFloat(),
            (ref.third / tgt.third).toFloat(),
        )
    }

    /**
     * Planckian (blackbody) radiator chromaticity at [kelvin] as linear sRGB (green normalized
     * implicitly by the caller's ratio). Used only for ratios between two temperatures, never as an
     * absolute colour — the sensor's true calibration lives in the reference gains.
     */
    private fun planckianLinearRgb(kelvin: Double): Triple<Double, Double, Double> {
        val (x, y) = planckianXy(kelvin)
        val safeY = y.coerceAtLeast(1e-4)
        // xyY (Y=1) -> XYZ.
        val bigX = x / safeY
        val bigY = 1.0
        val bigZ = (1.0 - x - y) / safeY
        // XYZ -> linear sRGB (Rec.709 / sRGB primaries, D65 white).
        val r = 3.2406 * bigX - 1.5372 * bigY - 0.4986 * bigZ
        val g = -0.9689 * bigX + 1.8758 * bigY + 0.0415 * bigZ
        val b = 0.0557 * bigX - 0.2040 * bigY + 1.0570 * bigZ
        // A blackbody is never complementary; clamp guards extrapolation noise at the extremes.
        return Triple(r.coerceAtLeast(1e-4), g.coerceAtLeast(1e-4), b.coerceAtLeast(1e-4))
    }

    /**
     * Planckian locus chromaticity (x, y) for a blackbody at [kelvin] via McCamy's cubic
     * approximation (valid ~1667 K..25000 K, accurate to ~2e-4 in x/y — far tighter than needed
     * for a temperature *ratio*).
     */
    private fun planckianXy(kelvin: Double): Pair<Double, Double> {
        val t = kelvin.coerceIn(MIN_KELVIN.toDouble(), MAX_KELVIN.toDouble())
        val t2 = t * t
        val t3 = t2 * t
        val x = -4.6070e9 / t3 + 2.9678e6 / t2 + 0.09986e3 / t + 0.244063
        val y = -3.0 * x * x + 2.870 * x - 0.275
        return x to y
    }

    /**
     * Estimates CCT in Kelvin from a CIE 1931 xy chromaticity via McCamy's inverse approximation.
     * Used to convert `SENSOR_NEUTRAL_COLOR_POINT` (the HAL's estimated scene white point) into a
     * reference kelvin for the relative manual-WB fallback. Returns null for degenerate input.
     */
    fun cctFromXy(x: Float, y: Float): Int? {
        if (!x.isFinite() || !y.isFinite() || y <= 0f) return null
        val denominator = 0.1858f - y
        if (abs(denominator) < 1e-6f) return null
        val n = (x - 0.3320f) / denominator
        val t = 449.0 * n.toDouble().pow(3) +
            3525.0 * n.toDouble().pow(2) +
            6823.3 * n.toDouble() +
            5520.33
        if (!t.isFinite()) return null
        return t.roundToInt().coerceIn(MIN_KELVIN, MAX_KELVIN)
    }

    private fun clampGain(v: Float): Float = v.coerceIn(MIN_GAIN, MAX_GAIN)

    /** True when two gain vectors agree within [epsilon]. Used by tests and diagnostic logs. */
    fun nearlyEqual(a: RggbGains, b: RggbGains, epsilon: Float = 1e-3f): Boolean =
        abs(a.red - b.red) < epsilon &&
            abs(a.greenEven - b.greenEven) < epsilon &&
            abs(a.greenOdd - b.greenOdd) < epsilon &&
            abs(a.blue - b.blue) < epsilon
}
