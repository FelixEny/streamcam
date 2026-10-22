package com.streamcam.common.stream

class StatsSampler {

    private var previous: StreamCounters? = null
    private var previousAtMs: Long = 0L

    fun reset() {
        previous = null
        previousAtMs = 0L
    }

    fun sample(nowMs: Long, counters: StreamCounters): StreamStats {
        val prev = previous
        val prevAt = previousAtMs
        previous = counters
        previousAtMs = nowMs
        if (prev == null || nowMs <= prevAt) {
            return StreamStats(counters = counters)
        }
        val elapsedSec = (nowMs - prevAt) / 1000.0
        val sentDelta = (counters.sentFrames - prev.sentFrames).coerceAtLeast(0L)
        val droppedDelta = (counters.droppedFrames - prev.droppedFrames).coerceAtLeast(0L)
        val bytesDelta = (counters.bytesSent - prev.bytesSent).coerceAtLeast(0L)
        val packetsDelta = (counters.packetsSent - prev.packetsSent).coerceAtLeast(0L)
        return StreamStats(
            counters = counters,
            sentFps = (sentDelta / elapsedSec).toFloat(),
            droppedFps = (droppedDelta / elapsedSec).toFloat(),
            kbps = (bytesDelta * 8.0 / 1000.0 / elapsedSec).toFloat(),
            packetsPerSec = (packetsDelta / elapsedSec).toFloat(),
        )
    }
}
