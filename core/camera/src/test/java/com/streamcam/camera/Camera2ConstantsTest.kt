package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class Camera2ConstantsTest {

    @Test
    fun `lens facing constants match the camera2 api`() {
        // CameraCharacteristics: LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1, LENS_FACING_EXTERNAL = 2.
        // These were previously mirrored the wrong way round, which made the app default to the
        // front camera on devices that report the documented values.
        assertEquals(0, Camera2Constants.LENS_FACING_FRONT)
        assertEquals(1, Camera2Constants.LENS_FACING_BACK)
        assertEquals(2, Camera2Constants.LENS_FACING_EXTERNAL)
    }
}
