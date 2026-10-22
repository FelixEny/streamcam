package com.streamcam.imageproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingPolicyTest {

    @Test
    fun `selects OFF for every control when supported`() {
        val sel = ProcessingPolicy.minimalProcessingSelection(
            availableNoiseReductionModes = setOf(0, 1, 2),
            availableEdgeModes = setOf(0, 1, 2, 3),
            availableFaceDetectModes = setOf(0, 1, 2),
            availableSceneModes = setOf(0, 1, 2),
            availableVideoStabilizationModes = setOf(0, 1, 2),
            availableAberrationModes = setOf(0, 1, 2),
        )
        assertEquals(0, sel.noiseReductionMode)
        assertEquals(0, sel.edgeMode)
        assertEquals(0, sel.faceDetectMode)
        assertEquals(0, sel.sceneMode)
        assertEquals(0, sel.videoStabilizationMode)
        assertEquals(0, sel.aberrationMode)
    }

    @Test
    fun `leaves control at default when OFF unsupported`() {
        val sel = ProcessingPolicy.minimalProcessingSelection(
            availableNoiseReductionModes = setOf(1, 2),
            availableEdgeModes = setOf(1, 2),
            availableFaceDetectModes = setOf(1),
            availableSceneModes = setOf(1, 2),
            availableVideoStabilizationModes = setOf(1),
            availableAberrationModes = setOf(1, 2),
        )
        assertNull(sel.noiseReductionMode)
        assertNull(sel.edgeMode)
        assertNull(sel.faceDetectMode)
        assertNull(sel.sceneMode)
        assertNull(sel.videoStabilizationMode)
        assertNull(sel.aberrationMode)
    }

    @Test
    fun `mix of supported and unsupported`() {
        val sel = ProcessingPolicy.minimalProcessingSelection(
            availableNoiseReductionModes = setOf(0, 1),
            availableEdgeModes = setOf(1),
            availableFaceDetectModes = emptySet(),
            availableSceneModes = setOf(0),
            availableVideoStabilizationModes = setOf(0, 1),
            availableAberrationModes = setOf(1),
        )
        assertEquals(0, sel.noiseReductionMode)
        assertNull(sel.edgeMode)
        assertNull(sel.faceDetectMode)
        assertEquals(0, sel.sceneMode)
        assertEquals(0, sel.videoStabilizationMode)
        assertNull(sel.aberrationMode)
    }

    @Test
    fun `all empty yields all defaults`() {
        val sel = ProcessingPolicy.minimalProcessingSelection(
            availableNoiseReductionModes = emptySet(),
            availableEdgeModes = emptySet(),
            availableFaceDetectModes = emptySet(),
            availableSceneModes = emptySet(),
            availableVideoStabilizationModes = emptySet(),
            availableAberrationModes = emptySet(),
        )
        assertNull(sel.noiseReductionMode)
        assertNull(sel.edgeMode)
        assertNull(sel.faceDetectMode)
        assertNull(sel.sceneMode)
        assertNull(sel.videoStabilizationMode)
        assertNull(sel.aberrationMode)
    }
}
