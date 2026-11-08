package com.streamcam.imageproc

/**
 * Standard third-stop ISO ladder used by interchangeable-lens and pro video cameras: every step
 * is 2^(1/3) ≈ 1.26× the previous one. Kept pure so the stop generation is unit-testable; the UI
 * filters it against the camera's probed `SENSOR_INFO_SENSITIVITY_RANGE`.
 */
object IsoStops {

    /** Third-stop ISO values in ascending order, from ISO 50 up to ISO 102400. */
    val THIRD_STOPS: List<Int> = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        8000, 10000, 12800, 16000, 20000, 25600, 32000, 51200, 64000,
        80000, 102400,
    )

    /**
     * Third stops that fall inside [range]. Devices with an unusual narrow range that contains no
     * standard stop get the range start as their single usable value, so the slider never goes
     * empty (same fallback the previous full-stop list used).
     */
    fun forRange(range: IntRange): List<Int> {
        if (range.isEmpty()) return listOf(range.first.coerceAtLeast(1))
        val inside = THIRD_STOPS.filter { it in range }
        return inside.ifEmpty { listOf(range.first.coerceAtLeast(1)) }
    }
}
