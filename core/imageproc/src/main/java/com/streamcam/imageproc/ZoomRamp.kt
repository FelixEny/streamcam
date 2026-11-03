package com.streamcam.imageproc

import kotlin.math.exp

/**
 * Rate-based zoom for the gimbal wheel: while key events keep arriving the ratio ramps
 * exponentially at [ratePerSec] (natural-log units per second), instead of jumping per event.
 *
 * With the default 0.8, 1x→10x takes ln(10)/0.8 ≈ 2.9 s and the same back down. The ramp stops
 * within [eventHoldMs] after the last wheel event, so a short flick moves only a little.
 *
 * Pure and time-injected (`nowMs`), so the feel is deterministic and unit-tested.
 */
class ZoomRamp(
    val ratePerSec: Float = DEFAULT_RATE,
    val eventHoldMs: Long = DEFAULT_HOLD_MS,
) {
    private var direction = 0
    private var lastEventMs = Long.MIN_VALUE
    private var lastTickMs = Long.MIN_VALUE

    /** Called for every wheel key event; [direction] is +1 (in) or -1 (out). */
    fun onWheel(direction: Int, nowMs: Long) {
        this.direction = direction.coerceIn(-1, 1)
        lastEventMs = nowMs
    }

    fun isActive(nowMs: Long): Boolean =
        direction != 0 && lastEventMs != Long.MIN_VALUE && nowMs - lastEventMs <= eventHoldMs

    /** Next ratio for this tick, or null when idle/unchanged (clamped to the range). */
    fun advance(nowMs: Long, current: Float, minZoom: Float, maxZoom: Float): Float? {
        if (!isActive(nowMs)) {
            direction = 0
            lastTickMs = nowMs
            return null
        }
        if (lastTickMs == Long.MIN_VALUE) {
            lastTickMs = nowMs
            return null
        }
        val dtSec = ((nowMs - lastTickMs).coerceIn(0L, MAX_TICK_MS)).toFloat() / 1000f
        lastTickMs = nowMs
        if (dtSec <= 0f) return null
        val next = (current * exp(ratePerSec * direction * dtSec)).coerceIn(minZoom, maxZoom)
        return next.takeIf { it != current }
    }

    fun reset() {
        direction = 0
        lastEventMs = Long.MIN_VALUE
        lastTickMs = Long.MIN_VALUE
    }

    companion object {
        /** ≈3 s from 1x to 10x. */
        const val DEFAULT_RATE = 0.8f
        const val DEFAULT_HOLD_MS = 120L
        /** Caps a delayed tick so a stalled coroutine cannot jump the zoom instantly. */
        const val MAX_TICK_MS = 100L
    }
}
