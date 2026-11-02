package com.streamcam.common.resilience

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalLadderTest {

    private val base = 8_000_000

    @Test
    fun `moderate heat reduces to the moderate factor`() {
        val ladder = ThermalLadder(base)
        val decision = ladder.onSample(ThermalLevel.MODERATE)
        assertEquals(ThermalAction.REDUCE, decision.action)
        assertEquals(6_000_000, decision.bitrateBps)
        assertEquals(6_000_000, ladder.currentBitrateBps)
    }

    @Test
    fun `severe heat reduces straight to the severe factor`() {
        val ladder = ThermalLadder(base)
        ladder.onSample(ThermalLevel.MODERATE)
        val decision = ladder.onSample(ThermalLevel.SEVERE)
        assertEquals(ThermalAction.REDUCE, decision.action)
        assertEquals(4_000_000, decision.bitrateBps)
    }

    @Test
    fun `bitrate is never raised while still hot`() {
        val ladder = ThermalLadder(base)
        ladder.onSample(ThermalLevel.SEVERE)
        val decision = ladder.onSample(ThermalLevel.MODERATE)
        assertEquals(ThermalAction.NONE, decision.action)
        assertEquals(4_000_000, ladder.currentBitrateBps)
    }

    @Test
    fun `restores only after the configured cool samples`() {
        val ladder = ThermalLadder(base, restoreAfterSamples = 3)
        ladder.onSample(ThermalLevel.SEVERE)
        assertEquals(ThermalAction.NONE, ladder.onSample(ThermalLevel.LIGHT).action)
        assertEquals(ThermalAction.NONE, ladder.onSample(ThermalLevel.NONE).action)
        val restored = ladder.onSample(ThermalLevel.LIGHT)
        assertEquals(ThermalAction.RESTORE, restored.action)
        assertEquals(base, restored.bitrateBps)
    }

    @Test
    fun `a hot sample resets the cool down progress`() {
        val ladder = ThermalLadder(base, restoreAfterSamples = 2)
        ladder.onSample(ThermalLevel.MODERATE)
        ladder.onSample(ThermalLevel.NONE)
        ladder.onSample(ThermalLevel.SEVERE) // resets progress and degrades further
        assertEquals(4_000_000, ladder.currentBitrateBps)
        assertEquals(ThermalAction.NONE, ladder.onSample(ThermalLevel.NONE).action)
        assertEquals(ThermalAction.RESTORE, ladder.onSample(ThermalLevel.NONE).action)
    }

    @Test
    fun `no action when already at base and cool`() {
        val ladder = ThermalLadder(base)
        val decision = ladder.onSample(ThermalLevel.NONE)
        assertEquals(ThermalAction.NONE, decision.action)
        assertEquals(base, decision.bitrateBps)
    }

    @Test
    fun `degrade respects the minimum bitrate`() {
        val ladder = ThermalLadder(baseBitrateBps = 3_000_000, minBitrateBps = 2_000_000)
        val decision = ladder.onSample(ThermalLevel.SEVERE)
        assertEquals(2_000_000, decision.bitrateBps)
    }

    @Test
    fun `reset returns to base`() {
        val ladder = ThermalLadder(base)
        ladder.onSample(ThermalLevel.CRITICAL)
        ladder.reset()
        assertEquals(base, ladder.currentBitrateBps)
        assertEquals(ThermalAction.NONE, ladder.onSample(ThermalLevel.NONE).action)
    }

    @Test
    fun `status values map to levels with out of range clamping`() {
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromStatus(0))
        assertEquals(ThermalLevel.MODERATE, ThermalLevel.fromStatus(2))
        assertEquals(ThermalLevel.SHUTDOWN, ThermalLevel.fromStatus(6))
        assertEquals(ThermalLevel.SHUTDOWN, ThermalLevel.fromStatus(99))
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromStatus(-1))
    }
}
