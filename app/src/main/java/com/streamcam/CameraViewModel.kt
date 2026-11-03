package com.streamcam

import android.app.Application
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import com.streamcam.camera.FocusMode
import com.streamcam.camera.FocusMapper
import com.streamcam.camera.FocusState
import com.streamcam.camera.IntSize
import com.streamcam.camera.PreviewOrientation
import com.streamcam.camera.RequestPlanner
import com.streamcam.camera.StreamSizeChooser
import com.streamcam.camera.WhiteBalanceMode
import com.streamcam.camera.AppliedWhiteBalance
import com.streamcam.common.resilience.BackoffPolicy
import com.streamcam.common.resilience.ThermalAction
import com.streamcam.common.resilience.ThermalLadder
import com.streamcam.common.resilience.ThermalLevel
import com.streamcam.common.stream.StreamState
import com.streamcam.common.stream.StreamStatus
import com.streamcam.common.stream.StreamStats
import com.streamcam.common.stream.TransportConfig
import com.streamcam.common.vmix.VmixBus
import com.streamcam.common.vmix.VmixState
import com.streamcam.common.vmix.VmixXml
import com.streamcam.encoder.EncoderConfig
import com.streamcam.encoder.H264Encoder
import com.streamcam.imageproc.ZoomMath
import com.streamcam.imageproc.ZoomRamp
import com.streamcam.settings.OutputSize
import com.streamcam.settings.SettingsRepository
import com.streamcam.settings.StreamSettings
import com.streamcam.transport.rtsp.RtspTransport
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private val deviceMetrics = DeviceMetrics(application)
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

    // Resilience (Phase 4): user intent, foreground state and retry/network bookkeeping.
    private var userWantsStream = false
    private var foregrounded = true
    private var networkWaitRestart = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var cameraRetryAttempt = 0
    private var cameraRetryJob: Job? = null
    private var thermalLadder: ThermalLadder? = null
    private val cameraBackoff = BackoffPolicy()
    private val zoomRamp = ZoomRamp()
    private var zoomRampJob: Job? = null
    private var vmixJob: Job? = null

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    private val _streamSettings = MutableStateFlow(StreamSettings())
    val streamSettings: StateFlow<StreamSettings> = _streamSettings.asStateFlow()

    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    private val _previewSize = MutableStateFlow<IntSize?>(null)
    val previewSize: StateFlow<IntSize?> = _previewSize.asStateFlow()

    private val _previewViewSize = MutableStateFlow<IntSize?>(null)
    val previewViewSize: StateFlow<IntSize?> = _previewViewSize.asStateFlow()

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

    /** Which vMix bus our RTSP input is on, or null when not streaming / vMix not configured. */
    private val _vmixBus = MutableStateFlow<VmixBus?>(null)
    val vmixBus: StateFlow<VmixBus?> = _vmixBus.asStateFlow()

    val streamStatus: StateFlow<StreamStatus> = transport.status
    val streamStats: StateFlow<StreamStats> = transport.stats
    val streamUrl: StateFlow<String?> = transport.url

    private val _focusState = MutableStateFlow(FocusState.IDLE)
    val focusState: StateFlow<FocusState> = _focusState.asStateFlow()

    val isStreaming: Boolean
        get() = streaming

    init {
        engine.onOpened = {
            _isCameraReady.value = true
            _error.value = null
            cameraRetryAttempt = 0
            cameraRetryJob?.cancel()
            cameraRetryJob = null
            if (pendingEncoderRelease) {
                pendingEncoderRelease = false
                releaseEncoder()
            }
            // A stream restart deferred while the camera or network was down can proceed now, but
            // only if Wi-Fi is actually back (the session rebuild itself also lands here).
            if (networkWaitRestart && userWantsStream && foregrounded && !streaming && isOnWifi()) {
                networkWaitRestart = false
                vmScope.launch { startStreaming() }
            }
        }
        engine.onWbResult = { applied ->
            _appliedWb.value = applied
        }
        engine.onFocusState = { state ->
            _focusState.value = state
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
            onCameraFailure(err)
        }
        registerNetworkMonitor()
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
        foregrounded = true
        cameraRetryAttempt = 0
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
                "zoomRange=${caps?.zoomRatioRange} maxZoom=${caps?.maxZoom} maxAfRegions=${caps?.maxAfRegions} " +
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

    /** Selects a fixed zoom ratio from the Zoom tab presets (no ramp). */
    fun setZoomPreset(ratio: Float) {
        update { it.copy(zoomRatio = ratio) }
    }

    /** CameraPreview reports its on-screen bounds for tap-to-focus coordinate conversion. */
    fun onPreviewViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && _previewViewSize.value != IntSize(width, height)) {
            _previewViewSize.value = IntSize(width, height)
        }
    }

    /**
     * Tap-to-focus: maps the view-space tap to a sensor region (null outside the visible content
     * or on cameras without AF regions) and starts a genuine one-shot scan. Returns false when the
     * tap must be ignored (manual focus mode, no camera). Tapping while locked re-focuses and
     * re-locks on the new subject — no manual unlock needed.
     */
    fun tapFocus(tapX: Float, tapY: Float, viewWidth: Int, viewHeight: Int): Boolean {
        // Manual mode owns the lens; never let the two fight each other.
        if (_settings.value.focusMode == FocusMode.MANUAL) return false
        val caps = _capabilities.value ?: return false
        val previewSize = _previewSize.value ?: return false
        val active = caps.activeArraySize
        if (active.width <= 0 || active.height <= 0) return false
        val region = FocusMapper.toRegion(
            tapX = tapX, tapY = tapY,
            viewWidth = viewWidth, viewHeight = viewHeight,
            bufferWidth = previewSize.width, bufferHeight = previewSize.height,
            displayRotation = reportedDisplayRotationDegrees,
            lensFacing = caps.lensFacing,
            activeArrayWidth = active.width,
            activeArrayHeight = active.height,
            maxAfRegions = caps.maxAfRegions,
            scalerCrop = engine.lastScalerCrop,
        )
        Log.i(TAG, "tapFocus tap=(${tapX.toInt()},${tapY.toInt()}) region=$region regionsSupported=${caps.maxAfRegions > 0}")
        _focusState.value = FocusState.TAPPING
        engine.tapFocus(region)
        return true
    }

    /** Dedicated unlock: releases the focus hold and restores continuous autofocus. */
    fun unlockFocus() {
        engine.cancelFocusLock()
    }

    /**
     * Starts a smooth zoom ramp from the insta360 Flow wheel (KEYCODE_ZOOM_IN/OUT; repeats keep the
     * ramp alive). Zoom moves at [ZoomRamp]'s rate instead of jumping per event and is capped at
     * [ZoomMath.MAX_ZOOM]. Returns false when there is no zoom range so the key falls through.
     */
    fun adjustZoom(direction: Int): Boolean {
        val range = _capabilities.value?.zoomRatioRange ?: return false
        if (range.isEmpty()) return false
        zoomRamp.ratePerSec = _settings.value.zoomWheelRate
            .coerceIn(ZoomRamp.MIN_RATE, ZoomRamp.MAX_RATE)
        zoomRamp.onWheel(direction, SystemClock.elapsedRealtime())
        ensureZoomRamp()
        return true
    }

    private fun ensureZoomRamp() {
        if (zoomRampJob?.isActive == true) return
        zoomRampJob = vmScope.launch {
            while (true) {
                delay(ZOOM_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                if (!zoomRamp.isActive(now)) break
                val range = _capabilities.value?.zoomRatioRange ?: break
                val next = zoomRamp.advance(
                    now,
                    _settings.value.zoomRatio,
                    range.start,
                    maxZoomFor(range),
                ) ?: continue
                applyZoomLive(next)
            }
            persistCameraSettings()
        }
    }

    /** Applies a ramp tick without persisting (the ramp persists once when it settles). */
    private fun applyZoomLive(ratio: Float) {
        val next = normalizeSettings(_settings.value.copy(zoomRatio = ratio), _capabilities.value)
        if (next == _settings.value) return
        _settings.value = next
        engine.apply(next)
    }

    private fun persistCameraSettings() {
        val snapshot = _settings.value
        vmScope.launch { runCatching { store.saveCamera(snapshot) } }
    }

    private fun maxZoomFor(range: ClosedFloatingPointRange<Float>): Float =
        minOf(range.endInclusive, ZoomMath.MAX_ZOOM)

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
        if (streaming && next.vmixHost != previous.vmixHost) startVmixMonitor()
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
            // Device range defines the real zoom span (0.5x ultrawide .. max tele), capped by the
            // app's usable ceiling (pure digital beyond it).
            out.zoomRatio.coerceIn(zoomRange.start, minOf(zoomRange.endInclusive, ZoomMath.MAX_ZOOM))
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
            zoomWheelRate = out.zoomWheelRate.coerceIn(ZoomRamp.MIN_RATE, ZoomRamp.MAX_RATE),
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
        userWantsStream = true
        networkWaitRestart = false
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
            thermalLadder = ThermalLadder(
                baseBitrateBps = streamSettings.bitrateBps,
                minBitrateBps = StreamSettings.MIN_BITRATE_BPS,
            )
            startMetrics()
            startVmixMonitor()
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

    /** User intent: stops the stream and cancels any automatic restart. */
    fun stopStreaming() {
        userWantsStream = false
        networkWaitRestart = false
        haltStreaming()
    }

    /** Stops encoder/transport without touching the user's streaming intent. */
    private fun haltStreaming() {
        if (!streaming && encoder == null) return
        streaming = false
        stopMetrics()
        stopVmixMonitor()
        thermalLadder = null
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
        foregrounded = false
        userWantsStream = false
        networkWaitRestart = false
        cancelCameraRetry()
        engine.cancelFocusLock()
        if (streaming) haltStreaming()
    }

    fun dismissError() {
        _error.value = null
    }

    // --- Phase 4 resilience ---------------------------------------------------------------

    /**
     * Watches the default network while the app runs. A LAN RTSP server is only reachable over
     * Wi-Fi, so losing Wi-Fi (or leaving it for cellular) halts the stream, and returning Wi-Fi
     * restarts it when the user still wants it.
     */
    private fun registerNetworkMonitor() {
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork = network
                vmScope.launch { handleNetworkAvailable(network) }
            }

            override fun onLost(network: Network) {
                val wasActive = activeNetwork == network
                if (wasActive) activeNetwork = null
                if (!wasActive) return
                vmScope.launch { handleNetworkLost() }
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        if (registered) {
            networkCallback = callback
            Log.i(TAG, "network monitor registered")
        } else {
            Log.w(TAG, "default network callback unavailable; network recovery disabled")
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        val caps = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!onWifi) {
            // Wi-Fi dropped and something else (cellular) became the default: LAN clients can no
            // longer reach the server, so stop cleanly but keep the user's intent.
            if (streaming) haltForNetwork("default network is not Wi-Fi; halting LAN stream")
            return
        }
        if (!networkWaitRestart || !userWantsStream || !foregrounded) return
        if (_isCameraReady.value && !streaming) {
            networkWaitRestart = false
            _error.value = null
            Log.i(TAG, "network restored; restarting stream")
            startStreaming()
        }
        // Otherwise the camera is still down; engine.onOpened resumes the deferred restart.
    }

    private fun isOnWifi(): Boolean {
        val network = activeNetwork ?: return false
        val caps = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun handleNetworkLost() {
        if (streaming) haltForNetwork("network lost; halting stream")
    }

    private fun haltForNetwork(reason: String) {
        Log.w(TAG, reason)
        networkWaitRestart = userWantsStream
        _error.value = "Wi-Fi lost — stream stopped; will restart when it returns"
        haltStreaming()
    }

    private fun onCameraFailure(err: CameraEngineError) {
        if (err is CameraEngineError.ProbeFailed) return
        if (streaming) {
            // Without the camera the encoder has no frames; stop cleanly instead of streaming
            // frozen video. The user (not an automatic loop) decides whether to restart.
            userWantsStream = false
            networkWaitRestart = false
            haltStreaming()
        }
        scheduleCameraReopen()
    }

    /**
     * Reopens the camera with exponential backoff after a transient failure (disconnect, open or
     * session failure). Attempts are bounded; the banner shows the retry count and a lens switch or
     * resume resets the counter.
     */
    private fun scheduleCameraReopen() {
        cameraRetryJob?.cancel()
        cameraRetryJob = null
        if (!foregrounded) {
            Log.i(TAG, "camera reopen skipped: app not foregrounded")
            return
        }
        val surface = lastSurface?.takeIf { it.isValid } ?: run {
            Log.i(TAG, "camera reopen skipped: no valid preview surface")
            return
        }
        val id = _selectedCameraId.value ?: run {
            Log.i(TAG, "camera reopen skipped: no selected camera")
            return
        }
        val attempt = ++cameraRetryAttempt
        val delayMs = cameraBackoff.delayMsFor(attempt)
        if (delayMs == null) {
            Log.w(TAG, "camera reopen exhausted after $cameraRetryAttempt attempts")
            _error.value = "Camera unavailable — switch lens or reopen the app"
            return
        }
        _error.value = "Camera disconnected — retrying ($attempt/${cameraBackoff.maxAttempts})"
        cameraRetryJob = vmScope.launch {
            delay(delayMs)
            if (!foregrounded || _isCameraReady.value || streaming) return@launch
            Log.i(TAG, "camera reopen attempt=$attempt camera=$id delayMs=$delayMs")
            openCamera(id, surface, capsById[id])
        }
    }

    private fun cancelCameraRetry() {
        cameraRetryJob?.cancel()
        cameraRetryJob = null
        cameraRetryAttempt = 0
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
                val device = deviceMetrics.sample()
                val cpu = String.format(Locale.US, "%.1f", device.cpuPercent)
                val battery = device.batteryTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "na"
                Log.i(
                    TAG,
                    "METRICS state=$state enc_fps=$encFps sent_fps=${stats.sentFps} " +
                        "dropped_fps=${stats.droppedFps} kbps=${stats.kbps} " +
                        "pkts_fps=${stats.packetsPerSec} " +
                        "clients=${stats.counters.clients} sent_total=${stats.counters.sentFrames} " +
                        "dropped_total=${stats.counters.droppedFrames} " +
                        "cpu_pct=$cpu thermal=${device.thermalStatus} battery_c=$battery " +
                        "wifi_rssi=${device.wifiRssi} wifi_mbps=${device.wifiLinkMbps}",
                )
                applyThermalDecision(device.thermalStatus)
            }
        }
    }

    /** Feeds one 1 Hz thermal sample to the degrade ladder and retargets the live encoder. */
    private fun applyThermalDecision(thermalStatus: Int) {
        val ladder = thermalLadder ?: return
        val enc = encoder ?: return
        val decision = ladder.onSample(ThermalLevel.fromStatus(thermalStatus))
        if (decision.action == ThermalAction.NONE) return
        val applied = enc.setBitrate(decision.bitrateBps)
        if (applied) enc.requestSyncFrame()
        Log.i(
            TAG,
            "THERMAL action=${decision.action} level=${decision.level} " +
                "bitrate=${decision.bitrateBps} applied=$applied",
        )
    }

    private fun stopMetrics() {
        metricsJob?.cancel()
        metricsJob = null
    }

    /**
     * Polls vMix's Web Controller for the bus our input is on. RTSP carries no bus information, so
     * this is the only way to tell "in preview" apart from "on air".
     */
    private fun startVmixMonitor() {
        vmixJob?.cancel()
        val host = _streamSettings.value.vmixHost.trim()
        if (host.isEmpty()) {
            _vmixBus.value = null
            return
        }
        vmixJob = vmScope.launch {
            var lastBus: VmixBus? = null
            while (isActive) {
                val state = VmixClient.fetch(host)
                val bus = state?.let(::busOf)
                if (bus != lastBus) {
                    Log.i(TAG, "vmix bus=$bus host=$host")
                    lastBus = bus
                }
                _vmixBus.value = bus
                delay(VMIX_POLL_MS)
            }
        }
    }

    private fun busOf(state: VmixState): VmixBus {
        val url = transport.url.value
        val port = _streamSettings.value.port
        val candidates = buildList {
            if (url != null) {
                add(url)
                add(url.removePrefix("rtsp://"))
            }
            add(":$port/")
            add("streamcam")
        }
        return VmixXml.busFor(state, VmixXml.matchInput(state, candidates))
    }

    private fun stopVmixMonitor() {
        vmixJob?.cancel()
        vmixJob = null
        _vmixBus.value = null
    }

    override fun onCleared() {
        runCatching { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
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
        private const val ZOOM_TICK_MS = 50L
        private const val VMIX_POLL_MS = 500L
    }
}
