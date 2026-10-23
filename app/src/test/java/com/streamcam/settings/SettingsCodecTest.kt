package com.streamcam.settings

import com.streamcam.camera.CameraSettings
import com.streamcam.camera.ExposureMode
import com.streamcam.camera.FocusMode
import com.streamcam.camera.WhiteBalanceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `camera settings round-trip`() {
        val settings = CameraSettings(
            whiteBalanceMode = WhiteBalanceMode.MANUAL,
            whiteBalanceLock = true,
            kelvin = 3200,
            tint = -25,
            exposureMode = ExposureMode.MANUAL,
            aeLock = true,
            iso = 400,
            shutterNs = 8_000_000L,
            focusMode = FocusMode.MANUAL,
            focusDistanceDiopters = 2.5f,
            zoomRatio = 1.8f,
            minimalProcessing = true,
        )
        assertEquals(settings, SettingsCodec.decodeCamera(SettingsCodec.encodeCamera(settings)))
    }

    @Test
    fun `camera defaults decode from empty map`() {
        assertEquals(CameraSettings(), SettingsCodec.decodeCamera(emptyMap()))
    }

    @Test
    fun `camera decode tolerates unknown enum names and wrong types`() {
        val decoded = SettingsCodec.decodeCamera(
            mapOf(
                "wb_mode" to "SOMETHING_ELSE",
                "exposure_mode" to 42,
                "kelvin" to "not a number",
                "ae_lock" to "true",
                "iso" to 200L,
            ),
        )
        assertEquals(WhiteBalanceMode.AUTO, decoded.whiteBalanceMode)
        assertEquals(ExposureMode.AUTO, decoded.exposureMode)
        assertEquals(5500, decoded.kelvin)
        assertEquals(false, decoded.aeLock)
        assertEquals(200, decoded.iso)
    }

    @Test
    fun `camera decode clamps out-of-range values`() {
        val decoded = SettingsCodec.decodeCamera(
            mapOf(
                "kelvin" to 50_000,
                "tint" to -500,
                "iso" to 0,
                "shutter_ns" to -1L,
                "focus_diopters" to -3f,
                "zoom_ratio" to 0.2f,
            ),
        )
        assertEquals(10000, decoded.kelvin)
        assertEquals(-50, decoded.tint)
        assertEquals(1, decoded.iso)
        assertEquals(1L, decoded.shutterNs)
        assertEquals(0f, decoded.focusDistanceDiopters)
        assertEquals(1f, decoded.zoomRatio)
    }

    @Test
    fun `stream settings round-trip`() {
        val settings = StreamSettings(
            bitrateBps = 4_500_000,
            fps = 24,
            outputSize = OutputSize.P720,
            port = 9000,
        )
        assertEquals(settings, SettingsCodec.decodeStream(SettingsCodec.encodeStream(settings)))
    }

    @Test
    fun `stream defaults decode from empty map`() {
        assertEquals(StreamSettings(), SettingsCodec.decodeStream(emptyMap()))
    }

    @Test
    fun `stream decode clamps bitrate and port and rejects unknown fps`() {
        val decoded = SettingsCodec.decodeStream(
            mapOf(
                "bitrate_bps" to 100_000_000,
                "fps" to 17,
                "output_size" to "GARBAGE",
                "port" to 50,
            ),
        )
        assertEquals(StreamSettings.MAX_BITRATE_BPS, decoded.bitrateBps)
        assertEquals(StreamSettings.FPS_AUTO, decoded.fps)
        assertEquals(OutputSize.AUTO, decoded.outputSize)
        assertEquals(StreamSettings.MIN_PORT, decoded.port)
    }

    @Test
    fun `stream decode clamps low bitrate and high port`() {
        val decoded = SettingsCodec.decodeStream(
            mapOf("bitrate_bps" to 100, "port" to 999_999),
        )
        assertEquals(StreamSettings.MIN_BITRATE_BPS, decoded.bitrateBps)
        assertEquals(StreamSettings.MAX_PORT, decoded.port)
    }
}
