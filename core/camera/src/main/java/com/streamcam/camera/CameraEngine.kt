package com.streamcam.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.streamcam.camera.FocusIntent
import com.streamcam.imageproc.IntRect
import com.streamcam.imageproc.RggbGains

/**
 * Owns the Camera2 device + session lifecycle on a dedicated [HandlerThread].
 *
 * - [open] probes (or accepts pre-probed) capabilities, opens the camera, creates a session
 *   with the provided preview [Surface], and starts a repeating request.
 * - [apply] rebuilds the repeating request from the current [CameraSettings] (no session rebuild).
 * - [setStreamingSurface] attaches/detaches an extra (encoder) surface, rebuilding the session
 *   so the same repeating request drives preview and encoder targets.
 * - A generation counter guards against stale open callbacks when switching cameras quickly.
 */
class CameraEngine(
    context: Context,
    private val probe: CapabilityProbe,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var streamingSurface: Surface? = null
    private var openGeneration: Int = 0

    var capabilities: CameraCapabilities? = null
        private set
    var settings: CameraSettings = CameraSettings()
        private set
    var focusIntent: FocusIntent = FocusIntent.IDLE
        private set

    /** Latest `SCALER_CROP_REGION` (zoom-aware) for tap-to-focus mapping. */
    @Volatile
    var lastScalerCrop: IntRect? = null
        private set
    private var targetFps: FpsRange? = null

    /**
     * Colour calibration reported by the HAL while auto white balance is running. Learned
     * continuously from capture results; frozen into a [ManualWbReference] when the user switches
     * to manual WB on devices without native CCT support.
     */
    private data class AutoWbSnapshot(
        val colorTransform: List<Int>,
        val gains: RggbGains,
        val sceneCct: Int?,
    )

    private var latestAutoWb: AutoWbSnapshot? = null
    private var manualWbReference: ManualWbReference? = null
    private var lastWbLogAtMs: Long = 0L
    private var lastLoggedApplied: AppliedWhiteBalance? = null
    private var lastActivePhysicalId: String? = null
    private var lastRequestLogAtMs: Long = 0L
    /** One-shot tap diagnostics: requested/returned region logging, reset at every boundary. */
    private val tapDiagnostics = TapDiagnostics()

    var onOpened: ((CameraCapabilities) -> Unit)? = null
    var onError: ((CameraEngineError) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    /** Latest HAL-reported white-balance values (throttled; not per-frame). */
    var onWbResult: ((AppliedWhiteBalance) -> Unit)? = null
    /** AF state transitions: called when the HAL reports AF_STATE_LOCKED or AF_STATE_NOT_LOCKED. */
    var onFocusState: ((FocusState) -> Unit)? = null

    /**
     * @param preferredFps user's fps preference for the AE target range; clamped against the
     * device's reported ranges by [RequestPlanner.chooseTargetFps].
     */
    fun open(cameraId: String, surface: Surface, caps: CameraCapabilities? = null, preferredFps: Int = 30) {
        onCameraThread {
            closeInternal(notify = false)
            val generation = ++openGeneration
            previewSurface = surface
            latestAutoWb = null
            manualWbReference = null
            lastLoggedApplied = null
            lastActivePhysicalId = null
            val probed = caps ?: runCatching { probe.probe(cameraId) }.getOrElse { e ->
                onError?.invoke(CameraEngineError.ProbeFailed(e.message ?: "probe failed"))
                return@onCameraThread
            }
            capabilities = probed
            targetFps = RequestPlanner.chooseTargetFps(probed.aeTargetFpsRanges, desired = preferredFps)
            val handler = requireNotNull(cameraHandler)
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation != openGeneration) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(camera, generation)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (generation != openGeneration) {
                        camera.close()
                        return
                    }
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    onError?.invoke(CameraEngineError.Disconnected)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (generation != openGeneration) {
                        camera.close()
                        return
                    }
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    onError?.invoke(CameraEngineError.OpenFailed(error))
                }
            }
            try {
                cameraManager.openCamera(cameraId, stateCallback, handler)
            } catch (_: CameraAccessException) {
                onError?.invoke(CameraEngineError.OpenFailed(-1))
            } catch (_: SecurityException) {
                onError?.invoke(CameraEngineError.OpenFailed(-2))
            }
        }
    }

    fun apply(newSettings: CameraSettings) {
        onCameraThread {
            val previousMode = settings.whiteBalanceMode
            settings = newSettings
            when {
                newSettings.whiteBalanceMode == WhiteBalanceMode.MANUAL &&
                    previousMode != WhiteBalanceMode.MANUAL -> {
                    // Freeze the current auto calibration as the manual-WB reference. On CCT
                    // devices the planner ignores this; on fallback devices it anchors the
                    // relative gains to the HAL's own calibration.
                    freezeManualReference()
                }
                newSettings.whiteBalanceMode == WhiteBalanceMode.AUTO -> {
                    // Returning to auto: allow a fresh freeze next time manual is entered.
                    manualWbReference = null
                }
            }
            applyInternal()
        }
    }

    /**
     * Begin a single-shot autofocus: the repeating request is rebuilt with AF_MODE_AUTO (+ regions
     * when supported, trigger IDLE), then ONE capture is sent carrying `AF_TRIGGER_START`. The
     * repeating request never carries a one-shot trigger key.
     */
    fun tapFocus(region: FocusRegion?) {
        onCameraThread {
            focusIntent = FocusIntent.TAPPING(region)
            tapDiagnostics.start()
            Log.i(TAG, "af tap requested region=$region")
            applyInternal()
            captureSingleShot()
        }
    }

    /** Unlock: clear regions/intent and restore continuous autofocus. */
    fun cancelFocusLock() {
        onCameraThread {
            focusIntent = FocusIntent.IDLE
            tapDiagnostics.reset()
            onFocusState?.invoke(FocusState.IDLE)
            applyInternal()
        }
    }

    /**
     * Sends a single capture with `AF_TRIGGER_START` on top of the current repeating request
     * (regions + AF_MODE_AUTO from the TAPPING plan). The repeating request keeps trigger IDLE.
     */
    private fun captureSingleShot() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val caps = capabilities ?: return
        val surfaces = sessionSurfaces()
        if (surfaces.isEmpty()) return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            for (surface in surfaces) {
                builder.addTarget(surface)
            }
            val ops = RequestPlanner.plan(settings, caps, targetFps, manualWbReference, focusIntent)
            RequestApplier.apply(builder, ops)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), captureCallback, cameraHandler)
            Log.i(TAG, "af single-shot ops=${ops.joinToString(", ")} trigger=START")
        } catch (e: Exception) {
            Log.w(TAG, "af single-shot failed: ${e.message}")
        }
    }

    private fun freezeManualReference() {
        val snapshot = latestAutoWb ?: run {
            Log.i(TAG, "wb manual requested before any auto calibration; staying on auto until one arrives")
            return
        }
        val referenceKelvin = snapshot.sceneCct ?: DEFAULT_REFERENCE_KELVIN
        if (snapshot.sceneCct == null) {
            Log.i(TAG, "wb scene cct not reported by hal; using referenceKelvin=$referenceKelvin")
        }
        manualWbReference = ManualWbReference(
            colorTransform = snapshot.colorTransform,
            gains = snapshot.gains,
            kelvin = referenceKelvin,
        )
        Log.i(
            TAG,
            "wb manual reference frozen kelvin=$referenceKelvin " +
                "gains=r${snapshot.gains.red}/g${snapshot.gains.greenEven}/b${snapshot.gains.blue}",
        )
    }

    /**
     * Attaches or detaches an extra stream target (encoder input surface). Rebuilds the current
     * session (camera device stays open) so [apply] targets all session surfaces. A null surface
     * is stored and takes effect on the next session creation; it is kept across [open]/[close]
     * and only cleared here or in [release].
     */
    fun setStreamingSurface(surface: Surface?) {
        onCameraThread { setStreamingSurfaceInternal(surface) }
    }

    private fun setStreamingSurfaceInternal(surface: Surface?) {
        if (surface != null && !surface.isValid) {
            Log.w(TAG, "ignoring abandoned streaming surface")
            return
        }
        if (streamingSurface === surface) return
        streamingSurface = surface
        val device = cameraDevice ?: return
        captureSession?.close()
        captureSession = null
        createSession(device, openGeneration)
    }

    fun close() {
        onCameraThread { closeInternal(notify = true) }
    }

    fun release() {
        onCameraThread {
            closeInternal(notify = false)
            streamingSurface = null
            openGeneration++
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, generation: Int) {
        // A rebuilt session must never inherit diagnostics from a focus attempt on the old one.
        tapDiagnostics.reset()
        if (previewSurface == null) return
        val surfaces = sessionSurfaces()
        if (surfaces.isEmpty()) return
        if (surfaces.any { !it.isValid }) {
            Log.w(TAG, "cannot create session: abandoned surface")
            onError?.invoke(CameraEngineError.SessionFailed)
            return
        }
        val handler = requireNotNull(cameraHandler)
        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (generation != openGeneration || cameraDevice == null) {
                    session.close()
                    return
                }
                captureSession = session
                applyInternal()
                capabilities?.let { onOpened?.invoke(it) }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (generation != openGeneration) return
                captureSession = null
                onError?.invoke(CameraEngineError.SessionFailed)
            }
        }
        try {
            camera.createCaptureSession(surfaces, sessionCallback, handler)
        } catch (_: CameraAccessException) {
            onError?.invoke(CameraEngineError.SessionFailed)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "session creation rejected", e)
            onError?.invoke(CameraEngineError.SessionFailed)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "camera closed while creating session", e)
            onError?.invoke(CameraEngineError.SessionFailed)
        }
    }

    private fun sessionSurfaces(): List<Surface> = listOfNotNull(previewSurface, streamingSurface)

    private fun applyInternal() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surfaces = sessionSurfaces()
        if (surfaces.isEmpty()) return
        val caps = capabilities ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            for (surface in surfaces) {
                builder.addTarget(surface)
            }
            val ops = RequestPlanner.plan(settings, caps, targetFps, manualWbReference, focusIntent)
            RequestApplier.apply(builder, ops)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            // A zoom ramp can call this ~20x/s; throttle the diagnostic line — but never hide a
            // focus-intent plan, which is rare and security-relevant for the operator.
            val now = SystemClock.elapsedRealtime()
            if (focusIntent != FocusIntent.IDLE || now - lastRequestLogAtMs >= REQUEST_LOG_INTERVAL_MS) {
                lastRequestLogAtMs = now
                Log.i(TAG, "request ops intent=$focusIntent ops=${ops.joinToString(", ")}")
            }
        } catch (_: CameraAccessException) {
            onError?.invoke(CameraEngineError.SessionFailed)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "camera rejected request", e)
            onError?.invoke(CameraEngineError.SessionFailed)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "camera closed while applying request", e)
            onError?.invoke(CameraEngineError.SessionFailed)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE)
            val correctionMode = result.get(CaptureResult.COLOR_CORRECTION_MODE)
            result.get(CaptureResult.SCALER_CROP_REGION)?.let {
                lastScalerCrop = IntRect(it.left, it.top, it.right, it.bottom)
            }
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            val sceneCct = readSceneCct(result)
            val appliedCct = readAppliedCct(result)
            val appliedTint = readAppliedTint(result)
            reportAfState(result)

            if (tapDiagnostics.active) {
                // Meaningful changes only: the HAL can echo the same region for every frame of a
                // scan. State transitions are logged by reportAfState; the lock carries the final
                // measured distance there.
                val returned = result.get(CaptureResult.CONTROL_AF_REGIONS)?.contentToString()
                if (tapDiagnostics.onReturnedRegions(returned)) {
                    Log.i(TAG, "af tap returned regions=$returned")
                }
                tapDiagnostics.onAfState(result.get(CaptureResult.CONTROL_AF_STATE))
            }

            // Learn the HAL calibration only while AWB is actually driving the pipeline; in manual
            // mode the reported gains are our own, not the device's calibration.
            if (awbMode != null &&
                awbMode != Camera2Constants.AWB_MODE_OFF &&
                transform != null &&
                gains != null
            ) {
                val elements = IntArray(18)
                try {
                    // Layout: 9 rational pairs (numerator, denominator), row-major.
                    transform.copyElements(elements, 0)
                } catch (_: Exception) {
                    return
                }
                val learnedGains = RggbGains(
                    red = gains.red,
                    greenEven = gains.greenEven,
                    greenOdd = gains.greenOdd,
                    blue = gains.blue,
                )
                latestAutoWb = AutoWbSnapshot(
                    colorTransform = elements.toList(),
                    gains = learnedGains,
                    sceneCct = sceneCct,
                )
                // Manual was requested before any auto frame arrived; freeze now and re-plan.
                if (settings.whiteBalanceMode == WhiteBalanceMode.MANUAL && manualWbReference == null) {
                    freezeManualReference()
                    applyInternal()
                }
            }

            val applied = AppliedWhiteBalance(
                colorCorrectionMode = correctionMode,
                awbMode = awbMode,
                colorTemperature = appliedCct,
                colorTint = appliedTint,
                gains = gains?.let {
                    RggbGains(it.red, it.greenEven, it.greenOdd, it.blue)
                },
                sceneCct = sceneCct,
            )
            maybeReportAppliedWb(applied)
            reportActivePhysicalId(result)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            Log.w(TAG, "capture failed reason=${failure.reason} frame=${failure.frameNumber}")
        }
    }

    private fun readSceneCct(result: TotalCaptureResult): Int? {
        // SENSOR_NEUTRAL_COLOR_POINT is the HAL's estimated scene white point in CIE 1931 xyY.
        // Convert to CCT with the same McCamy model the fallback gain math uses. Present on all
        // API 21+ devices, but still optional in practice — null-checked.
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) ?: return null
        val x = neutral.getOrNull(0)?.toFloat() ?: return null
        val y = neutral.getOrNull(1)?.toFloat() ?: return null
        return com.streamcam.imageproc.WhiteBalance.cctFromXy(x, y)
    }

    private fun readAppliedCct(result: TotalCaptureResult): Int? {
        if (android.os.Build.VERSION.SDK_INT < 36) return null
        return result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)
    }

    private fun readAppliedTint(result: TotalCaptureResult): Int? {
        if (android.os.Build.VERSION.SDK_INT < 36) return null
        return result.get(CaptureResult.COLOR_CORRECTION_COLOR_TINT)
    }

    /**
     * Logs which physical camera the logical multi-camera HAL is using, whenever it changes.
     * This is the ground truth behind zoom ratios: e.g. 0.5x → ultrawide, 5x → telephoto.
     */
    private fun reportActivePhysicalId(result: TotalCaptureResult) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return
        if (physicalId == lastActivePhysicalId) return
        lastActivePhysicalId = physicalId
        val focal = capabilities?.focalLengthsByCamera?.get(physicalId)
        Log.i(TAG, "active physical camera=$physicalId focal=$focal zoom=${settings.zoomRatio}")
    }

    /**
     * Drives the focus intent from the HAL's AF state. Success holds the *measured*
     * `LENS_FOCUS_DISTANCE`; failure reports FAILED and returns to continuous AF; a re-hunting
     * HAL invalidates a hold. See [FocusTransition] for the pure state machine.
     */
    private fun reportAfState(result: TotalCaptureResult) {
        val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
        val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val (nextIntent, uiState) = FocusTransition.onAfState(focusIntent, state, distance)
        if (nextIntent == focusIntent && uiState == null) return
        focusIntent = nextIntent
        uiState?.let { onFocusState?.invoke(it) }
        Log.i(TAG, "af transition state=$state intent=$nextIntent ui=$uiState distance=$distance")
        applyInternal()
    }

    /**
     * Surfaces HAL-reported WB values to the UI and logs them, throttled: always on a meaningful
     * change (mode, >50 K, >1 tint), otherwise at most once per [WB_LOG_INTERVAL_MS]. Never per
     * frame.
     */
    private fun maybeReportAppliedWb(applied: AppliedWhiteBalance) {
        val previous = lastLoggedApplied
        val now = SystemClock.elapsedRealtime()
        val meaningfulChange = previous == null ||
            previous.colorCorrectionMode != applied.colorCorrectionMode ||
            previous.awbMode != applied.awbMode ||
            kotlin.math.abs((applied.colorTemperature ?: 0) - (previous.colorTemperature ?: 0)) > 50 ||
            kotlin.math.abs((applied.colorTint ?: 0) - (previous.colorTint ?: 0)) > 1
        val intervalElapsed = now - lastWbLogAtMs >= WB_LOG_INTERVAL_MS
        if (!meaningfulChange && !intervalElapsed) return
        lastLoggedApplied = applied
        lastWbLogAtMs = now
        Log.i(
            TAG,
            "wb result mode=${applied.colorCorrectionMode} awb=${applied.awbMode} " +
                "cct=${applied.colorTemperature} tint=${applied.colorTint} " +
                "gains=${applied.gains?.let { "r${it.red}/g${it.greenEven}/b${it.blue}" }} " +
                "sceneCct=${applied.sceneCct} " +
                "requestedK=${settings.kelvin} requestedTint=${settings.tint}",
        )
        onWbResult?.invoke(applied)
    }

    private fun closeInternal(notify: Boolean) {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface = null
        tapDiagnostics.reset()
        // Focus cannot survive a closed device: never leave the UI or a stale intent locked.
        if (focusIntent != FocusIntent.IDLE) {
            focusIntent = FocusIntent.IDLE
            onFocusState?.invoke(FocusState.IDLE)
        }
        if (notify) onClosed?.invoke()
    }

    private fun onCameraThread(block: () -> Unit) {
        val handler = cameraHandler ?: run {
            val thread = HandlerThread("camera").also { it.start() }
            cameraThread = thread
            Handler(thread.looper).also { cameraHandler = it }
        }
        handler.post(block)
    }

    private companion object {
        const val TAG = "StreamCamera"
        const val WB_LOG_INTERVAL_MS = 5_000L
        const val DEFAULT_REFERENCE_KELVIN = 5500
        const val REQUEST_LOG_INTERVAL_MS = 150L
    }
}
