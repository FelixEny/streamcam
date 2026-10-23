package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOrientationTest {

    private val back = Camera2Constants.LENS_FACING_BACK
    private val front = Camera2Constants.LENS_FACING_FRONT

    @Test
    fun `display correction negates the counterclockwise display rotation`() {
        assertEquals(0, PreviewOrientation.displayCorrectionDegrees(0))
        assertEquals(270, PreviewOrientation.displayCorrectionDegrees(90))
        assertEquals(180, PreviewOrientation.displayCorrectionDegrees(180))
        assertEquals(90, PreviewOrientation.displayCorrectionDegrees(270))
    }

    @Test
    fun `display correction normalises into zero until three sixty`() {
        assertEquals(270, PreviewOrientation.displayCorrectionDegrees(450))
        assertEquals(180, PreviewOrientation.displayCorrectionDegrees(540))
        assertEquals(0, PreviewOrientation.displayCorrectionDegrees(720))
    }

    @Test
    fun `back camera relative rotation subtracts display with opposite sign`() {
        // Pixel 8 Pro: back sensor 90. Display rotations are counterclockwise, so for back
        // sensors the relative rotation is sensor + display.
        assertEquals(90, PreviewOrientation.relativeRotation(90, 0, back))
        assertEquals(180, PreviewOrientation.relativeRotation(90, 90, back))
        assertEquals(270, PreviewOrientation.relativeRotation(90, 180, back))
        assertEquals(0, PreviewOrientation.relativeRotation(90, 270, back))
    }

    @Test
    fun `front camera relative rotation subtracts display directly`() {
        // Pixel 8 Pro: front sensor 270, mounted in the opposite frame.
        assertEquals(270, PreviewOrientation.relativeRotation(270, 0, front))
        assertEquals(180, PreviewOrientation.relativeRotation(270, 90, front))
        assertEquals(90, PreviewOrientation.relativeRotation(270, 180, front))
        assertEquals(0, PreviewOrientation.relativeRotation(270, 270, front))
    }

    @Test
    fun `relative rotation normalises into zero until three sixty`() {
        assertEquals(0, PreviewOrientation.relativeRotation(0, 0, back))
        assertEquals(270, PreviewOrientation.relativeRotation(180, 90, back))
        assertEquals(270, PreviewOrientation.relativeRotation(180, 450, back))
        assertEquals(90, PreviewOrientation.relativeRotation(180, 270, back))
    }

    @Test
    fun `front facing detection follows camera2 constants`() {
        assertTrue(PreviewOrientation.isFrontFacing(Camera2Constants.LENS_FACING_FRONT))
        assertFalse(PreviewOrientation.isFrontFacing(Camera2Constants.LENS_FACING_BACK))
        assertFalse(PreviewOrientation.isFrontFacing(Camera2Constants.LENS_FACING_EXTERNAL))
    }
}
