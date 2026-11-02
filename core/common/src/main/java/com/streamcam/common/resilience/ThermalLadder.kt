package com.streamcam.common.resilience

/**
 * Mirrors `PowerManager` thermal status levels (API 29+). Kept as a plain enum so the degrade
 * ladder has no Android dependency and is unit-testable off-device.
 */
enum class ThermalLevel(val status: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6),
    ;

    companion object {
        /** Maps a platform status int (including out-of-range values) to a level. */
        fun fromStatus(status: Int): ThermalLevel =
            entries.firstOrNull { it.status == status }
                ?: if (status > SHUTDOWN.status) SHUTDOWN else NONE
    }
}

enum class ThermalAction { NONE, REDUCE, RESTORE }

data class ThermalDecision(
    val action: ThermalAction,
    val bitrateBps: Int,
    val level: ThermalLevel,
)

/**
 * Bitrate degrade ladder driven by the platform thermal status.
 *
 * - [ThermalLevel.MODERATE] drops to [moderateFactor] of the base bitrate immediately.
 * - [ThermalLevel.SEVERE] and above drop to [severeFactor] immediately.
 * - Restoring to the base bitrate requires [restoreAfterSamples] consecutive samples at
 *   [ThermalLevel.LIGHT] or below.
 *
 * The ladder only moves down while hot and only moves up when cool, so a flapping thermal status
 * cannot oscillate the encoder. Never raises the bitrate inside a hot level (e.g. SEVERE → MODERATE
 * stays at the severe factor).
 */
class ThermalLadder(
    private val baseBitrateBps: Int,
    private val minBitrateBps: Int = 0,
    private val moderateFactor: Float = 0.75f,
    private val severeFactor: Float = 0.5f,
    private val restoreAfterSamples: Int = 5,
) {
    init {
        require(baseBitrateBps > 0) { "baseBitrateBps must be positive" }
        require(minBitrateBps in 0..baseBitrateBps) { "minBitrateBps must be in 0..baseBitrateBps" }
        require(moderateFactor in 0f..1f) { "moderateFactor must be in 0..1" }
        require(severeFactor in 0f..moderateFactor) { "severeFactor must be in 0..moderateFactor" }
        require(restoreAfterSamples >= 1) { "restoreAfterSamples must be >= 1" }
    }

    var currentBitrateBps: Int = clamp(baseBitrateBps)
        private set

    private var coolSamples = 0

    /** Feeds one 1 Hz status sample and reports the action the caller should apply. */
    fun onSample(level: ThermalLevel): ThermalDecision {
        val sev = ThermalLevel.SEVERE.status
        val hot = level.status >= ThermalLevel.MODERATE.status
        val target = when {
            level.status >= sev -> clamp((baseBitrateBps * severeFactor).toInt())
            hot -> clamp((baseBitrateBps * moderateFactor).toInt())
            else -> clamp(baseBitrateBps)
        }
        if (hot) {
            coolSamples = 0
            // Never raise while hot; only degrade further.
            if (target < currentBitrateBps) {
                currentBitrateBps = target
                return ThermalDecision(ThermalAction.REDUCE, currentBitrateBps, level)
            }
            return ThermalDecision(ThermalAction.NONE, currentBitrateBps, level)
        }
        coolSamples++
        if (coolSamples >= restoreAfterSamples && target > currentBitrateBps) {
            currentBitrateBps = target
            return ThermalDecision(ThermalAction.RESTORE, currentBitrateBps, level)
        }
        return ThermalDecision(ThermalAction.NONE, currentBitrateBps, level)
    }

    /** Forgets the cool-down progress (e.g. stream restart). */
    fun reset() {
        currentBitrateBps = clamp(baseBitrateBps)
        coolSamples = 0
    }

    private fun clamp(value: Int): Int = value.coerceIn(minBitrateBps, baseBitrateBps)
}
