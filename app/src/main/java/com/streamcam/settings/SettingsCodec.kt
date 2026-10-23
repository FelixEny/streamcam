package com.streamcam.settings

import com.streamcam.camera.CameraSettings
import com.streamcam.camera.ExposureMode
import com.streamcam.camera.FocusMode
import com.streamcam.camera.WhiteBalanceMode

/**
 * Pure mapping between settings data classes and flat key/value maps. Kept free of Android types
 * so the mapping (including clamping of persisted values) is unit-testable on the JVM; the
 * DataStore glue in [SettingsRepository] only shuttles these maps.
 */
object SettingsCodec {

    fun encodeCamera(settings: CameraSettings): Map<String, Any> = mapOf(
        "wb_mode" to settings.whiteBalanceMode.name,
        "wb_lock" to settings.whiteBalanceLock,
        "kelvin" to settings.kelvin,
        "tint" to settings.tint,
        "exposure_mode" to settings.exposureMode.name,
        "ae_lock" to settings.aeLock,
        "iso" to settings.iso,
        "shutter_ns" to settings.shutterNs,
        "focus_mode" to settings.focusMode.name,
        "focus_diopters" to settings.focusDistanceDiopters,
        "zoom_ratio" to settings.zoomRatio,
        "minimal_processing" to settings.minimalProcessing,
    )

    fun decodeCamera(map: Map<String, Any?>): CameraSettings = CameraSettings(
        whiteBalanceMode = map.enum("wb_mode", WhiteBalanceMode.AUTO),
        whiteBalanceLock = map.bool("wb_lock", false),
        kelvin = map.int("kelvin", 5500).coerceIn(2000, 10000),
        tint = map.int("tint", 0).coerceIn(-50, 50),
        exposureMode = map.enum("exposure_mode", ExposureMode.AUTO),
        aeLock = map.bool("ae_lock", false),
        iso = map.int("iso", 100).coerceAtLeast(1),
        shutterNs = map.long("shutter_ns", 33_333_333L).coerceAtLeast(1L),
        focusMode = map.enum("focus_mode", FocusMode.AUTO),
        focusDistanceDiopters = map.float("focus_diopters", 0f).coerceAtLeast(0f),
        zoomRatio = map.float("zoom_ratio", 1f).coerceAtLeast(1f),
        minimalProcessing = map.bool("minimal_processing", false),
    )

    fun encodeStream(settings: StreamSettings): Map<String, Any> = mapOf(
        "bitrate_bps" to settings.bitrateBps,
        "fps" to settings.fps,
        "output_size" to settings.outputSize.name,
        "port" to settings.port,
    )

    fun decodeStream(map: Map<String, Any?>): StreamSettings = StreamSettings(
        bitrateBps = map.int("bitrate_bps", StreamSettings.DEFAULT_BITRATE_BPS)
            .coerceIn(StreamSettings.MIN_BITRATE_BPS, StreamSettings.MAX_BITRATE_BPS),
        fps = map.int("fps", StreamSettings.FPS_AUTO)
            .takeIf { it in StreamSettings.FPS_CHOICES } ?: StreamSettings.FPS_AUTO,
        outputSize = map.enum("output_size", OutputSize.AUTO),
        port = map.int("port", StreamSettings.DEFAULT_PORT)
            .coerceIn(StreamSettings.MIN_PORT, StreamSettings.MAX_PORT),
    )

    private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default

    private fun Map<String, Any?>.int(key: String, default: Int): Int = when (val v = this[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Float -> v.toInt()
        else -> default
    }

    private fun Map<String, Any?>.long(key: String, default: Long): Long = when (val v = this[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Float -> v.toLong()
        else -> default
    }

    private fun Map<String, Any?>.float(key: String, default: Float): Float = when (val v = this[key]) {
        is Float -> v
        is Int -> v.toFloat()
        is Long -> v.toFloat()
        else -> default
    }

    private inline fun <reified T : Enum<T>> Map<String, Any?>.enum(key: String, default: T): T {
        val name = this[key] as? String ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
