package com.streamcam.imageproc

import kotlin.math.pow
import kotlin.math.roundToInt

object ZoomMath {
    /** App-wide zoom ceiling: beyond ~10x the Pixel is pure digital and unusable for streaming. */
    const val MAX_ZOOM = 10f

    /** Quick-select ratios in the Zoom tab; filtered per camera by [presetsFor]. */
    val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 5f)

    /** Presets that lie inside the camera's zoom range (inclusive, with float tolerance). */
    fun presetsFor(minZoom: Float, maxZoom: Float): List<Float> =
        ZOOM_PRESETS.filter { it >= minZoom - 1e-3f && it <= maxZoom + 1e-3f }

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
