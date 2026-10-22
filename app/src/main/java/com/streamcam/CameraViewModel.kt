package com.streamcam

import android.app.Application
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import com.streamcam.camera.Camera2Constants
import com.streamcam.camera.CameraCapabilities
import com.streamcam.camera.CameraEngine
import com.streamcam.camera.CameraEngineError
import com.streamcam.camera.CameraSettings
import com.streamcam.camera.CapabilityProbe
import com.streamcam.camera.IntSize
import com.streamcam.camera.PreviewOrientation
import com.streamcam.camera.RequestPlanner
import com.streamcam.camera.StreamSizeChooser
import com.streamcam.camera.WhiteBalanceMode
import com.streamcam.common.stream.StreamState
import com.streamcam.common.stream.StreamStatus
import com.streamcam.common.stream.StreamStats
import com.streamcam.common.stream.TransportConfig
import com.streamcam.encoder.EncoderConfig
import com.streamcam.encoder.H264Encoder
import com.streamcam.transport.rtsp.RtspTransport
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LensOption(
    val cameraId: String,
    val label: String,
    val focalLength: Float?,
    val isBack: Boolean,
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Application = application
    private val cameraManager = application.getSystemService(CameraManager::class.java)
    private val probe = CapabilityProbe(cameraManager)
    private val engine = CameraEngine(application, probe)
    private val transport = RtspTransport()
    private val wifiManager = application.getSystemService(WifiManager::class.java)
    private val vmScope = CoroutineScope(SupervisorJob())

    private val capsById = mutableMapOf<String, CameraCapabilities>()
    private var lastSurface: Surface? = null

    private var encoder: H264Encoder? = null
    private var wifiLock: WifiLock? = null
    private var pendingEncoderRelease = false
    private var streaming = false
    private var metricsJob: Job? = null
    private var reportedDisplayRotationDegrees = 90

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    private val _previewSize = MutableStateFlow<IntSize?>(null)
    val previewSize: StateFlow<IntSize?> = _previewSize.asStateFlow()

    private val _previewRotation = MutableStateFlow(0)
    val previewRotation: StateFlow<Int> = _previewRotation.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    private val _lensOptions = MutableStateFlow<List<LensOption>>(emptyList())
    val lensOptions: StateFlow<List<LensOption>> = _lensOptions.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    val streamStatus: StateFlow<StreamStatus> = transport.status
    val streamStats: StateFlow<StreamStats> = transport.stats
    val streamUrl: StateFlow<String?> = transport.url

    val isStreaming: Boolean
        get() = streaming

    init {
        engine.onOpened = {
            _isCameraReady.value = true
            _error.value = null
            if (pendingEncoderRelease) {
                pendingEncoderRelease = false
                releaseEncoder()
            }
        }
        engine.onError = { err ->
            _isCameraReady.value = false
            Log.w(TAG, "camera error: $err")
            _error.value = when (err) {
                is CameraEngineError.OpenFailed -> "Camera open failed (${err.code})"
                CameraEngineError.Disconnected -> "Camera disconnected"
                CameraEngineError.SessionFailed -> "Camera session failed"
                is CameraEngineError.ProbeFailed -> "Capability probe failed: ${err.message}"
            }
            if (pendingEncoderRelease) {
                pendingEncoderRelease = false
                releaseEncoder()
            }
        }
        vmScope.launch {
            var lastState = transport.status.value.state
            transport.status.collect { status ->
                val previous = lastState
                lastState = status.state
                when (status.state) {
                    StreamState.ERROR -> if (previous != StreamState.ERROR) {
                        _error.value = "Stream failed: ${status.errorReason ?: "unknown error"}"
                        stopStreaming()
                    }

                    StreamState.LIVE -> if (previous != StreamState.LIVE) {
                        encoder?.requestSyncFrame()
                    }

                    else -> Unit
                }
            }
        }
        initializeCameras()
    }

    private fun initializeCameras() {
        val ids = probe.listCameraIds()
        Log.i(TAG, "camera ids=$ids")
        val options = buildList {
            for (id in ids) {
                val caps = runCatching { probe.probe(id) }.onFailure {
                    Log.w(TAG, "probe failed for camera $id", it)
                }.getOrNull() ?: continue
                capsById[id] = caps
                Log.i(
                    TAG,
                    "camera $id facing=${caps.lensFacing} sensorOrientation=${caps.sensorOrientation} " +
                        "focal=${caps.focalLengthsByCamera[id]} physical=${caps.physicalCameraIds} " +
                        "sizes=${caps.streamSizes.size}",
                )
                add(
                    LensOption(
                        cameraId = id,
                        label = id,
                        focalLength = caps.focalLengthsByCamera[id],
                        isBack = caps.lensFacing == Camera2Constants.LENS_FACING_BACK,
                    ),
                )
            }
        }
        val backOptions = options.filter { it.isBack }
        val mainFocal = backOptions.maxByOrNull { it.focalLength ?: 0f }?.focalLength
        val labeled = options.map { opt ->
            val ratio = if (opt.focalLength != null && mainFocal != null && mainFocal > 0f) {
                opt.focalLength / mainFocal
            } else {
                null
            }
            val label = when {
                !opt.isBack -> "Front"
                ratio == null -> opt.cameraId
                ratio < 0.85f -> String.format(java.util.Locale.US, "%.1fx", ratio)
                ratio > 1.2f -> String.format(java.util.Locale.US, "%.1fx", ratio)
                else -> "1x"
            }
            opt.copy(label = label)
        }
        _lensOptions.value = labeled
        // Default to the main back camera (longest back focal length); fall back to any back camera,
        // then to whatever is available. The front camera is never the default for a live stream.
        val defaultId = backOptions.maxByOrNull { it.focalLength ?: 0f }?.cameraId
            ?: backOptions.firstOrNull()?.cameraId
            ?: labeled.firstOrNull()?.cameraId
        Log.i(TAG, "default camera=$defaultId options=${labeled.map { it.cameraId to it.label }}")
        _selectedCameraId.value = defaultId
        defaultId?.let { id ->
            capsById[id]?.let { caps ->
                _capabilities.value = caps
                _previewSize.value = StreamSizeChooser.choosePreviewSize(caps.streamSizes)
            }
        }
    }

    fun onPreviewSurfaceReady(surface: Surface) {
        lastSurface = surface
        val id = _selectedCameraId.value ?: return
        val caps = capsById[id] ?: runCatching { probe.probe(id) }.getOrNull()
        caps?.let { capsById[id] = it }
        _capabilities.value = caps ?: _capabilities.value
        openCamera(id, surface, caps)
    }

    fun onPreviewSurfaceDestroyed() {
        lastSurface = null
    }

    /**
     * Android revokes camera access while the app is in the background, so the preview session must
     * be re-established when the activity returns to the foreground. The TextureView surface is
     * typically retained across that cycle, so no surface callback fires and the camera would stay
     * closed without this.
     */
    fun onAppResumed() {
        if (streaming || _isCameraReady.value) return
        val surface = lastSurface?.takeIf { it.isValid } ?: return
        val id = _selectedCameraId.value ?: return
        Log.i(TAG, "resume: reopening camera=$id")
        _error.value = null
        openCamera(id, surface, capsById[id])
    }

    fun selectLens(cameraId: String) {
        if (cameraId == _selectedCameraId.value) return
        if (encoder != null) return
        _selectedCameraId.value = cameraId
        capsById[cameraId]?.let { caps ->
            _capabilities.value = caps
            _previewSize.value = StreamSizeChooser.choosePreviewSize(caps.streamSizes)
        }
        val surface = lastSurface ?: return
        val caps = capsById[cameraId] ?: runCatching { probe.probe(cameraId) }.getOrNull()
        caps?.let { capsById[cameraId] = it }
        _isCameraReady.value = false
        openCamera(cameraId, surface, caps)
    }

    private fun openCamera(cameraId: String, surface: Surface, caps: CameraCapabilities?) {
        val sensor = caps?.sensorOrientation ?: 0
        val displayDegrees = currentDisplayRotationDegrees()
        val relative = PreviewOrientation.relativeRotation(sensor, displayDegrees)
        _previewRotation.value = relative
        if (caps != null && !caps.supportsManualWhiteBalance &&
            _settings.value.whiteBalanceMode == WhiteBalanceMode.MANUAL
        ) {
            // The selected camera cannot do manual WB; reset instead of showing a dead control.
            val normalized = _settings.value.copy(
                whiteBalanceMode = WhiteBalanceMode.AUTO,
                whiteBalanceLock = false,
            )
            _settings.value = normalized
            engine.apply(normalized)
        }
        Log.i(
            TAG,
            "open camera=$cameraId sensorOrientation=$sensor displayRotation=$displayDegrees " +
                "previewRotation=$relative facing=${caps?.lensFacing} " +
                "manualWb=${caps?.supportsManualWhiteBalance}",
        )
        engine.open(cameraId, surface, caps)
    }

    fun onDisplayRotationChanged(rotationDegrees: Int) {
        if (reportedDisplayRotationDegrees != rotationDegrees) {
            reportedDisplayRotationDegrees = rotationDegrees
            Log.i(TAG, "display rotation=$rotationDegrees")
        }
    }

    private fun currentDisplayRotationDegrees(): Int {
        val rotation = runCatching {
            @Suppress("DEPRECATION")
            appContext.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }.takeIf { it != 0 } ?: reportedDisplayRotationDegrees
    }

    fun update(transform: (CameraSettings) -> CameraSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        engine.apply(next)
    }

    fun startStreaming() {
        if (streaming) return
        val caps = _capabilities.value
        val size = _previewSize.value
        if (caps == null || size == null || !_isCameraReady.value) {
            _error.value = "Camera not ready"
            return
        }
        streaming = true
        acquireWifiLock()
        val targetFps = RequestPlanner.chooseTargetFps(caps.aeTargetFpsRanges)
        val fps = (targetFps?.max ?: 30).coerceIn(15, 60)
        val encoder = H264Encoder().also { this.encoder = it }
        encoder.listener = object : H264Encoder.Listener {
            override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer) {
                transport.setVideoInfo(sps, pps)
            }

            override fun onEncodedFrame(frame: ByteBuffer, info: android.media.MediaCodec.BufferInfo) {
                transport.submit(frame, info)
            }

            override fun onEncoderError(reason: String) {
                vmScope.launch {
                    _error.value = "Encoder failed: $reason"
                    stopStreaming()
                }
            }
        }
        try {
            val surface = encoder.prepare(EncoderConfig(size.width, size.height, fps))
            encoder.start()
            engine.setStreamingSurface(surface)
            transport.start(TransportConfig(STREAM_PORT, STREAM_PATH, size.width, size.height, fps))
            startMetrics()
        } catch (e: Exception) {
            Log.e(TAG, "startStreaming failed", e)
            _error.value = "Stream start failed: ${e.message}"
            stopStreaming()
        }
    }

    fun stopStreaming() {
        if (!streaming && encoder == null) return
        streaming = false
        stopMetrics()
        runCatching { transport.stop() }
        releaseWifiLock()
        val enc = encoder ?: return
        if (_isCameraReady.value) {
            pendingEncoderRelease = true
            engine.setStreamingSurface(null)
        } else {
            enc.stopAndRelease()
            encoder = null
        }
    }

    fun onAppBackgrounded() {
        if (streaming) stopStreaming()
    }

    fun dismissError() {
        _error.value = null
    }

    private fun releaseEncoder() {
        val enc = encoder ?: return
        encoder = null
        enc.stopAndRelease()
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "streamcam")
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "streamcam")
        }
        // A missing WAKE_LOCK grant or a vendor lock failure must not take the stream down.
        runCatching { lock.acquire() }
            .onFailure { Log.w(TAG, "wifi lock acquire failed: ${it.message}") }
            .onSuccess { wifiLock = lock }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.let { lock -> if (lock.isHeld) lock.release() } }
        wifiLock = null
    }

    private fun startMetrics() {
        metricsJob?.cancel()
        var lastEmitted = encoder?.emittedFrames ?: 0L
        metricsJob = vmScope.launch {
            while (streaming) {
                delay(METRICS_INTERVAL_MS)
                val stats = transport.stats.value
                val state = transport.status.value.state
                val emitted = encoder?.emittedFrames ?: 0L
                val encFps = (emitted - lastEmitted).coerceAtLeast(0L)
                lastEmitted = emitted
                Log.i(
                    TAG,
                    "METRICS state=$state enc_fps=$encFps sent_fps=${stats.sentFps} " +
                        "dropped_fps=${stats.droppedFps} kbps=${stats.kbps} " +
                        "pkts_fps=${stats.packetsPerSec} " +
                        "clients=${stats.counters.clients} sent_total=${stats.counters.sentFrames} " +
                        "dropped_total=${stats.counters.droppedFrames}",
                )
            }
        }
    }

    private fun stopMetrics() {
        metricsJob?.cancel()
        metricsJob = null
    }

    override fun onCleared() {
        vmScope.cancel()
        streaming = false
        runCatching { transport.stop() }
        releaseWifiLock()
        releaseEncoder()
        engine.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "StreamMetrics"
        private const val STREAM_PORT = 8554
        private const val STREAM_PATH = "stream"
        private const val METRICS_INTERVAL_MS = 1_000L
    }
}
