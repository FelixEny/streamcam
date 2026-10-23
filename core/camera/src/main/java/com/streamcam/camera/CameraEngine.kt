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

    var onOpened: ((CameraCapabilities) -> Unit)? = null
    var onError: ((CameraEngineError) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    /** Latest HAL-reported white-balance values (throttled; not per-frame). */
    var onWbResult: ((AppliedWhiteBalance) -> Unit)? = null

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
            val ops = RequestPlanner.plan(settings, caps, targetFps, manualWbReference)
            RequestApplier.apply(builder, ops)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            Log.i(TAG, "request ops=${ops.joinToString(", ")}")
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
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            val sceneCct = readSceneCct(result)
            val appliedCct = readAppliedCct(result)
            val appliedTint = readAppliedTint(result)

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
    }
}
