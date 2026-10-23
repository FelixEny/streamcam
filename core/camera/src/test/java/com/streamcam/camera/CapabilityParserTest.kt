package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityParserTest {

    @Test
    fun `maps all fields`() {
        val raw = RawCameraCharacteristics(
            lensFacing = 0,
            hardwareLevel = 2,
            physicalCameraIds = listOf("2", "3"),
            focalLengths = mapOf("0" to 6.81f, "2" to 2.4f, "3" to 6.81f),
            streamSizes = listOf(IntSize(1920, 1080), IntSize(1280, 720)),
            aeModes = setOf(0, 1),
            aeTargetFpsRanges = listOf(FpsRange(30, 30)),
            aeLockAvailable = true,
            sensitivityRangeMin = 50,
            sensitivityRangeMax = 6400,
            exposureTimeRangeMinNs = 1000L,
            exposureTimeRangeMaxNs = 1_000_000_000L,
            awbModes = setOf(0, 1),
            awbLockAvailable = true,
            afModes = setOf(0, 1, 3, 4),
            minFocusDistance = 10f,
            zoomRatioRangeMin = 1f,
            zoomRatioRangeMax = 8f,
            maxDigitalZoom = 8f,
            activeArrayLeft = 0,
            activeArrayTop = 0,
            activeArrayRight = 4000,
            activeArrayBottom = 3000,
            noiseReductionModes = setOf(0, 1, 2),
            edgeModes = setOf(0, 1),
            faceDetectModes = setOf(0, 1),
            sceneModes = setOf(0, 1),
            videoStabilizationModes = setOf(0, 1),
            aberrationModes = setOf(0, 1),
        )
        val caps = CapabilityParser.parse("0", raw)

        assertEquals("0", caps.cameraId)
        assertEquals(0, caps.lensFacing)
        assertEquals(listOf("2", "3"), caps.physicalCameraIds)
        assertEquals(6.81f, caps.focalLengthsByCamera.getValue("0"), 0f)
        assertEquals(listOf(IntSize(1920, 1080), IntSize(1280, 720)), caps.streamSizes)
        assertEquals(setOf(0, 1), caps.aeModes)
        assertEquals(listOf(FpsRange(30, 30)), caps.aeTargetFpsRanges)
        assertEquals(true, caps.aeLockAvailable)
        assertEquals(50..6400, caps.sensitivityRange)
        assertEquals(1000L..1_000_000_000L, caps.exposureTimeRange)
        assertEquals(true, caps.awbLockAvailable)
        assertEquals(10f, caps.minFocusDistance, 0f)
        assertEquals(1f..8f, caps.zoomRatioRange)
        assertEquals(8f, caps.maxZoom, 0f)
        assertEquals(4000, caps.activeArraySize.width)
        assertEquals(3000, caps.activeArraySize.height)
        assertEquals(setOf(0, 1, 2), caps.noiseReductionModes)
        assertEquals(true, caps.supportsManualFocus)
        assertEquals(true, caps.supportsZoomRatio)
        assertEquals(true, caps.hasStream(1920, 1080))
        assertEquals(false, caps.hasStream(3840, 2160))
    }

    @Test
    fun `zoom range null when either bound missing`() {
        val base = RawCameraCharacteristics(lensFacing = 0, hardwareLevel = 2)
        assertNull(CapabilityParser.parse("0", base.copy(zoomRatioRangeMin = 1f)).zoomRatioRange)
        assertNull(CapabilityParser.parse("0", base.copy(zoomRatioRangeMax = 8f)).zoomRatioRange)
        assertNull(CapabilityParser.parse("0", base).zoomRatioRange)
    }

    @Test
    fun `max digital zoom coerced to at least one`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(lensFacing = 0, hardwareLevel = 2, maxDigitalZoom = 0.5f),
        )
        assertEquals(1f, caps.maxZoom, 0f)
    }

    @Test
    fun `max zoom prefers zoom ratio range over digital zoom`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(
                lensFacing = 0,
                hardwareLevel = 2,
                zoomRatioRangeMin = 1f,
                zoomRatioRangeMax = 4f,
                maxDigitalZoom = 10f,
            ),
        )
        assertEquals(4f, caps.maxZoom, 0f)
    }

    @Test
    fun `manual focus unsupported when min focus distance is zero`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(lensFacing = 0, hardwareLevel = 2, minFocusDistance = 0f),
        )
        assertEquals(false, caps.supportsManualFocus)
    }

    @Test
    fun `cct range parsed when both bounds present and mode advertised`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(
                lensFacing = 0,
                hardwareLevel = 2,
                awbModes = setOf(0, 1),
                colorCorrectionModes = setOf(0, 1, 2, 3),
                cctKelvinMin = 2500,
                cctKelvinMax = 10000,
            ),
        )
        assertEquals(2500..10000, caps.cctKelvinRange)
        assertEquals(setOf(0, 1, 2, 3), caps.colorCorrectionModes)
        assertEquals(true, caps.supportsCctWhiteBalance)
    }

    @Test
    fun `cct range null when either bound missing`() {
        val base = RawCameraCharacteristics(lensFacing = 0, hardwareLevel = 2, awbModes = setOf(0, 1))
        assertNull(CapabilityParser.parse("0", base.copy(cctKelvinMin = 2500)).cctKelvinRange)
        assertNull(CapabilityParser.parse("0", base.copy(cctKelvinMax = 10000)).cctKelvinRange)
        assertNull(CapabilityParser.parse("0", base).cctKelvinRange)
    }

    @Test
    fun `cct unsupported when mode not in available modes`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(
                lensFacing = 0,
                hardwareLevel = 2,
                awbModes = setOf(0, 1),
                colorCorrectionModes = setOf(0, 1, 2),
                cctKelvinMin = 2500,
                cctKelvinMax = 10000,
            ),
        )
        assertEquals(false, caps.supportsCctWhiteBalance)
    }

    @Test
    fun `cct unsupported without awb off`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(
                lensFacing = 0,
                hardwareLevel = 2,
                awbModes = setOf(1),
                colorCorrectionModes = setOf(0, 1, 2, 3),
                cctKelvinMin = 2500,
                cctKelvinMax = 10000,
            ),
        )
        assertEquals(false, caps.supportsCctWhiteBalance)
    }

    @Test
    fun `cct unsupported when device reports nothing`() {
        val caps = CapabilityParser.parse(
            "0",
            RawCameraCharacteristics(lensFacing = 0, hardwareLevel = 2, awbModes = setOf(0, 1)),
        )
        assertEquals(false, caps.supportsCctWhiteBalance)
    }
}
