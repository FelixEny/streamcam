package com.streamcam.common.stream

import android.media.MediaCodec
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.StateFlow

enum class StreamState {
    IDLE,
    STARTING,
    RUNNING,
    LIVE,
    STOPPING,
    ERROR,
}

data class StreamStatus(
    val state: StreamState = StreamState.IDLE,
    val errorReason: String? = null,
)

sealed class StreamEvent {
    data object StartRequested : StreamEvent()
    data object ServerReady : StreamEvent()
    data class ClientPlaying(val clients: Int) : StreamEvent()
    data class ClientGone(val remaining: Int) : StreamEvent()
    data class Failed(val reason: String) : StreamEvent()
    data object StopRequested : StreamEvent()
    data object Stopped : StreamEvent()
}

object StreamReducer {
    fun reduce(current: StreamStatus, event: StreamEvent): StreamStatus = when (event) {
        StreamEvent.StartRequested -> if (
            current.state == StreamState.IDLE || current.state == StreamState.ERROR
        ) {
            StreamStatus(StreamState.STARTING)
        } else {
            current
        }

        StreamEvent.ServerReady -> if (current.state == StreamState.STARTING) {
            StreamStatus(StreamState.RUNNING)
        } else {
            current
        }

        is StreamEvent.ClientPlaying -> {
            val active = current.state == StreamState.STARTING ||
                current.state == StreamState.RUNNING ||
                current.state == StreamState.LIVE
            if (event.clients > 0 && active) StreamStatus(StreamState.LIVE) else current
        }

        is StreamEvent.ClientGone -> when {
            current.state != StreamState.LIVE -> current
            event.remaining > 0 -> current
            else -> StreamStatus(StreamState.RUNNING)
        }

        is StreamEvent.Failed -> when (current.state) {
            StreamState.STARTING, StreamState.RUNNING, StreamState.LIVE ->
                StreamStatus(StreamState.ERROR, event.reason)

            else -> current
        }

        StreamEvent.StopRequested -> when (current.state) {
            StreamState.STARTING, StreamState.RUNNING, StreamState.LIVE ->
                StreamStatus(StreamState.STOPPING)

            else -> current
        }

        StreamEvent.Stopped -> StreamStatus(StreamState.IDLE)
    }
}

data class TransportConfig(
    val port: Int,
    val path: String,
    val width: Int,
    val height: Int,
    val fps: Int,
)

data class StreamCounters(
    val sentFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val bytesSent: Long = 0L,
    val clients: Int = 0,
    val packetsSent: Long = 0L,
)

data class StreamStats(
    val counters: StreamCounters = StreamCounters(),
    val sentFps: Float = 0f,
    val droppedFps: Float = 0f,
    val kbps: Float = 0f,
    val packetsPerSec: Float = 0f,
)

interface StreamTransport {
    val status: StateFlow<StreamStatus>
    val stats: StateFlow<StreamStats>
    val url: StateFlow<String?>

    fun start(config: TransportConfig)
    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer? = null)
    fun submit(frame: ByteBuffer, info: MediaCodec.BufferInfo): Boolean
    fun stop()
}
