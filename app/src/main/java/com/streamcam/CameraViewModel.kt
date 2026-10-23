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
import com.streamcam.camera.AppliedWhiteBalance
import com.streamcam.common.stream.StreamState
import com.streamcam.common.stream.StreamStatus
import com.streamcam.common.stream.StreamStats
import com.streamcam.common.stream.TransportConfig
import com.streamcam.encoder.EncoderConfig
import com.streamcam.encoder.H264Encoder
import com.streamcam.settings.OutputSize
import com.streamcam.settings.SettingsRepository
import com.streamcam.settings.StreamSettings
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
import kotlinx.coroutines.flow.first
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
    private val store = SettingsRepository(application)
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

    private val _streamSettings = MutableStateFlow(StreamSettings())
    val streamSettings: StateFlow<StreamSettings> = _streamSettings.asStateFlow()

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

    /** HAL-reported white balance (applied kelvin/tint/gains), updated throttled by the engine. */
    private val _appliedWb = MutableStateFlow<AppliedWhiteBalance?>(null)
    val appliedWb: StateFlow<AppliedWhiteBalance?> = _appliedWb.asStateFlow()

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
        engine.onWbResult = { applied ->
            _appliedWb.value = applied
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
            // Restore persisted control + stream settings. The camera only opens once the preview
            // surface is ready (user-visible latency), so this async load comfortably wins the race;
            // if it does not, apply() below still lands before any session is configured.
            val persistedCamera = runCatching { store.cameraSettings.first() }.getOrNull()
            val persistedStream = runCatching { store.streamSettings.first() }.getOrNull()
            persistedStream?.let { stream ->
                _streamSettings.value = stream
                // initializeCameras() ran with defaults; realign the preview size if the
                // persisted output-size preference picks a different one.
                _capabilities.value?.let { caps ->
                    val size = StreamSizeChooser.chooseOutputSize(caps.streamSizes, outputSizePreference())
                    if (size != null && size != _previewSize.value) reconfigureIdle()
                }
            }
            persistedCamera?.let { persisted ->
                val normalized = normalizeSettings(persisted, _capabilities.value)
                _settings.value = normalized
                engine.apply(normalized)
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
                        "sizes=${caps.streamSizes.size} " +
                        "colorCorrectionModes=${caps.colorCorrectionModes} cctRange=${caps.cctKelvinRange}",
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
                _previewSize.value = StreamSizeChooser.chooseOutputSize(caps.streamSizes, outputSizePreference())
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
            _previewSize.value = StreamSizeChooser.chooseOutputSize(caps.streamSizes, outputSizePreference())
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
        val applied = PreviewOrientation.displayCorrectionDegrees(displayDegrees)
        val relative = PreviewOrientation.relativeRotation(
            sensor,
            displayDegrees,
            caps?.lensFacing ?: Camera2Constants.LENS_FACING_BACK,
        )
        _previewRotation.value = applied
        val normalized = normalizeSettings(_settings.value, caps)
        if (normalized != _settings.value) {
            // e.g. persisted manual WB on a camera that cannot do it, or values saved on a
            // device with wider ranges — normalize instead of showing dead/broken controls.
            _settings.value = normalized
            engine.apply(normalized)
        }
        Log.i(
            TAG,
            "open camera=$cameraId sensorOrientation=$sensor displayRotation=$displayDegrees " +
                "previewRotation=$applied relative=$relative facing=${caps?.lensFacing} " +
                "manualWb=${caps?.supportsManualWhiteBalance} cctWb=${caps?.supportsCctWhiteBalance} " +
                "cctRange=${caps?.cctKelvinRange} preferredFps=${preferredFps()}",
        )
        engine.open(cameraId, surface, caps, preferredFps = preferredFps())
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
        val next = normalizeSettings(transform(_settings.value), _capabilities.value)
        _settings.value = next
        engine.apply(next)
        vmScope.launch { runCatching { store.saveCamera(next) } }
    }

    /**
     * Updates stream configuration (bitrate/fps/output size/port), persisting it. Fps and output
     * size reconfigure the idle camera immediately; bitrate and port apply on the next stream
     * start (the encoder format and server bind happen at start).
     */
    fun updateStream(transform: (StreamSettings) -> StreamSettings) {
        val previous = _streamSettings.value
        val next = transform(previous)
        _streamSettings.value = next
        vmScope.launch { runCatching { store.saveStream(next) } }
        if (streaming) return
        if (next.outputSize != previous.outputSize || next.fps != previous.fps) {
            reconfigureIdle()
        }
    }

    /**
     * Reopens the camera so the session and AE target pick up the current output size and fps
     * preference. Same guard as lens switching: never while a stream is running.
     */
    private fun reconfigureIdle() {
        if (streaming) return
        val id = _selectedCameraId.value ?: return
        val caps = capsById[id] ?: return
        val size = StreamSizeChooser.chooseOutputSize(caps.streamSizes, outputSizePreference())
        if (size != null && size != _previewSize.value) {
            _previewSize.value = size
        }
        val surface = lastSurface ?: return
        _isCameraReady.value = false
        openCamera(id, surface, caps)
    }

    /** Resolved output-size preference as a concrete size, or null for auto. */
    private fun outputSizePreference(): IntSize? = when (_streamSettings.value.outputSize) {
        OutputSize.AUTO -> null
        OutputSize.P1080 -> IntSize(1920, 1080)
        OutputSize.P720 -> IntSize(1280, 720)
    }

    /** AE fps the engine/stream should target: user preference, else device default (30). */
    private fun preferredFps(): Int = _streamSettings.value.fps.takeIf { it > 0 } ?: 30

    /**
     * Clamps persisted/live settings to what the open camera reports: unsupported manual WB
     * resets to auto, and kelvin/tint/iso/shutter/focus/zoom are coerced into probed ranges so a
     * value saved on another device can never produce an invalid request.
     */
    private fun normalizeSettings(s: CameraSettings, caps: CameraCapabilities?): CameraSettings {
        if (caps == null) return s
        var out = s
        if (!caps.supportsManualWhiteBalance && !caps.supportsCctWhiteBalance &&
            out.whiteBalanceMode == WhiteBalanceMode.MANUAL
        ) {
            out = out.copy(whiteBalanceMode = WhiteBalanceMode.AUTO, whiteBalanceLock = false)
        }
        val kelvinRange = caps.cctKelvinRange ?: 2000..10000
        val zoomRange = caps.zoomRatioRange
        val iso = if (caps.sensitivityRange.isEmpty()) out.iso
        else out.iso.coerceIn(caps.sensitivityRange.first, caps.sensitivityRange.last)
        val shutter = if (caps.exposureTimeRange.isEmpty()) out.shutterNs
        else out.shutterNs.coerceIn(caps.exposureTimeRange.first, caps.exposureTimeRange.last)
        val zoom = if (zoomRange != null) {
            out.zoomRatio.coerceIn(zoomRange.start, zoomRange.endInclusive)
        } else {
            out.zoomRatio.coerceAtLeast(1f)
        }
        val normalized = out.copy(
            kelvin = out.kelvin.coerceIn(kelvinRange.first, kelvinRange.last),
            tint = out.tint.coerceIn(-50, 50),
            iso = iso,
            shutterNs = shutter,
            focusDistanceDiopters = out.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistance),
            zoomRatio = zoom,
        )
        return if (normalized != s) normalized else s
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
        val streamSettings = _streamSettings.value
        val targetFps = RequestPlanner.chooseTargetFps(
            caps.aeTargetFpsRanges,
            desired = preferredFps(),
        )
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
            val surface = encoder.prepare(
                EncoderConfig(
                    width = size.width,
                    height = size.height,
                    fps = fps,
                    bitrateBps = streamSettings.bitrateBps,
                ),
            )
            encoder.start()
            engine.setStreamingSurface(surface)
            transport.start(
                TransportConfig(
                    port = streamSettings.port,
                    path = STREAM_PATH,
                    width = size.width,
                    height = size.height,
                    fps = fps,
                ),
            )
            startMetrics()
            Log.i(
                TAG,
                "stream started ${size.width}x${size.height}@$fps " +
                    "bitrate=${streamSettings.bitrateBps} port=${streamSettings.port}",
            )
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
        private const val STREAM_PATH = "stream"
        private const val METRICS_INTERVAL_MS = 1_000L
    }
}
