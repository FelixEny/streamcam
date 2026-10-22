package com.streamcam.transport.rtsp

import android.media.MediaCodec
import android.os.SystemClock
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.IpType
import com.pedro.rtspserver.server.RtspServer
import com.pedro.rtspserver.server.ServerClient
import com.streamcam.common.stream.StatsSampler
import com.streamcam.common.stream.StreamCounters
import com.streamcam.common.stream.StreamEvent
import com.streamcam.common.stream.StreamReducer
import com.streamcam.common.stream.StreamStats
import com.streamcam.common.stream.StreamStatus
import com.streamcam.common.stream.StreamTransport
import com.streamcam.common.stream.TransportConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RtspTransport : StreamTransport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(StreamStatus())
    override val status = _status.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    override val stats = _stats.asStateFlow()

    private val _url = MutableStateFlow<String?>(null)
    override val url = _url.asStateFlow()

    private var server: RtspServer? = null
    private var config: TransportConfig? = null
    private var sampler = StatsSampler()
    private var sampleJob: Job? = null

    private val submittedFrames = AtomicLong(0L)
    private val submittedBytes = AtomicLong(0L)
    private val droppedAccum = AtomicLong(0L)
    private val packetsAccum = AtomicLong(0L)

    private val connectChecker = object : ConnectChecker {
        override fun onAuthError() = Unit
        override fun onAuthSuccess() = Unit
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() = Unit
        override fun onDisconnect() = Unit
        override fun onNewBitrate(bitrate: Long) = Unit
        override fun onConnectionFailed(reason: String) {
            transition(StreamEvent.Failed(reason))
        }
    }

    private val clientListener = object : ClientListener {
        override fun onClientConnected(client: ServerClient) {
            transition(StreamEvent.ClientPlaying(server?.getNumClients() ?: 1))
        }

        override fun onClientDisconnected(client: ServerClient) {
            transition(StreamEvent.ClientGone(server?.getNumClients() ?: 0))
        }

        override fun onClientNewBitrate(bitrate: Long, client: ServerClient) = Unit
    }

    override fun start(config: TransportConfig) {
        synchronized(this) {
            if (server != null) return
            transition(StreamEvent.StartRequested)
            this.config = config
            val rtsp = RtspServer(connectChecker, config.port)
            rtsp.forceIpType(IpType.IPv4)
            rtsp.setVideoCodec(VideoCodec.H264)
            rtsp.setOnlyVideo(true)
            rtsp.setLogs(true)
            rtsp.setClientListener(clientListener)
            server = rtsp
            rtsp.startServer()
            _url.value = "rtsp://${rtsp.serverIp}:${config.port}/${config.path}"
            startSampling(rtsp)
        }
    }

    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
        val rtsp = synchronized(this) { server } ?: return
        rtsp.setVideoInfo(sps.duplicate().rewind0(), pps.duplicate().rewind0(), vps?.duplicate()?.rewind0())
        transition(StreamEvent.ServerReady)
    }

    override fun submit(frame: ByteBuffer, info: MediaCodec.BufferInfo): Boolean {
        val rtsp = server ?: return false
        rtsp.sendVideo(frame, info)
        submittedFrames.incrementAndGet()
        submittedBytes.addAndGet(info.size.toLong())
        return true
    }

    override fun stop() {
        synchronized(this) {
            val rtsp = server
            transition(StreamEvent.StopRequested)
            sampleJob?.cancel()
            sampleJob = null
            if (rtsp != null) {
                runCatching { rtsp.stopServer() }
            }
            server = null
            config = null
            _url.value = null
            _stats.value = StreamStats()
            sampler = StatsSampler()
            transition(StreamEvent.Stopped)
        }
    }

    private fun startSampling(rtsp: RtspServer) {
        sampler = StatsSampler()
        _stats.value = StreamStats()
        submittedFrames.set(0L)
        submittedBytes.set(0L)
        droppedAccum.set(0L)
        packetsAccum.set(0L)
        sampleJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val counters = StreamCounters(
                    sentFrames = submittedFrames.get(),
                    droppedFrames = droppedAccum.updateAndGet { previous ->
                        maxOf(previous, rtsp.droppedVideoFrames)
                    },
                    bytesSent = submittedBytes.get(),
                    clients = rtsp.getNumClients(),
                    packetsSent = packetsAccum.updateAndGet { previous ->
                        maxOf(previous, rtsp.sentVideoFrames)
                    },
                )
                _stats.value = sampler.sample(SystemClock.elapsedRealtime(), counters)
            }
        }
    }

    @Synchronized
    private fun transition(event: StreamEvent) {
        _status.value = StreamReducer.reduce(_status.value, event)
    }

    private fun ByteBuffer.rewind0(): ByteBuffer {
        position(0)
        return this
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1_000L
    }
}
