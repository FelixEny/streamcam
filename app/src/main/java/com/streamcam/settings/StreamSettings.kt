package com.streamcam.settings

/** User-facing output-resolution choice. Resolved against probed camera sizes at use time. */
enum class OutputSize { AUTO, P1080, P720 }

/**
 * Stream (encoder + RTSP server) configuration, persisted across restarts. Bitrate, fps and port
 * take effect on the next stream start (the encoder and server bind their formats at start);
 * [outputSize] takes effect immediately when not streaming (camera reopen).
 */
data class StreamSettings(
    /** Video bitrate in bits/second. Clamped to [MIN_BITRATE_BPS]..[MAX_BITRATE_BPS]. */
    val bitrateBps: Int = DEFAULT_BITRATE_BPS,
    /** Target frames per second; [FPS_AUTO] = derive from the device's AE ranges (preferred 30). */
    val fps: Int = FPS_AUTO,
    val outputSize: OutputSize = OutputSize.AUTO,
    /** RTSP TCP/UDP listen port. Clamped to [MIN_PORT]..[MAX_PORT]. */
    val port: Int = DEFAULT_PORT,
) {
    companion object {
        const val FPS_AUTO = 0
        val FPS_CHOICES = listOf(FPS_AUTO, 15, 24, 30, 60)
        const val DEFAULT_BITRATE_BPS = 8_000_000
        const val MIN_BITRATE_BPS = 2_000_000
        const val MAX_BITRATE_BPS = 20_000_000
        const val BITRATE_STEP_BPS = 500_000
        const val DEFAULT_PORT = 8554
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
