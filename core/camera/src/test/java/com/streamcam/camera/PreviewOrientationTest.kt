package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOrientationTest {

    @Test
    fun `back camera in landscape needs no rotation`() {
        // Pixel 8 Pro: back sensor 90, display ROTATION_90 (landscape locked).
        assertEquals(0, PreviewOrientation.relativeRotation(90, 90))
    }

    @Test
    fun `front camera in landscape needs 180 degrees`() {
        // Pixel 8 Pro: front sensor 270, display ROTATION_90.
        assertEquals(180, PreviewOrientation.relativeRotation(270, 90))
    }

    @Test
    fun `rotation is normalised into zero until three sixty`() {
        assertEquals(270, PreviewOrientation.relativeRotation(0, 90))
        assertEquals(180, PreviewOrientation.relativeRotation(270, 90))
        assertEquals(0, PreviewOrientation.relativeRotation(90, 450))
        assertEquals(90, PreviewOrientation.relativeRotation(180, 90))
    }

    @Test
    fun `portrait display rotation of zero is identity for back sensors`() {
        assertEquals(90, PreviewOrientation.relativeRotation(90, 0))
    }

    @Test
    fun `front facing detection follows camera2 constants`() {
        assertTrue(PreviewOrientation.isFrontFacing(Camera2Constants.LENS_FACING_FRONT))
        assertFalse(PreviewOrientation.isFrontFacing(Camera2Constants.LENS_FACING_BACK))
    }
}
