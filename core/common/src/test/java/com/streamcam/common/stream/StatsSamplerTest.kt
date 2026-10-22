package com.streamcam.common.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsSamplerTest {

    @Test
    fun `first sample reports counters with zero rates`() {
        val sampler = StatsSampler()
        val stats = sampler.sample(
            nowMs = 1_000L,
            counters = StreamCounters(sentFrames = 42, droppedFrames = 1, bytesSent = 1234, clients = 1),
        )
        assertEquals(42L, stats.counters.sentFrames)
        assertEquals(1L, stats.counters.droppedFrames)
        assertEquals(1234L, stats.counters.bytesSent)
        assertEquals(1, stats.counters.clients)
        assertEquals(0f, stats.sentFps)
        assertEquals(0f, stats.droppedFps)
        assertEquals(0f, stats.kbps)
    }

    @Test
    fun `computes rates over one second window`() {
        val sampler = StatsSampler()
        sampler.sample(0L, StreamCounters(sentFrames = 100, bytesSent = 100_000))
        val stats = sampler.sample(
            1_000L,
            StreamCounters(sentFrames = 130, droppedFrames = 2, bytesSent = 148_000),
        )
        assertEquals(30f, stats.sentFps)
        assertEquals(2f, stats.droppedFps)
        assertEquals(384f, stats.kbps) // 48_000 bytes * 8 / 1000
    }

    @Test
    fun `packet rate is tracked separately from frame rate`() {
        val sampler = StatsSampler()
        sampler.sample(0L, StreamCounters(sentFrames = 100, packetsSent = 5_000))
        val stats = sampler.sample(
            1_000L,
            StreamCounters(sentFrames = 130, bytesSent = 1_000_000, packetsSent = 5_700),
        )
        assertEquals(30f, stats.sentFps)
        assertEquals(700f, stats.packetsPerSec)
        assertEquals(8000f, stats.kbps)
    }

    @Test
    fun `computes rates over fractional window`() {
        val sampler = StatsSampler()
        sampler.sample(0L, StreamCounters(sentFrames = 0))
        val stats = sampler.sample(500L, StreamCounters(sentFrames = 15))
        assertEquals(30f, stats.sentFps)
    }

    @Test
    fun `counter reset clamps deltas to zero`() {
        val sampler = StatsSampler()
        sampler.sample(0L, StreamCounters(sentFrames = 1000, droppedFrames = 5, bytesSent = 999_999))
        val stats = sampler.sample(
            1_000L,
            StreamCounters(sentFrames = 0, droppedFrames = 0, bytesSent = 0),
        )
        assertEquals(0f, stats.sentFps)
        assertEquals(0f, stats.droppedFps)
        assertEquals(0f, stats.kbps)
        assertEquals(0L, stats.counters.sentFrames)
    }

    @Test
    fun `zero delta window reports zero rates but keeps counters`() {
        val sampler = StatsSampler()
        sampler.sample(0L, StreamCounters(sentFrames = 10, clients = 1))
        val stats = sampler.sample(1_000L, StreamCounters(sentFrames = 10, clients = 0))
        assertEquals(0f, stats.sentFps)
        assertEquals(0, stats.counters.clients)
    }

    @Test
    fun `non-monotonic timestamps do not divide by zero`() {
        val sampler = StatsSampler()
        sampler.sample(5_000L, StreamCounters(sentFrames = 10))
        val stats = sampler.sample(1_000L, StreamCounters(sentFrames = 20))
        assertEquals(0f, stats.sentFps)
        assertEquals(20L, stats.counters.sentFrames)
    }

    @Test
    fun `reset forgets baseline`() {
        val sampler = StatsSampler()
        sampler.sample(0L, StreamCounters(sentFrames = 100))
        sampler.reset()
        val stats = sampler.sample(1_000L, StreamCounters(sentFrames = 300))
        assertEquals(0f, stats.sentFps)
        assertEquals(300L, stats.counters.sentFrames)
    }
}
