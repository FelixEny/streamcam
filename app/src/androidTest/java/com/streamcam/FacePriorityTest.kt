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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.streamcam.camera.Camera2Constants
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only hardware diagnostic for face detection and the built-in FACE_PRIORITY scene mode.
 *
 * It never changes production camera behaviour: it opens its own session on one logical camera and
 * writes a plain-text report to the app's external files dir.
 *
 * Phases are selected with instrumentation args so the operator can run the ones that need a face
 * separately from the ones that do not:
 *
 * ```
 * adb shell am instrument -w \
 *   -e class com.streamcam.FacePriorityTest \
 *   -e phase capability|noface|faces|regions|all \
 *   -e camera 0 -e seconds 10 \
 *   com.streamcam.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `faceprio-*` phases request `CONTROL_MODE_USE_SCENE_MODE` + `CONTROL_SCENE_MODE_FACE_PRIORITY`
 * with normal AF/AE/AWB modes; `baseline-*` phases request `CONTROL_MODE_AUTO` as the control.
 * Both request `STATISTICS_FACE_DETECT_MODE_FULL` so `STATISTICS_FACES` reports what the HAL
 * actually detects. The decisive evidence is that face array plus measured focus distance, not the
 * advertised capability list.
 */
@RunWith(AndroidJUnit4::class)
class FacePriorityTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: CameraManager
        get() = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val report = StringBuilder()

    private var reader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    /** Set to request one JPEG of the next frame; consumed by the ImageReader listener. */
    private val frameRequested = AtomicBoolean(false)

    @Volatile
    private var frameName: String = "frame"

    @Test
    fun diagnoseFacePriority() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("phase") ?: "all"
        val cameraId = args.getString("camera") ?: "0"
        val seconds = (args.getString("seconds")?.toIntOrNull() ?: 8).coerceIn(3, 60)

        log("=== StreamCam face-priority diagnostics ===")
        log("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        log("phase=$phase camera=$cameraId seconds=$seconds")
        log("")

        capabilityMatrix()

        if (phase == "capability") {
            writeReport()
            return
        }

        thread = HandlerThread("face-diag").also { it.start() }
        handler = Handler(thread.looper)
        try {
            when (phase) {
                "noface" -> {
                    log("")
                    log("=== No-face phases (no operator action needed) ===")
                    sessionSuite(cameraId, seconds, runFaces = false)
                }

                "faces" -> {
                    log("")
                    log("=== Face phases ===")
                    sessionSuite(cameraId, seconds, runFaces = true)
                }

                "regions" -> {
                    log("")
                    log("=== Tap-region vs FACE_PRIORITY ===")
                    regionSuite(cameraId, seconds)
                }

                "probe" -> {
                    log("")
                    log("=== Face probe (saves frames) ===")
                    probeSuite(cameraId, seconds)
                }

                "alternate" -> {
                    log("")
                    log("=== A/B/A: baseline vs FACE_PRIORITY with the face held still ===")
                    alternateSuite(cameraId, seconds)
                }

                "many" -> {
                    log("")
                    log("=== Multiple faces ===")
                    manySuite(cameraId, seconds)
                }

                else -> {
                    log("")
                    log("=== Full suite ===")
                    sessionSuite(cameraId, seconds, runFaces = true)
                    regionSuite(cameraId, seconds)
                }
            }
        } catch (e: Throwable) {
            log("PHASE FAILURE: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            runCatching { thread.quitSafely() }
            writeReport()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Phase 1: capability matrix (logical + physical)
    // ---------------------------------------------------------------------------------------

    private fun capabilityMatrix() {
        log("=== Face detection capability matrix ===")
        log("Contract note: FACE_PRIORITY is always listed when maxFaceCount > 0, so the list alone")
        log("proves only that the camera reports face detection, not that the HAL acts on faces.")
        log("")

        val ids = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            log("cameraIdList FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        log("cameraIdList=$ids")
        log("")

        for (id in ids) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                log("camera $id: getCameraCharacteristics FAILED: ${e.message}")
                continue
            }
            logCamera(id, chars)
            if (Build.VERSION.SDK_INT >= 28) {
                for (physicalId in charactersPhysicalIds(chars)) {
                    val physical = try {
                        manager.getCameraCharacteristics(physicalId)
                    } catch (e: Exception) {
                        log("  physical $physicalId: characteristics FAILED: ${e.message}")
                        continue
                    }
                    logCamera(physicalId, physical, prefix = "  ")
                }
            }
            log("")
        }
    }

    private fun logCamera(id: String, chars: CameraCharacteristics, prefix: String = "") {
        val maxFaces = chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)
        val faceModes = chars
            .get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            ?.toList().orEmpty()
        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?.toList().orEmpty()
        val facing = chars.get(CameraCharacteristics.LENS_FACING)

        log("${prefix}camera $id (${facingName(facing)}):")
        log("${prefix}  STATISTICS_INFO_MAX_FACE_COUNT=${maxFaces ?: "null"}")
        log("${prefix}  STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES=" +
            "${faceModes.map { faceDetectModeName(it) }} ($faceModes)")
        log("${prefix}  face detection OFF=${0 in faceModes} SIMPLE=${1 in faceModes} " +
            "FULL=${2 in faceModes}")
        log("${prefix}  CONTROL_AVAILABLE_SCENE_MODES=" +
            "${sceneModes.map { sceneModeName(it) }} ($sceneModes)")
        log("${prefix}  CONTROL_SCENE_MODE_FACE_PRIORITY=" +
            "${CONTROL_SCENE_MODE_FACE_PRIORITY in sceneModes}")
    }

    // ---------------------------------------------------------------------------------------
    // Phase 2: baseline vs FACE_PRIORITY
    // ---------------------------------------------------------------------------------------

    private fun sessionSuite(cameraId: String, seconds: Int, runFaces: Boolean) {
        val handle = openHandle(cameraId) ?: return
        try {
            if (runFaces) {
                runPhase(
                    handle,
                    label = "baseline-one",
                    facePriority = false,
                    seconds = seconds,
                    prompt = "hold ONE face centred in the frame",
                    waitForFaceSec = 10,
                )
                runPhase(
                    handle,
                    label = "faceprio-one",
                    facePriority = true,
                    seconds = seconds,
                    prompt = "keep ONE face centred in the frame",
                    waitForFaceSec = 10,
                )
                runPhase(
                    handle,
                    label = "baseline-many",
                    facePriority = false,
                    seconds = seconds,
                    prompt = "hold TWO OR MORE faces in the frame",
                    waitForFaceSec = 10,
                )
                runPhase(
                    handle,
                    label = "faceprio-many",
                    facePriority = true,
                    seconds = seconds,
                    prompt = "keep TWO OR MORE faces in the frame",
                    waitForFaceSec = 10,
                )
            }
            runPhase(
                handle,
                label = "baseline-noface",
                facePriority = false,
                seconds = seconds,
                prompt = "point the camera at a scene with NO faces",
            )
            runPhase(
                handle,
                label = "faceprio-noface",
                facePriority = true,
                seconds = seconds,
                prompt = "keep the scene face-free",
            )
        } finally {
            closeHandle(handle)
        }
    }

    private fun runPhase(
        handle: Handle,
        label: String,
        facePriority: Boolean,
        seconds: Int,
        prompt: String,
        waitForFaceSec: Int = 0,
        minFaces: Int = 1,
    ): List<Sample> {
        val samples = mutableListOf<Sample>()
        val lock = Any()
        val callback = collectingCallback(samples, lock)
        val request = try {
            buildRequest(handle, facePriority, region = null, triggerStart = false)
        } catch (e: Exception) {
            log("[$label] build request FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }

        log(">>> PROMPT [$label] ${seconds}s: $prompt")
        try {
            handle.session.setRepeatingRequest(request, callback, handler)
        } catch (e: Exception) {
            log("[$label] setRepeatingRequest FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        requestFrame("$label-start")
        if (waitForFaceSec > 0) {
            if (waitForFace(label, waitForFaceSec, samples, lock, minFaces)) {
                requestFrame("$label-face")
            }
        }
        countDown(label, seconds, samples, lock)
        requestFrame("$label-end")
        Thread.sleep(200)
        runCatching { handle.session.stopRepeating() }
        logSummary(label, samples, lock)
        return samples
    }

    /**
     * Blocks until the HAL reports at least one face, or [maxSeconds] elapses. Distinguishing
     * "no face present" from "face stats never reported" is the whole point of this gate.
     */
    private fun waitForFace(
        label: String,
        maxSeconds: Int,
        samples: MutableList<Sample>,
        lock: Any,
        minFaces: Int = 1,
    ): Boolean {
        log("[$label] waiting up to ${maxSeconds}s for STATISTICS_FACES to report " +
            "${if (minFaces == 1) "a face" else "$minFaces faces"}...")
        for (elapsed in 1..maxSeconds) {
            val faces = synchronized(lock) { samples.lastOrNull()?.faceCount ?: 0 }
            if (faces >= minFaces) {
                log("[$label] face detected after ${elapsed}s (faces=$faces)")
                return true
            }
            if (elapsed % 5 == 0) {
                log("[$label] still no face after ${elapsed}s (faces=$faces)")
            }
            Thread.sleep(1_000)
        }
        log("[$label] NO face reported in ${maxSeconds}s")
        return false
    }

    /** Two-face check: the second face may be a photo held next to the operator. */
    private fun manySuite(cameraId: String, seconds: Int) {
        val handle = openHandle(cameraId) ?: return
        try {
            val baseline = runPhase(
                handle,
                label = "many-baseline",
                facePriority = false,
                seconds = seconds,
                prompt = "hold TWO faces in frame (a face photo counts)",
                waitForFaceSec = 30,
                minFaces = 2,
            )
            val maxBaseline = baseline.maxOfOrNull { it.faceCount } ?: 0
            val facePrio = runPhase(
                handle,
                label = "many-faceprio",
                facePriority = true,
                seconds = seconds,
                prompt = "keep BOTH faces in frame",
            )
            val maxFacePrio = facePrio.maxOfOrNull { it.faceCount } ?: 0
            log("")
            log("=== Multiple-face summary ===")
            log("max faces reported: baseline=$maxBaseline faceprio=$maxFacePrio")
            log("If both stay at 1, a second face was never detected in this scene.")
        } finally {
            closeHandle(handle)
        }
    }

    /**
     * Baseline until a face is detected, then alternate modes with the face held still. This
     * isolates the scene-mode variable: the face is already reported before FACE_PRIORITY is
     * switched on, so a drop to zero faces cannot be an operator-timing artefact.
     */
    private fun alternateSuite(cameraId: String, seconds: Int) {
        val handle = openHandle(cameraId) ?: return
        try {
            val baselineBlocks = mutableListOf<String>()
            val facePrioBlocks = mutableListOf<String>()

            val first = runPhase(
                handle,
                label = "alt-baseline-1",
                facePriority = false,
                seconds = seconds,
                prompt = "hold ONE face steady in the frame",
                waitForFaceSec = 60,
            )
            baselineBlocks += "alt-baseline-1=${first.count { it.faceCount > 0 }}/${first.size}"
            if (first.none { it.faceCount > 0 }) {
                log("No face was ever detected in baseline; alternation aborted.")
                log("Keep the face in frame for the whole run and retry 'alternate'.")
                return
            }

            val facePrio1 = runPhase(
                handle,
                label = "alt-faceprio-1",
                facePriority = true,
                seconds = seconds,
                prompt = "keep the face EXACTLY where it is",
            )
            facePrioBlocks += "alt-faceprio-1=${facePrio1.count { it.faceCount > 0 }}/${facePrio1.size}"

            val baseline2 = runPhase(
                handle,
                label = "alt-baseline-2",
                facePriority = false,
                seconds = seconds,
                prompt = "still the same face position",
            )
            baselineBlocks += "alt-baseline-2=${baseline2.count { it.faceCount > 0 }}/${baseline2.size}"

            val facePrio2 = runPhase(
                handle,
                label = "alt-faceprio-2",
                facePriority = true,
                seconds = seconds,
                prompt = "still the same face position",
            )
            facePrioBlocks += "alt-faceprio-2=${facePrio2.count { it.faceCount > 0 }}/${facePrio2.size}"

            log("")
            log("=== Alternation summary ===")
            log("baseline blocks (face frames/total): ${baselineBlocks.joinToString(" | ")}")
            log("faceprio blocks (face frames/total): ${facePrioBlocks.joinToString(" | ")}")
            log("Frames are saved as alt-*.jpg; check them to confirm the face stayed in view.")
            log("Reading: face frames collapsing only in the faceprio blocks means the HAL stops")
            log("reporting STATISTICS_FACES while scene mode FACE_PRIORITY is active.")
        } finally {
            closeHandle(handle)
        }
    }

    private fun probeSuite(cameraId: String, seconds: Int) {
        val handle = openHandle(cameraId) ?: return
        try {
            runPhase(
                handle,
                label = "probe-faceprio",
                facePriority = true,
                seconds = seconds,
                prompt = "hold ONE face in front of the rear camera",
                waitForFaceSec = 20,
            )
            runPhase(
                handle,
                label = "probe-baseline",
                facePriority = false,
                seconds = seconds,
                prompt = "keep the face in the same position",
                waitForFaceSec = 10,
            )
        } finally {
            closeHandle(handle)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Phase 3: tap-region precedence while FACE_PRIORITY is active
    // ---------------------------------------------------------------------------------------

    /**
     * Keep a face centred, then push a one-shot AF region into the top-left corner of the frame.
     * The corner target should be at a clearly different distance from the face. Baselines are run
     * with scene mode disabled so the region behaviour itself is known to work.
     */
    private fun regionSuite(cameraId: String, seconds: Int) {
        val handle = openHandle(cameraId) ?: return
        try {
            val face = runPhase(
                handle,
                label = "region-face-reference",
                facePriority = true,
                seconds = seconds,
                prompt = "hold ONE face centred (reference distance for the region test)",
                waitForFaceSec = 30,
            )
            val faceDistance = medianDistance(face)

            runRegionPhase(
                handle,
                label = "region-baseline",
                facePriority = false,
                seconds = seconds,
                prompt = "keep the face centred and a FAR object in the TOP-LEFT quadrant",
            )
            runRegionPhase(
                handle,
                label = "region-faceprio",
                facePriority = true,
                seconds = seconds,
                prompt = "same scene, now with FACE_PRIORITY active",
            )

            log("")
            log("Region test reading guide:")
            log("  face reference distance (FACE_PRIORITY, no region) = ${formatDistance(faceDistance)}")
            log("  if region-baseline locks near the far object, regions work without scene mode")
            log("  if region-faceprio locks at the face distance instead, FACE_PRIORITY wins")
            log("  if region-faceprio cannot lock at all, scene mode blocks region targeting")
        } finally {
            closeHandle(handle)
        }
    }

    private fun runRegionPhase(
        handle: Handle,
        label: String,
        facePriority: Boolean,
        seconds: Int,
        prompt: String,
    ) {
        val samples = mutableListOf<Sample>()
        val lock = Any()
        val callback = collectingCallback(samples, lock)
        val region = topLeftRegion(handle.activeArray)
        log(">>> PROMPT [$label] ${seconds}s: $prompt")
        log("    region=$region (top-left quadrant of the active array)")

        val armed = try {
            buildRequest(handle, facePriority, region = region, triggerStart = false)
        } catch (e: Exception) {
            log("[$label] build request FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val trigger = try {
            buildRequest(handle, facePriority, region = region, triggerStart = true)
        } catch (e: Exception) {
            log("[$label] build trigger FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        try {
            handle.session.setRepeatingRequest(armed, callback, handler)
            Thread.sleep(1_000)
            requestFrame("$label-start")
            handle.session.capture(trigger, callback, handler)
        } catch (e: Exception) {
            log("[$label] region capture FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        countDown(label, seconds, samples, lock)
        requestFrame("$label-end")
        Thread.sleep(200)
        runCatching { handle.session.stopRepeating() }
        logSummary(label, samples, lock)
    }

    // ---------------------------------------------------------------------------------------
    // Session plumbing
    // ---------------------------------------------------------------------------------------

    private data class Handle(
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val activeArray: Rect,
    )

    private fun openHandle(cameraId: String): Handle? {
        val chars = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            log("camera $cameraId characteristics FAILED: ${e.message}")
            return null
        }
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 4000, 3000)
        val size = pickSize(chars)
            ?: run {
                log("camera $cameraId: no YUV output size")
                return null
            }
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = imageReader
        // Drain and close frames: an unconsumed reader stalls the whole pipeline. When a frame is
        // requested, convert this one to JPEG first so the report can prove what the camera saw.
        imageReader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (frameRequested.compareAndSet(true, false)) {
                    saveFrame(image, "$frameName.jpg")
                }
            } finally {
                image.close()
            }
        }, handler)

        val openLatch = CountDownLatch(1)
        var device: CameraDevice? = null
        var error: String? = null
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    openLatch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    error = "disconnected"
                    openLatch.countDown()
                }

                override fun onError(camera: CameraDevice, code: Int) {
                    camera.close()
                    error = "open error $code"
                    openLatch.countDown()
                }
            }, handler)
        } catch (e: Exception) {
            log("openCamera($cameraId) threw: ${e.javaClass.simpleName}: ${e.message}")
            imageReader.close()
            return null
        }
        if (!openLatch.await(8, TimeUnit.SECONDS) || device == null) {
            log("openCamera($cameraId) failed: ${error ?: "timeout"}")
            imageReader.close()
            return null
        }
        val camera = device!!

        val sessionLatch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var sessionError: String? = null
        camera.createCaptureSession(
            listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    sessionLatch.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    sessionError = "configure failed"
                    sessionLatch.countDown()
                }
            },
            handler,
        )
        if (!sessionLatch.await(8, TimeUnit.SECONDS) || session == null) {
            log("session configure failed: ${sessionError ?: "timeout"}")
            camera.close()
            imageReader.close()
            return null
        }
        log("session ready on camera $cameraId, ${size.width}x${size.height} YUV")
        return Handle(camera, session!!, activeArray)
    }

    private fun closeHandle(handle: Handle) {
        runCatching { handle.session.stopRepeating() }
        runCatching { handle.session.close() }
        runCatching { handle.device.close() }
        runCatching { reader?.close() }
        reader = null
    }

    private fun buildRequest(
        handle: Handle,
        facePriority: Boolean,
        region: Rect?,
        triggerStart: Boolean,
    ): CaptureRequest {
        val builder = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(reader!!.surface)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, FACE_DETECT_MODE_FULL)
        if (facePriority) {
            builder.set(CaptureRequest.CONTROL_MODE, CONTROL_MODE_USE_SCENE_MODE)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_FACE_PRIORITY)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_DISABLED)
        }
        if (region != null) {
            // A tap is a one-shot: AF_MODE_AUTO + one AF_TRIGGER_START capture, like the app.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(
                CaptureRequest.CONTROL_AF_REGIONS,
                arrayOf(MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        builder.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            if (triggerStart) {
                CaptureRequest.CONTROL_AF_TRIGGER_START
            } else {
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            },
        )
        return builder.build()
    }

    // ---------------------------------------------------------------------------------------
    // Sampling and summaries
    // ---------------------------------------------------------------------------------------

    private data class Sample(
        val afState: Int?,
        val distance: Float?,
        val faceCount: Int,
        val faceScores: List<Int>,
        val controlMode: Int?,
        val sceneMode: Int?,
        val afMode: Int?,
        val faceDetectMode: Int?,
    )

    private fun collectingCallback(
        samples: MutableList<Sample>,
        lock: Any,
    ) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val faces = result.get(CaptureResult.STATISTICS_FACES)
            val sample = Sample(
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                faceCount = faces?.size ?: 0,
                faceScores = faces?.map { it.score } ?: emptyList(),
                controlMode = result.get(CaptureResult.CONTROL_MODE),
                sceneMode = result.get(CaptureResult.CONTROL_SCENE_MODE),
                afMode = result.get(CaptureResult.CONTROL_AF_MODE),
                faceDetectMode = result.get(CaptureResult.STATISTICS_FACE_DETECT_MODE),
            )
            synchronized(lock) { samples += sample }
        }
    }

    /** Waits [seconds], logging a countdown so an operator can follow along in logcat. */
    private fun countDown(
        label: String,
        seconds: Int,
        samples: MutableList<Sample>,
        lock: Any,
    ) {
        for (remaining in seconds downTo 1) {
            val (count, faces, state) = synchronized(lock) {
                val last = samples.lastOrNull()
                Triple(samples.size, last?.faceCount ?: 0, last?.afState)
            }
            log("[$label] ${remaining}s left samples=$count faces=$faces " +
                "af=${state?.let { afStateName(it) } ?: "n/a"}")
            Thread.sleep(1_000)
        }
    }

    private fun logSummary(label: String, samples: MutableList<Sample>, lock: Any) {
        val snapshot = synchronized(lock) { samples.toList() }
        if (snapshot.isEmpty()) {
            log("[$label] NO capture results")
            return
        }
        val faceFrames = snapshot.count { it.faceCount > 0 }
        val maxFaces = snapshot.maxOf { it.faceCount }
        val scores = snapshot.flatMap { it.faceScores }.distinct().sorted()
        val distances = snapshot.mapNotNull { it.distance }
        val states = snapshot.groupingBy { it.afState }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { (state, count) ->
                "${state?.let { afStateName(it) } ?: "null"}=$count"
            }

        log("[$label] samples=${snapshot.size} framesWithFaces=$faceFrames " +
            "maxFacesReported=$maxFaces faceScores=$scores")
        log("[$label] AF states: $states")
        log("[$label] LENS_FOCUS_DISTANCE: " +
            "min=${formatDistance(distances.minOrNull())} " +
            "median=${formatDistance(median(distances))} " +
            "max=${formatDistance(distances.maxOrNull())} (n=${distances.size})")
        val echoes = buildString {
            append("controlMode=")
            append(snapshot.mapNotNull { it.controlMode }.distinct().sorted())
            append(" sceneMode=")
            append(snapshot.mapNotNull { it.sceneMode }.distinct().sorted())
            append(" afMode=")
            append(snapshot.mapNotNull { it.afMode }.distinct().sorted())
            append(" faceDetectMode=")
            append(snapshot.mapNotNull { it.faceDetectMode }.distinct().sorted())
        }
        log("[$label] echo: $echoes")
    }

    private fun medianDistance(samples: List<Sample>): Float? =
        median(samples.mapNotNull { it.distance })

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun formatDistance(diopters: Float?): String {
        if (diopters == null) return "n/a"
        if (diopters <= 0.001f) return "0.000 dpt (infinity)"
        return "%.3f dpt (%.0f cm)".format(diopters, 100f / diopters)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun topLeftRegion(active: Rect): Rect {
        val w = active.width()
        val h = active.height()
        return Rect(
            active.left + w / 10,
            active.top + h / 10,
            active.left + w * 35 / 100,
            active.top + h * 35 / 100,
        )
    }

    private fun pickSize(chars: CameraCharacteristics): android.util.Size? {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        return sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.firstOrNull()
    }

    private fun charactersPhysicalIds(chars: CameraCharacteristics): List<String> = try {
        chars.physicalCameraIds.toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($facing)"
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

    private fun faceDetectModeName(mode: Int): String = when (mode) {
        STATISTICS_FACE_DETECT_MODE_OFF -> "OFF"
        STATISTICS_FACE_DETECT_MODE_SIMPLE -> "SIMPLE"
        FACE_DETECT_MODE_FULL -> "FULL"
        else -> "MODE($mode)"
    }

    private fun sceneModeName(mode: Int): String = when (mode) {
        CONTROL_SCENE_MODE_DISABLED -> "DISABLED"
        CONTROL_SCENE_MODE_FACE_PRIORITY -> "FACE_PRIORITY"
        CONTROL_SCENE_MODE_ACTION -> "ACTION"
        CONTROL_SCENE_MODE_PORTRAIT -> "PORTRAIT"
        CONTROL_SCENE_MODE_LANDSCAPE -> "LANDSCAPE"
        CONTROL_SCENE_MODE_NIGHT -> "NIGHT"
        else -> "MODE($mode)"
    }

    private fun requestFrame(name: String) {
        frameName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        frameRequested.set(true)
    }

    private fun saveFrame(image: Image, name: String) {
        try {
            val w = image.width
            val h = image.height
            val nv21 = yuv420ToNv21(image)
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(Rect(0, 0, w, h), 88, out)
            val dir = ctx.getExternalFilesDir("face_frames") ?: ctx.filesDir
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(out.toByteArray())
            log("frame saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("saveFrame($name) failed: ${e.message}")
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
            val file = File(dir, "face_priority_diagnostics.txt")
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

    companion object {
        private const val TAG = "FacePriorityDiag"

        // Local aliases keep the request code readable next to the Camera2 constants it mirrors.
        private const val CONTROL_MODE_USE_SCENE_MODE = Camera2Constants.CONTROL_MODE_USE_SCENE_MODE
        private const val CONTROL_SCENE_MODE_DISABLED = Camera2Constants.CONTROL_SCENE_MODE_DISABLED
        private const val CONTROL_SCENE_MODE_FACE_PRIORITY =
            Camera2Constants.CONTROL_SCENE_MODE_FACE_PRIORITY
        private const val CONTROL_SCENE_MODE_ACTION = Camera2Constants.CONTROL_SCENE_MODE_ACTION
        private const val CONTROL_SCENE_MODE_PORTRAIT = Camera2Constants.CONTROL_SCENE_MODE_PORTRAIT
        private const val CONTROL_SCENE_MODE_LANDSCAPE =
            Camera2Constants.CONTROL_SCENE_MODE_LANDSCAPE
        private const val CONTROL_SCENE_MODE_NIGHT = Camera2Constants.CONTROL_SCENE_MODE_NIGHT
        private const val STATISTICS_FACE_DETECT_MODE_OFF =
            Camera2Constants.STATISTICS_FACE_DETECT_MODE_OFF
        private const val STATISTICS_FACE_DETECT_MODE_SIMPLE =
            Camera2Constants.STATISTICS_FACE_DETECT_MODE_SIMPLE
        private const val FACE_DETECT_MODE_FULL = Camera2Constants.STATISTICS_FACE_DETECT_MODE_FULL
    }
}
