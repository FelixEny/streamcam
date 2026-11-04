package com.streamcam

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only hardware diagnostic. Enumerates every camera (logical + physical), records the AF
 * capabilities, then performs a real region-targeted one-shot AF test on every camera that
 * advertises `CONTROL_MAX_REGIONS_AF > 0`.
 *
 * It never changes production focus behaviour: it opens its own sessions, one camera at a time,
 * and writes a plain-text report to the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class FocusDiagnosticsTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: CameraManager
        get() = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val report = StringBuilder()

    @Test
    fun diagnoseAutofocus() {
        log("=== StreamCam AF capability diagnostics ===")
        log("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")

        val ids = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            log("cameraIdList FAILED: ${e.javaClass.simpleName}: ${e.message}")
            writeReport()
            return
        }
        log("cameraIdList: $ids")
        log("")

        // Cross-check the production capability probe against raw CameraCharacteristics.
        val appProbe = try {
            com.streamcam.camera.CapabilityProbe(manager)
        } catch (e: Exception) {
            log("production CapabilityProbe construction FAILED: ${e.message}")
            null
        }
        if (appProbe != null) {
            for (id in ids) {
                try {
                    val caps = appProbe.probe(id)
                    log("production probe camera $id: maxAfRegions=${caps.maxAfRegions} " +
                        "supportsTapToFocus=${caps.supportsTapToFocus} " +
                        "afModes=${caps.afModes} activeArray=${caps.activeArraySize}")
                } catch (e: Exception) {
                    log("production probe camera $id FAILED: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        val infos = mutableListOf<CamInfo>()
        for (id in ids) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                log("camera $id: getCameraCharacteristics FAILED: ${e.message}")
                continue
            }
            val info = readInfo(id, chars)
            infos += info
            logCamera(info)
            if (Build.VERSION.SDK_INT >= 28) {
                for (physicalId in info.physicalIds) {
                    val physicalChars = try {
                        manager.getCameraCharacteristics(physicalId)
                    } catch (e: Exception) {
                        log("  physical $physicalId: characteristics FAILED: ${e.message}")
                        continue
                    }
                    val physicalInfo = readInfo(physicalId, physicalChars)
                    logCamera(physicalInfo, prefix = "  ")
                    logPhysicalRequestKeys(info, physicalId)
                }
            }
            log("")
        }

        log("=== Region-targeted one-shot AF tests ===")
        val regionCapable = infos.filter { it.maxAfRegions > 0 }
        if (regionCapable.isEmpty()) {
            log("NO camera reports CONTROL_MAX_REGIONS_AF > 0 -> region targeting is not")
            log("advertised anywhere. Region test skipped; no full-frame fallback is claimed.")
        } else {
            for (info in regionCapable) {
                runRegionSuite(info)
            }
        }

        log("")
        log("=== Negative control: region request on maxAfRegions == 0 cameras ===")
        for (info in infos.filter { it.maxAfRegions == 0 }) {
            negativeControl(info)
        }

        log("")
        log("=== Physical camera access tests ===")
        for (logical in infos.filter { it.logical }) {
            if (logical.physicalIds.isEmpty()) continue
            for (physicalId in logical.physicalIds) {
                physicalAccessTest(logical, physicalId)
            }
        }

        writeReport()
    }

    // ---------------------------------------------------------------------------------------
    // Characteristics
    // ---------------------------------------------------------------------------------------

    private data class CamInfo(
        val id: String,
        val chars: CameraCharacteristics,
        val logical: Boolean,
        val physicalIds: List<String>,
        val maxAfRegions: Int,
        val afModes: List<Int>,
    )

    private fun readInfo(id: String, chars: CameraCharacteristics): CamInfo {
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList().orEmpty()
        val logical = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
        )
        val physicalIds = if (Build.VERSION.SDK_INT >= 28) {
            try {
                chars.physicalCameraIds.toList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return CamInfo(
            id = id,
            chars = chars,
            logical = logical,
            physicalIds = physicalIds,
            maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
        )
    }

    private fun logCamera(info: CamInfo, prefix: String = "") {
        val c = info.chars
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val hyper = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        log("${prefix}camera ${info.id}:")
        log("${prefix}  facing=${facingName(c.get(CameraCharacteristics.LENS_FACING))}")
        log("${prefix}  hardwareLevel=${hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
        log("${prefix}  logicalMultiCamera=${info.logical} physicalIds=${info.physicalIds}")
        log("${prefix}  CONTROL_MAX_REGIONS_AF=${info.maxAfRegions}")
        log("${prefix}  CONTROL_AF_AVAILABLE_MODES=${info.afModes.map { afModeName(it) }} (${info.afModes})")
        log("${prefix}  LENS_INFO_MINIMUM_FOCUS_DISTANCE=${minFocus} dioptres")
        log("${prefix}  LENS_INFO_HYPERFOCAL_DISTANCE=${hyper} dioptres")
        log("${prefix}  SENSOR_INFO_ACTIVE_ARRAY_SIZE=$active")
        log("${prefix}  REQUEST_AVAILABLE_CAPABILITIES=${caps.map { capabilityName(it) }} ($caps)")
    }

    private fun logPhysicalRequestKeys(logical: CamInfo, physicalId: String) {
        if (Build.VERSION.SDK_INT < 35) {
            log("    physical($physicalId) request keys: unavailable (sdk < 35)")
            return
        }
        val keys = try {
            logical.chars.availablePhysicalCameraRequestKeys
        } catch (e: Exception) {
            log("    physical($physicalId) request keys: FAILED ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val names = keys.map { it.name }.sorted()
        log("    physical($physicalId) available request keys (${names.size}): $names")
        log("    physical($physicalId) supports CONTROL_AF_REGIONS=" +
            "${keys.contains(CaptureRequest.CONTROL_AF_REGIONS)}")
        log("    physical($physicalId) supports CONTROL_AF_MODE=" +
            "${keys.contains(CaptureRequest.CONTROL_AF_MODE)}")
        log("    physical($physicalId) supports LENS_FOCUS_DISTANCE=" +
            "${keys.contains(CaptureRequest.LENS_FOCUS_DISTANCE)}")
    }

    // ---------------------------------------------------------------------------------------
    // Region test
    // ---------------------------------------------------------------------------------------

    private data class Probe(
        val label: String,
        val requestedRegion: Rect,
        val acceptedRegion: Rect?,
        val regionMatches: Boolean,
        val afModeEcho: Int?,
        val afState: Int?,
        val focusDistance: Float?,
        val elapsedMs: Long,
        val error: String?,
        val scanTrace: List<Int> = emptyList(),
        val leftPreviousLock: Boolean = false,
    )

    private inner class SessionHandle(
        val cameraId: String,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val reader: ImageReader,
        val surface: android.view.Surface,
        val handler: Handler,
        val thread: HandlerThread,
        val afModes: List<Int>,
        val activeArray: Rect,
    ) : AutoCloseable {
        override fun close() {
            try { session.close() } catch (_: Exception) {}
            try { device.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    private fun runRegionSuite(info: CamInfo) {
        log("camera ${info.id}: maxAfRegions=${info.maxAfRegions} modes=${info.afModes.map { afModeName(it) }}")

        val handle = try {
            openSession(info, physicalId = null)
        } catch (e: Exception) {
            log("  open/session FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        } ?: return

        handle.use { h ->
            val size = regionSize(h.activeArray)
            val centre = centreRegion(h.activeArray, size)
            val topLeft = cornerRegion(h.activeArray, size, right = false, bottom = false)
            val bottomRight = cornerRegion(h.activeArray, size, right = true, bottom = true)

            // Interleave regions so slow scene/lens drift cannot masquerade as a region effect.
            val probes = listOf(
                probe(h, centre, "centre#1", frameFile = "af_${info.id}_centre1.jpg"),
                probe(h, topLeft, "corner(top-left)", frameFile = "af_${info.id}_edge.jpg"),
                probe(h, centre, "centre#2", frameFile = "af_${info.id}_centre2.jpg"),
                probe(h, bottomRight, "corner(bottom-right)", frameFile = "af_${info.id}_br.jpg"),
                probe(h, centre, "centre#3", frameFile = null),
            )
            for (p in probes) logProbe(p)

            val centres = probes.filter { it.label.startsWith("centre") }
                .mapNotNull { it.focusDistance }
            val corners = probes.filter { it.label.startsWith("corner") }
                .mapNotNull { it.focusDistance }
            if (centres.size >= 2 && corners.isNotEmpty()) {
                val centreSpread = (centres.maxOrNull()!! - centres.minOrNull()!!)
                val centreMean = centres.average()
                val cornerMean = corners.average()
                val delta = kotlin.math.abs(cornerMean - centreMean)
                log("  centre distances=${centres.map { "%.4f".format(it) }} mean=${"%.4f".format(centreMean)}")
                log("  corner distances=${corners.map { "%.4f".format(it) }} mean=${"%.4f".format(cornerMean)}")
                log("  centre re-test spread = ${"%.4f".format(centreSpread)} dpt")
                log("  centre vs corner mean delta = ${"%.4f".format(delta)} dpt")
                if (delta > centreSpread + 0.05f) {
                    log("  => REGION CHANGED THE FOCUS RESULT (reproducible across interleaved taps)")
                } else {
                    log("  => no reproducible region effect (scene may be flat or region ignored)")
                }
            }
        }
    }

    private fun negativeControl(info: CamInfo) {
        log("camera ${info.id}: maxAfRegions=0, modes=${info.afModes.map { afModeName(it) }}")
        if (info.afModes.none { it != CaptureRequest.CONTROL_AF_MODE_OFF }) {
            log("  no usable AF mode; skipped")
            return
        }
        val handle = try {
            openSession(info, physicalId = null)
        } catch (e: Exception) {
            log("  open/session FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        } ?: return
        handle.use { h ->
            val size = regionSize(h.activeArray)
            val centre = centreRegion(h.activeArray, size)
            // Deliberately send a small region even though the camera advertises none. This is a
            // NEGATIVE CONTROL: we are checking whether it is rejected/ignored, NOT claiming
            // point-specific focus.
            val p = probe(h, centre, "unsupported-region(negative)", frameFile = null)
            logProbe(p)
            if (p.error != null) {
                log("  => request rejected/failed: ${p.error}")
            } else if (p.regionMatches) {
                log("  => HAL echoed the region even though maxAfRegions=0 (metadata mismatch?)")
            } else {
                log("  => region not applied (expected: no region support)")
            }
        }
    }

    private fun openSession(info: CamInfo, physicalId: String?): SessionHandle? {
        val thread = HandlerThread("afdiag-${info.id}").apply { start() }
        val handler = Handler(thread.looper)
        val chars = info.chars
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null

        val size = pickSize(chars)
        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            3,
        )

        val openLatch = CountDownLatch(1)
        var device: CameraDevice? = null
        var openError: String? = null
        try {
            manager.openCamera(info.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    openLatch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    openError = "camera disconnected"
                    openLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    openError = "open error $error"
                    openLatch.countDown()
                }
            }, handler)
        } catch (e: Exception) {
            log("  openCamera(${info.id}) threw: ${e.javaClass.simpleName}: ${e.message}")
            reader.close()
            thread.quitSafely()
            return null
        }

        if (!openLatch.await(8, TimeUnit.SECONDS) || device == null) {
            log("  openCamera(${info.id}) failed: ${openError ?: "timeout"}")
            reader.close()
            thread.quitSafely()
            return null
        }
        val cam = device!!

        return try {
            val session = if (physicalId == null) {
                createSession(cam, listOf(reader.surface), handler)
            } else {
                createPhysicalSession(cam, reader.surface, physicalId, handler)
            }
            if (session == null) {
                cam.close()
                reader.close()
                thread.quitSafely()
                return null
            }
            SessionHandle(
                cameraId = info.id,
                device = cam,
                session = session,
                reader = reader,
                surface = reader.surface,
                handler = handler,
                thread = thread,
                afModes = info.afModes,
                activeArray = activeArray,
            )
        } catch (e: Exception) {
            log("  createCaptureSession FAILED: ${e.javaClass.simpleName}: ${e.message}")
            cam.close()
            reader.close()
            thread.quitSafely()
            null
        }
    }

    private fun createSession(
        device: CameraDevice,
        surfaces: List<android.view.Surface>,
        handler: Handler,
    ): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var error: String? = null
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                latch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                error = "configure failed"
                latch.countDown()
            }
        }, handler)
        if (!latch.await(8, TimeUnit.SECONDS) || session == null) {
            log("  session configure failed: ${error ?: "timeout"}")
            return null
        }
        return session
    }

    private fun createPhysicalSession(
        device: CameraDevice,
        surface: android.view.Surface,
        physicalId: String,
        handler: Handler,
    ): CameraCaptureSession? {
        val output = OutputConfiguration(surface)
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                output.setPhysicalCameraId(physicalId)
            } catch (e: Exception) {
                log("  physical($physicalId) setPhysicalCameraId FAILED: ${e.message}")
                return null
            }
        } else {
            log("  physical sessions unavailable below API 28")
            return null
        }
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var error: String? = null
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSessionByOutputConfigurations(
                listOf(output),
                object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    latch.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    error = "physical configure failed"
                    latch.countDown()
                }
            }, handler)
        } catch (e: Exception) {
            log("  physical($physicalId) session threw: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!latch.await(8, TimeUnit.SECONDS) || session == null) {
            log("  physical($physicalId) session failed: ${error ?: "timeout"}")
            return null
        }
        log("  physical($physicalId) session CONFIGURED through the logical camera")
        return session
    }

    private fun probe(
        handle: SessionHandle,
        region: Rect,
        label: String,
        frameFile: String?,
    ): Probe {
        val afMode = chooseAfMode(handle.afModes)
            ?: return Probe(label, region, null, false, null, null, null, 0, "no AF mode available")

        val started = SystemClock.elapsedRealtime()
        val settled = CountDownLatch(1)
        val leftLock = CountDownLatch(1)
        val lock = Any()
        var state: Int? = null
        var distance: Float? = null
        var echoRegion: Rect? = null
        var echoMode: Int? = null
        var sawLeftLock = false
        val trace = mutableListOf<Int>()
        // 0 = cancelling, 1 = idle repeating, 2 = armed for the one-shot result.
        val phase = AtomicInteger(0)

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
                val currentPhase = phase.get()
                synchronized(lock) {
                    if (currentPhase >= 1 && trace.size < 32 &&
                        (trace.isEmpty() || trace.last() != afState)
                    ) {
                        trace += afState
                    }
                    // A new scan must be preceded by the HAL leaving its previous lock.
                    if (currentPhase <= 1 && (afState == CaptureResult.CONTROL_AF_STATE_INACTIVE ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
                    ) {
                        sawLeftLock = true
                        leftLock.countDown()
                    }
                }
                if (currentPhase < 2) return
                if (afState != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED &&
                    afState != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) return
                synchronized(lock) {
                    if (state != null) return
                    state = afState
                    distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    @Suppress("UNCHECKED_CAST")
                    val regions = result.get(CaptureResult.CONTROL_AF_REGIONS)
                        as? Array<MeteringRectangle>
                    echoRegion = regions?.firstOrNull()?.rect
                    echoMode = result.get(CaptureResult.CONTROL_AF_MODE)
                }
                settled.countDown()
            }
        }

        try {
            // 1. Cancel the previous scan and wait until the HAL actually leaves its lock.
            val cancel = baseBuilder(handle, afMode).apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }.build()
            handle.session.capture(cancel, callback, handle.handler)
            leftLock.await(1500, TimeUnit.MILLISECONDS)

            // 2. Repeating preview request with trigger IDLE (never START).
            phase.set(1)
            val repeating = baseBuilder(handle, afMode).apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }.build()
            handle.session.setRepeatingRequest(repeating, callback, handle.handler)
            Thread.sleep(220)

            // 3. One shot with the region + START. The repeating request stays IDLE.
            synchronized(lock) { trace.clear() }
            phase.set(2)
            val start = baseBuilder(handle, afMode).apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                if (region.width() > 0 && region.height() > 0) {
                    set(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        arrayOf(MeteringRectangle(region, 1000)),
                    )
                }
            }.build()
            handle.session.capture(start, callback, handle.handler)

            settled.await(7, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return Probe(label, region, null, false, null, null, null,
                SystemClock.elapsedRealtime() - started, "${e.javaClass.simpleName}: ${e.message}")
        }

        if (frameFile != null) saveFrame(handle.reader, frameFile)

        val echo: Rect?
        val st: Int?
        val d: Float?
        val seen: List<Int>
        val left: Boolean
        synchronized(lock) {
            echo = echoRegion
            st = state
            d = distance
            seen = trace.toList()
            left = sawLeftLock
        }
        return Probe(
            label = label,
            requestedRegion = region,
            acceptedRegion = echo,
            regionMatches = echo?.let {
                kotlin.math.abs(it.centerX() - region.centerX()) <= 2 &&
                    kotlin.math.abs(it.centerY() - region.centerY()) <= 2
            } ?: false,
            afModeEcho = echoMode,
            afState = st,
            focusDistance = d,
            elapsedMs = SystemClock.elapsedRealtime() - started,
            error = if (st == null) "no settle within timeout" else null,
            scanTrace = seen,
            leftPreviousLock = left,
        )
    }

    private fun baseBuilder(handle: SessionHandle, afMode: Int): CaptureRequest.Builder {
        val builder = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(handle.surface)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        return builder
    }

    private fun logProbe(p: Probe) {
        log("  [$p.label] requested=${p.requestedRegion}")
        log("    acceptedRegion=${p.acceptedRegion} matches=$p.regionMatches " +
            "afModeEcho=${p.afModeEcho?.let { afModeName(it) }}")
        log("    afState=${p.afState?.let { afStateName(it) }} " +
            "focusDistance=${p.focusDistance} elapsed=${p.elapsedMs}ms")
        log("    leftPreviousLock=${p.leftPreviousLock} scanTrace=${p.scanTrace.map { afStateName(it) }}")
        p.error?.let { log("    error=$it") }
    }

    private fun chooseAfMode(modes: List<Int>): Int? = when {
        CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        CaptureRequest.CONTROL_AF_MODE_MACRO in modes -> CaptureRequest.CONTROL_AF_MODE_MACRO
        else -> modes.firstOrNull { it != CaptureRequest.CONTROL_AF_MODE_OFF }
    }

    private fun pickSize(chars: CameraCharacteristics): android.util.Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        return sizes.filter { it.width in 640..1920 && it.height in 480..1080 }
            .minByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: android.util.Size(640, 480)
    }

    private fun regionSize(active: Rect): android.util.Size {
        val w = (active.width() * 0.12f).toInt().coerceAtLeast(60)
        val h = (active.height() * 0.12f).toInt().coerceAtLeast(60)
        return android.util.Size(w, h)
    }

    private fun centreRegion(active: Rect, size: android.util.Size): Rect {
        val cx = active.centerX()
        val cy = active.centerY()
        return Rect(cx - size.width / 2, cy - size.height / 2, cx + size.width / 2, cy + size.height / 2)
    }

    private fun cornerRegion(
        active: Rect,
        size: android.util.Size,
        right: Boolean,
        bottom: Boolean,
    ): Rect {
        // 8% in from the chosen corner, deliberately far from the centre.
        val insetX = (active.width() * 0.08f).toInt()
        val insetY = (active.height() * 0.08f).toInt()
        val left = if (right) {
            active.right - insetX - size.width
        } else {
            active.left + insetX
        }
        val top = if (bottom) {
            active.bottom - insetY - size.height
        } else {
            active.top + insetY
        }
        return Rect(left, top, left + size.width, top + size.height)
    }

    // ---------------------------------------------------------------------------------------
    // Physical access
    // ---------------------------------------------------------------------------------------

    private fun physicalAccessTest(logical: CamInfo, physicalId: String) {
        val physicalChars = try {
            manager.getCameraCharacteristics(physicalId)
        } catch (e: Exception) {
            log("logical ${logical.id} -> physical $physicalId: characteristics FAILED: ${e.message}")
            return
        }
        val pMaxAf = physicalChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val pModes = physicalChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        log("logical ${logical.id} -> physical $physicalId: maxAfRegions=$pMaxAf " +
            "modes=${pModes.map { afModeName(it) }}")

        // 1. Can the physical camera be opened directly?
        val directThread = HandlerThread("phys-open-$physicalId").apply { start() }
        val directHandler = Handler(directThread.looper)
        val openLatch = CountDownLatch(1)
        var directOpened = false
        var directError: String? = null
        try {
            manager.openCamera(physicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    directOpened = true
                    camera.close()
                    openLatch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    directError = "disconnected"
                    camera.close()
                    openLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    directError = "open error $error"
                    camera.close()
                    openLatch.countDown()
                }
            }, directHandler)
        } catch (e: Exception) {
            directError = "${e.javaClass.simpleName}: ${e.message}"
            openLatch.countDown()
        }
        openLatch.await(6, TimeUnit.SECONDS)
        directThread.quitSafely()
        log("  direct open($physicalId): opened=$directOpened error=$directError")

        // 2. If it advertises AF regions, try to use it through the logical session.
        if (pMaxAf <= 0) {
            log("  physical $physicalId has no AF regions; physical AF test not applicable")
            return
        }
        if (Build.VERSION.SDK_INT >= 35) {
            val keys = try {
                logical.chars.availablePhysicalCameraRequestKeys
            } catch (e: Exception) {
                null
            }
            val hasRegions = keys?.contains(CaptureRequest.CONTROL_AF_REGIONS) == true
            log("  logical session advertises physical CONTROL_AF_REGIONS=$hasRegions")
            if (!hasRegions) {
                log("  => logical session cannot carry physical AF regions; test skipped")
                return
            }
        }

        val physicalInfo = readInfo(physicalId, physicalChars)
        val handle = try {
            openSession(physicalInfo, physicalId)
        } catch (e: Exception) {
            log("  physical session FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (handle == null) {
            log("  => physical camera could not be used through the logical session")
            return
        }
        handle.use { h ->
            val size = regionSize(h.activeArray)
            val centre = centreRegion(h.activeArray, size)
            val p = probe(h, centre, "physical-$physicalId", frameFile = "af_physical_${physicalId}.jpg")
            logProbe(p)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Frame saving / report
    // ---------------------------------------------------------------------------------------

    private fun saveFrame(reader: ImageReader, name: String) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val w = image.width
            val h = image.height
            val nv21 = yuv420ToNv21(image)
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(Rect(0, 0, w, h), 88, out)
            val dir = ctx.getExternalFilesDir("focus_frames") ?: ctx.filesDir
            dir.mkdirs()
            File(dir, name).writeBytes(out.toByteArray())
        } catch (e: Exception) {
            log("  saveFrame($name) failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yb = y.buffer
        val ub = u.buffer
        val vb = v.buffer
        var pos = 0
        for (row in 0 until h) {
            val base = row * y.rowStride
            for (col in 0 until w) nv21[pos++] = yb.get(base + col)
        }
        val chromaH = h / 2
        val chromaW = w / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val vIdx = row * v.rowStride + col * v.pixelStride
                val uIdx = row * u.rowStride + col * u.pixelStride
                nv21[pos++] = vb.get(vIdx)
                nv21[pos++] = ub.get(uIdx)
            }
        }
        return nv21
    }

    private fun writeReport() {
        val text = report.toString()
        Log.i(TAG, text)
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val file = File(dir, "focus_diagnostics.txt")
            file.writeText(text)
            Log.i(TAG, "report: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "writeReport failed", e)
        }
    }

    private fun log(line: String) {
        report.append(line).append('\n')
        Log.i(TAG, line)
    }

    // ---------------------------------------------------------------------------------------
    // Name helpers
    // ---------------------------------------------------------------------------------------

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($facing)"
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($level)"
    }

    private fun afModeName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
        CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
        CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "MODE($mode)"
    }

    private fun afStateName(state: Int): String = when (state) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        else -> "STATE($state)"
    }

    private fun capabilityName(capability: Int): String = when (capability) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        else -> "CAP($capability)"
    }

    private companion object {
        const val TAG = "FocusDiag"
    }
}
