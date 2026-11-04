package com.streamcam.common.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTrackerTest {

    @Test
    fun `empty tracker has no samples`() {
        val snapshot = LatencyTracker().snapshot()
        assertFalse(snapshot.hasSamples)
        assertEquals(0, snapshot.count)
        assertEquals(-1L, snapshot.p50Ms)
    }

    @Test
    fun `single sample is every percentile`() {
        val tracker = LatencyTracker()
        tracker.record(42)
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(42L, snapshot.lastMs)
        assertEquals(42L, snapshot.p50Ms)
        assertEquals(42L, snapshot.p95Ms)
        assertEquals(42L, snapshot.maxMs)
    }

    @Test
    fun `percentiles use nearest rank`() {
        val tracker = LatencyTracker()
        (1L..100L).forEach { tracker.record(it) }
        val snapshot = tracker.snapshot()
        assertEquals(100, snapshot.count)
        assertEquals(50L, snapshot.p50Ms)
        assertEquals(95L, snapshot.p95Ms)
        assertEquals(100L, snapshot.lastMs)
        assertEquals(100L, snapshot.maxMs)
    }

    @Test
    fun `window keeps only the newest capacity samples`() {
        val tracker = LatencyTracker(capacity = 3)
        listOf(10L, 20L, 30L, 40L, 50L).forEach { tracker.record(it) }
        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot.count)
        // Newest three: 30, 40, 50. Nearest-rank p50 of 3 = rank 2 = 40.
        assertEquals(40L, snapshot.p50Ms)
        assertEquals(50L, snapshot.lastMs)
        assertEquals(50L, snapshot.maxMs)
    }

    @Test
    fun `negative samples are ignored`() {
        val tracker = LatencyTracker()
        tracker.record(-1)
        tracker.record(5)
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(5L, snapshot.p50Ms)
    }

    @Test
    fun `reset clears the window`() {
        val tracker = LatencyTracker()
        tracker.record(10)
        tracker.reset()
        assertFalse(tracker.snapshot().hasSamples)
        tracker.record(20)
        assertEquals(20L, tracker.snapshot().p50Ms)
    }

    @Test
    fun `out of order samples are sorted`() {
        val tracker = LatencyTracker()
        listOf(90L, 10L, 50L).forEach { tracker.record(it) }
        val snapshot = tracker.snapshot()
        // Sorted 10, 50, 90; nearest-rank p50 of 3 = rank 2 = 50.
        assertEquals(50L, snapshot.p50Ms)
        assertEquals(90L, snapshot.maxMs)
        assertTrue(snapshot.hasSamples)
    }
}
