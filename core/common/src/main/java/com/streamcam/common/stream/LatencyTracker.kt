package com.streamcam.common.stream

/**
 * Rolling window of pipeline latency samples in milliseconds.
 *
 * The encoder records one sample per output frame (capture timestamp → encoded frame available);
 * the 1 Hz metrics loop reads percentiles. Thread-safe: the drain thread writes while the metrics
 * coroutine reads. [capacity] bounds memory; older samples fall out of the window.
 */
class LatencyTracker(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    data class Snapshot(
        val count: Int = 0,
        val lastMs: Long = -1L,
        val p50Ms: Long = -1L,
        val p95Ms: Long = -1L,
        val maxMs: Long = -1L,
    ) {
        val hasSamples: Boolean get() = count > 0
    }

    private val samples = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var lastMs = -1L

    fun reset() {
        synchronized(this) {
            count = 0
            next = 0
            lastMs = -1L
        }
    }

    /** Records one sample. Negative values are ignored (invalid/unknown timestamps). */
    fun record(ms: Long) {
        if (ms < 0) return
        synchronized(this) {
            samples[next] = ms
            next = (next + 1) % capacity
            if (count < capacity) count++
            lastMs = ms
        }
    }

    fun snapshot(): Snapshot = synchronized(this) {
        if (count == 0) return Snapshot()
        val sorted = LongArray(count) { samples[it] }
        sorted.sort()
        Snapshot(
            count = count,
            lastMs = lastMs,
            p50Ms = percentile(sorted, 50),
            p95Ms = percentile(sorted, 95),
            maxMs = sorted[sorted.size - 1],
        )
    }

    /** Nearest-rank percentile: p95 of a single sample is that sample. */
    private fun percentile(sorted: LongArray, percent: Int): Long {
        val rank = kotlin.math.ceil(percent / 100.0 * sorted.size).toInt()
            .coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    companion object {
        const val DEFAULT_CAPACITY = 600
    }
}
