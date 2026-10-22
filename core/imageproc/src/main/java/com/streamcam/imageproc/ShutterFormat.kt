package com.streamcam.imageproc

import java.util.Locale
import kotlin.math.roundToInt

object ShutterFormat {
    fun format(shutterNs: Long): String {
        if (shutterNs <= 0L) return "—"
        val seconds = shutterNs / 1_000_000_000.0
        return if (seconds >= 1.0) {
            val value = if (seconds >= 10.0) {
                String.format(Locale.US, "%.0f", seconds)
            } else {
                String.format(Locale.US, "%.1f", seconds)
            }
            if (value.endsWith(".0")) value.dropLast(2) + "s" else value + "s"
        } else {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            "1/$denominator"
        }
    }
}
