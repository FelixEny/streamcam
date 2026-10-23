package com.streamcam.camera

import com.streamcam.imageproc.IntRect
import com.streamcam.imageproc.RggbGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPlannerTest {

    private fun caps(
        awbModes: Set<Int> = setOf(0, 1),
        awbLockAvailable: Boolean = true,
        manualPostProcessing: Boolean = true,
        colorCorrectionModes: Set<Int> = emptySet(),
        cctKelvinRange: IntRange? = null,
        aeModes: Set<Int> = setOf(0, 1),
        afModes: Set<Int> = setOf(0, 1, 3, 4),
        zoomRatioRange: ClosedFloatingPointRange<Float>? = 1f..10f,
        maxDigitalZoom: Float = 10f,
        minFocusDistance: Float = 0f,
        sensitivity: IntRange = 50..6400,
        exposure: LongRange = 1_000L..1_000_000_000L,
        noiseReduction: Set<Int> = setOf(0, 1, 2),
        edge: Set<Int> = setOf(0, 1, 2, 3),
        faceDetect: Set<Int> = setOf(0, 1, 2),
        scene: Set<Int> = setOf(0, 1),
        videoStab: Set<Int> = setOf(0, 1),
        aberration: Set<Int> = setOf(0, 1, 2),
    ) = CameraCapabilities(
        cameraId = "0",
        lensFacing = Camera2Constants.LENS_FACING_BACK,
        physicalCameraIds = emptyList(),
        focalLengthsByCamera = mapOf("0" to 6.81f),
        hardwareLevel = 2,
        streamSizes = listOf(IntSize(1920, 1080), IntSize(1280, 720)),
        aeModes = aeModes,
        aeTargetFpsRanges = listOf(FpsRange(15, 30), FpsRange(30, 30)),
        aeLockAvailable = true,
        sensitivityRange = sensitivity,
        exposureTimeRange = exposure,
        awbModes = awbModes,
        awbLockAvailable = awbLockAvailable,
        manualPostProcessing = manualPostProcessing,
        colorCorrectionModes = colorCorrectionModes,
        cctKelvinRange = cctKelvinRange,
        afModes = afModes,
        minFocusDistance = minFocusDistance,
        zoomRatioRange = zoomRatioRange,
        maxDigitalZoom = maxDigitalZoom,
        activeArraySize = IntRect(0, 0, 4000, 3000),
        noiseReductionModes = noiseReduction,
        edgeModes = edge,
        faceDetectModes = faceDetect,
        sceneModes = scene,
        videoStabilizationModes = videoStab,
        aberrationModes = aberration,
    )

    private fun cctCaps(
        kelvinRange: IntRange = 2500..10000,
        awbModes: Set<Int> = setOf(0, 1),
    ) = caps(
        awbModes = awbModes,
        colorCorrectionModes = setOf(
            Camera2Constants.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            1,
            2,
            Camera2Constants.COLOR_CORRECTION_MODE_CCT,
        ),
        cctKelvinRange = kelvinRange,
    )

    private val halReference = ManualWbReference(
        colorTransform = HAL_TRANSFORM,
        gains = RggbGains(red = 1f, greenEven = 1f, greenOdd = 1f, blue = 1f),
        kelvin = 5500,
    )

    @Test
    fun `default settings produce auto controls and unity zoom`() {
        val ops = RequestPlanner.plan(CameraSettings(), caps(), targetFps = FpsRange(30, 30))
        assertTrue(RequestOp.SetAeTargetFpsRange(30, 30) in ops)
        assertTrue(RequestOp.SetAeMode(Camera2Constants.AE_MODE_ON) in ops)
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO) in ops)
        assertTrue(RequestOp.SetAfMode(Camera2Constants.AF_MODE_CONTINUOUS_VIDEO) in ops)
        assertTrue(RequestOp.SetZoomRatio(1f) in ops)
        assertTrue(ops.none { it is RequestOp.SetNoiseReductionMode })
        assertTrue(ops.none { it is RequestOp.SetSensorSensitivity })
    }

    @Test
    fun `manual white balance uses learned transform plus relative gains`() {
        val settings = CameraSettings(
            whiteBalanceMode = WhiteBalanceMode.MANUAL,
            kelvin = 2700,
        )
        val ops = RequestPlanner.plan(settings, caps(), manualWb = halReference)
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_OFF) in ops)
        assertTrue(RequestOp.SetAwbLock(false) in ops)
        assertTrue(
            RequestOp.SetColorCorrectionMode(
                Camera2Constants.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            ) in ops,
        )
        assertTrue(RequestOp.SetColorCorrectionTransform(HAL_TRANSFORM) in ops)
        val gains = ops.filterIsInstance<RequestOp.SetColorCorrectionGains>().single().gains
        assertTrue("expected blue > red for 2700K", gains.blue > gains.red)
        assertEquals(gains.greenEven, gains.greenOdd, 0f)
    }

    @Test
    fun `manual gains at reference kelvin equal the hal reference gains`() {
        val reference = halReference.copy(
            gains = RggbGains(red = 1.3f, greenEven = 1.4f, greenOdd = 1.4f, blue = 2.2f),
        )
        val settings = CameraSettings(
            whiteBalanceMode = WhiteBalanceMode.MANUAL,
            kelvin = reference.kelvin,
        )
        val ops = RequestPlanner.plan(settings, caps(), manualWb = reference)
        val gains = ops.filterIsInstance<RequestOp.SetColorCorrectionGains>().single().gains
        assertEquals(reference.gains.red, gains.red, 1e-3f)
        assertEquals(reference.gains.greenEven, gains.greenEven, 1e-3f)
        assertEquals(reference.gains.blue, gains.blue, 1e-3f)
    }

    @Test
    fun `manual white balance stays auto until a reference is frozen`() {
        val settings = CameraSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL)
        val ops = RequestPlanner.plan(settings, caps(), manualWb = null)
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO) in ops)
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionGains })
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionMode })
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionTransform })
    }

    @Test
    fun `manual white balance stays auto without manual post processing support`() {
        val settings = CameraSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL)
        val ops = RequestPlanner.plan(
            settings,
            caps(manualPostProcessing = false),
            manualWb = halReference,
        )
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO) in ops)
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionGains })
    }

    @Test
    fun `manual white balance falls back to auto when unsupported`() {
        val settings = CameraSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL)
        val ops = RequestPlanner.plan(
            settings,
            caps(awbModes = setOf(1)),
            manualWb = halReference,
        )
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO) in ops)
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionGains })
    }

    @Test
    fun `auto white balance never sends a color transform`() {
        val ops = RequestPlanner.plan(CameraSettings(), caps(), manualWb = halReference)
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO) in ops)
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionTransform })
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionMode })
    }

    @Test
    fun `cct mode uses native kelvin and tint when supported`() {
        val settings = CameraSettings(
            whiteBalanceMode = WhiteBalanceMode.MANUAL,
            kelvin = 3200,
            tint = 15,
        )
        val ops = RequestPlanner.plan(settings, cctCaps(), manualWb = halReference)
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_OFF) in ops)
        assertTrue(RequestOp.SetAwbLock(false) in ops)
        assertTrue(
            RequestOp.SetColorCorrectionMode(Camera2Constants.COLOR_CORRECTION_MODE_CCT) in ops,
        )
        assertTrue(RequestOp.SetColorTemperature(3200) in ops)
        assertTrue(RequestOp.SetColorTint(15) in ops)
        // CCT mode must not send manual gains or a transform; the HAL computes them.
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionGains })
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionTransform })
    }

    @Test
    fun `cct mode clamps kelvin and tint to device ranges`() {
        val settings = CameraSettings(
            whiteBalanceMode = WhiteBalanceMode.MANUAL,
            kelvin = 1200,
            tint = -99,
        )
        val ops = RequestPlanner.plan(settings, cctCaps(kelvinRange = 2500..10000))
        assertTrue(RequestOp.SetColorTemperature(2500) in ops)
        assertTrue(RequestOp.SetColorTint(Camera2Constants.MIN_COLOR_TINT) in ops)
    }

    @Test
    fun `cct mode wins over the fallback path when both are available`() {
        val settings = CameraSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL, kelvin = 5600)
        val ops = RequestPlanner.plan(settings, cctCaps(), manualWb = halReference)
        assertTrue(
            ops.filterIsInstance<RequestOp.SetColorCorrectionMode>()
                .single()
                .mode == Camera2Constants.COLOR_CORRECTION_MODE_CCT,
        )
        assertTrue(ops.none { it is RequestOp.SetColorCorrectionGains })
    }

    @Test
    fun `cct unsupported without awb off`() {
        val settings = CameraSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL)
        val ops = RequestPlanner.plan(
            settings,
            cctCaps(awbModes = setOf(1)),
            manualWb = halReference,
        )
        assertTrue(ops.none { it is RequestOp.SetColorTemperature })
        // The fallback also needs AWB_MODE_OFF, so the planner stays on auto entirely.
        assertTrue(RequestOp.SetAwbMode(Camera2Constants.AWB_MODE_AUTO) in ops)
    }

    @Test
    fun `manual exposure sets off mode, iso and shutter`() {
        val settings = CameraSettings(
            exposureMode = ExposureMode.MANUAL,
            iso = 400,
            shutterNs = 10_000_000L,
        )
        val ops = RequestPlanner.plan(settings, caps())
        assertTrue(RequestOp.SetAeMode(Camera2Constants.AE_MODE_OFF) in ops)
        assertTrue(RequestOp.SetSensorSensitivity(400) in ops)
        assertTrue(RequestOp.SetSensorExposureTime(10_000_000L) in ops)
    }

    @Test
    fun `manual exposure clamps iso and shutter to device ranges`() {
        val settings = CameraSettings(
            exposureMode = ExposureMode.MANUAL,
            iso = 100_000,
            shutterNs = 100L,
        )
        val ops = RequestPlanner.plan(settings, caps())
        assertTrue(RequestOp.SetSensorSensitivity(6400) in ops)
        assertTrue(RequestOp.SetSensorExposureTime(1_000L) in ops)
    }

    @Test
    fun `manual exposure falls back to auto when unsupported`() {
        val settings = CameraSettings(exposureMode = ExposureMode.MANUAL)
        val ops = RequestPlanner.plan(settings, caps(aeModes = setOf(1)))
        assertTrue(RequestOp.SetAeMode(Camera2Constants.AE_MODE_ON) in ops)
        assertTrue(ops.none { it is RequestOp.SetSensorSensitivity })
    }

    @Test
    fun `manual focus uses off mode and diopters`() {
        val settings = CameraSettings(
            focusMode = FocusMode.MANUAL,
            focusDistanceDiopters = 5f,
        )
        val ops = RequestPlanner.plan(settings, caps(minFocusDistance = 10f))
        assertTrue(RequestOp.SetAfMode(Camera2Constants.AF_MODE_OFF) in ops)
        assertTrue(RequestOp.SetLensFocusDistance(5f) in ops)
    }

    @Test
    fun `manual focus clamps diopters and falls back when fixed focus`() {
        val clamped = RequestPlanner.plan(
            CameraSettings(focusMode = FocusMode.MANUAL, focusDistanceDiopters = 50f),
            caps(minFocusDistance = 10f),
        )
        assertTrue(RequestOp.SetLensFocusDistance(10f) in clamped)

        val fallback = RequestPlanner.plan(
            CameraSettings(focusMode = FocusMode.MANUAL),
            caps(minFocusDistance = 0f),
        )
        assertTrue(RequestOp.SetAfMode(Camera2Constants.AF_MODE_CONTINUOUS_VIDEO) in fallback)
        assertTrue(fallback.none { it is RequestOp.SetLensFocusDistance })
    }

    @Test
    fun `zoom uses ratio when range available and clamps it`() {
        val ops = RequestPlanner.plan(CameraSettings(zoomRatio = 99f), caps())
        assertTrue(RequestOp.SetZoomRatio(10f) in ops)
        assertTrue(ops.none { it is RequestOp.SetScalerCropRegion })
    }

    @Test
    fun `zoom falls back to crop region without ratio support`() {
        val ops = RequestPlanner.plan(
            CameraSettings(zoomRatio = 2f),
            caps(zoomRatioRange = null, maxDigitalZoom = 4f),
        )
        val crop = ops.filterIsInstance<RequestOp.SetScalerCropRegion>().single().rect
        assertEquals(2000, crop.width)
        assertEquals(1500, crop.height)
        assertTrue(ops.none { it is RequestOp.SetZoomRatio })
    }

    @Test
    fun `unity zoom emits no crop region on fallback path`() {
        val ops = RequestPlanner.plan(
            CameraSettings(zoomRatio = 1f),
            caps(zoomRatioRange = null, maxDigitalZoom = 4f),
        )
        assertTrue(ops.none { it is RequestOp.SetScalerCropRegion })
    }

    @Test
    fun `minimal processing emits supported off modes`() {
        val ops = RequestPlanner.plan(CameraSettings(minimalProcessing = true), caps())
        assertTrue(RequestOp.SetNoiseReductionMode(0) in ops)
        assertTrue(RequestOp.SetEdgeMode(0) in ops)
        assertTrue(RequestOp.SetFaceDetectMode(0) in ops)
        assertTrue(RequestOp.SetSceneMode(0) in ops)
        assertTrue(RequestOp.SetVideoStabilizationMode(0) in ops)
        assertTrue(RequestOp.SetAberrationMode(0) in ops)
    }

    @Test
    fun `minimal processing skips unsupported off modes`() {
        val ops = RequestPlanner.plan(
            CameraSettings(minimalProcessing = true),
            caps(noiseReduction = setOf(1, 2), edge = setOf(1)),
        )
        assertTrue(ops.none { it is RequestOp.SetNoiseReductionMode })
        assertTrue(ops.none { it is RequestOp.SetEdgeMode })
        assertTrue(RequestOp.SetFaceDetectMode(0) in ops)
    }

    @Test
    fun `choose target fps prefers exact 30`() {
        val ranges = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(24, 30))
        assertEquals(FpsRange(30, 30), RequestPlanner.chooseTargetFps(ranges))
    }

    @Test
    fun `choose target fps picks narrowest range containing 30`() {
        val ranges = listOf(FpsRange(15, 60), FpsRange(24, 30))
        assertEquals(FpsRange(24, 30), RequestPlanner.chooseTargetFps(ranges))
    }

    @Test
    fun `choose target fps falls back to closest max`() {
        assertEquals(FpsRange(24, 24), RequestPlanner.chooseTargetFps(listOf(FpsRange(24, 24))))
        assertEquals(null, RequestPlanner.chooseTargetFps(emptyList()))
    }

    private companion object {
        /** Representative sensor->sRGB matrix layout (9 numerator/denominator pairs). */
        val HAL_TRANSFORM = listOf(
            1, 1, 0, 1, 0, 1,
            0, 1, 1, 1, 0, 1,
            0, 1, 0, 1, 1, 1,
        )
    }
}
