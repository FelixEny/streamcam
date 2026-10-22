package com.streamcam.imageproc

import kotlin.math.pow
import kotlin.math.roundToInt

object ZoomMath {
    // Ease in-out for smooth zoom animation (driven per-frame by the UI).
    fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    fun interpolateZoom(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * easeInOutCubic(fraction)

    // Digital-zoom crop region for devices without CONTROL_ZOOM_RATIO (API < 28).
    fun cropRegionForZoom(zoomRatio: Float, activeArray: IntRect): IntRect {
        val ratio = zoomRatio.coerceAtLeast(1f)
        val cropWidth = (activeArray.width / ratio).roundToInt().coerceAtLeast(1)
        val cropHeight = (activeArray.height / ratio).roundToInt().coerceAtLeast(1)
        val left = activeArray.left + (activeArray.width - cropWidth) / 2
        val top = activeArray.top + (activeArray.height - cropHeight) / 2
        return IntRect(left, top, left + cropWidth, top + cropHeight)
    }
}
