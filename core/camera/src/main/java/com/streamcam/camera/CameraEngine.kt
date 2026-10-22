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
import android.util.Log
import android.view.Surface

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
     * Sensor->sRGB color transform reported by the HAL for the current camera. Learned from capture
     * results because [CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX] requires a real
     * matrix (an identity matrix renders dark/green, and Pixel ignores gains without it).
     */
    private var colorTransform: List<Int>? = null

    var onOpened: ((CameraCapabilities) -> Unit)? = null
    var onError: ((CameraEngineError) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    fun open(cameraId: String, surface: Surface, caps: CameraCapabilities? = null) {
        onCameraThread {
            closeInternal(notify = false)
            val generation = ++openGeneration
            previewSurface = surface
            colorTransform = null
            val probed = caps ?: runCatching { probe.probe(cameraId) }.getOrElse { e ->
                onError?.invoke(CameraEngineError.ProbeFailed(e.message ?: "probe failed"))
                return@onCameraThread
            }
            capabilities = probed
            targetFps = RequestPlanner.chooseTargetFps(probed.aeTargetFpsRanges)
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
            settings = newSettings
            applyInternal()
        }
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
            val ops = RequestPlanner.plan(settings, caps, targetFps, colorTransform)
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
            if (colorTransform != null) return
            val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return
            val elements = IntArray(18)
            try {
                // Layout: 9 rational pairs (numerator, denominator), row-major.
                transform.copyElements(elements, 0)
            } catch (_: Exception) {
                return
            }
            val learned = elements.toList()
            colorTransform = learned
            Log.i(TAG, "learned color transform=${learned.joinToString(",")}")
            if (settings.whiteBalanceMode == WhiteBalanceMode.MANUAL) {
                applyInternal()
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            Log.w(TAG, "capture failed reason=${failure.reason} frame=${failure.frameNumber}")
        }
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
    }
}
