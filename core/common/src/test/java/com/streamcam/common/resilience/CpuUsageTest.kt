package com.streamcam.common.resilience

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuUsageTest {

    @Test
    fun `one core busy over one second is one hundred percent`() {
        assertEquals(100f, CpuUsage.percent(0L, 0L, 1_000L, 1_000L))
    }

    @Test
    fun `half a core busy is fifty percent`() {
        assertEquals(50f, CpuUsage.percent(1_000L, 5_000L, 1_500L, 6_000L))
    }

    @Test
    fun `multi core usage can exceed one hundred percent`() {
        assertEquals(200f, CpuUsage.percent(0L, 0L, 2_000L, 1_000L))
    }

    @Test
    fun `zero width window reports zero`() {
        assertEquals(0f, CpuUsage.percent(100L, 1_000L, 200L, 1_000L))
    }

    @Test
    fun `backwards clock reports zero instead of a spike`() {
        assertEquals(0f, CpuUsage.percent(500L, 5_000L, 400L, 4_000L))
        assertEquals(0f, CpuUsage.percent(500L, 5_000L, 900L, 4_000L))
    }
}
