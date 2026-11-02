package com.streamcam.common.resilience

/**
 * Delta math for process CPU sampling. Pure so the metric is testable without `/proc` or `Process`
 * APIs.
 */
object CpuUsage {

    /**
     * CPU time consumed over the window as a percent of one core: 100 means one core fully busy,
     * and values above 100 are normal on multi-core devices (200 = two cores busy).
     *
     * Returns 0 for a non-positive window or a backwards clock so a counter reset can never produce
     * a bogus spike.
     */
    fun percent(previousCpuMs: Long, previousWallMs: Long, cpuMs: Long, wallMs: Long): Float {
        val wallDelta = wallMs - previousWallMs
        if (wallDelta <= 0L) return 0f
        val cpuDelta = cpuMs - previousCpuMs
        if (cpuDelta <= 0L) return 0f
        return (cpuDelta.toDouble() / wallDelta.toDouble() * 100.0).toFloat()
    }
}
