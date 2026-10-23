package com.streamcam.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Android adapter: reads [CameraCharacteristics] for a camera id and converts the fields this
 * app uses into a [RawCameraCharacteristics] (plain types), which [CapabilityParser] turns
 * into [CameraCapabilities].
 */
class CapabilityProbe(private val cameraManager: CameraManager) {

    fun listCameraIds(): List<String> = cameraManager.cameraIdList.toList()

    fun probe(cameraId: String): CameraCapabilities {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        return CapabilityParser.parse(cameraId, buildRaw(cameraId, chars))
    }

    private fun buildRaw(cameraId: String, chars: CameraCharacteristics): RawCameraCharacteristics {
        val physicalIds = if (Build.VERSION.SDK_INT >= 28) {
            chars.physicalCameraIds.toList()
        } else {
            emptyList()
        }

        val focalLengths = buildMap {
            chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
                ?.let { put(cameraId, it) }
            for (physicalId in physicalIds) {
                val physicalChars = try {
                    cameraManager.getCameraCharacteristics(physicalId)
                } catch (_: Exception) {
                    null
                }
                physicalChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
                    ?.let { put(physicalId, it) }
            }
        }

        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val streamSizes = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.map { IntSize(it.width, it.height) }
            .orEmpty()

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.mapNotNull { range -> range?.let { FpsRange(it.lower, it.upper) } }
            .orEmpty()

        val sensitivity = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTime = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val zoomRatioRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        return RawCameraCharacteristics(
            lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: Camera2Constants.LENS_FACING_BACK,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: 0,
            physicalCameraIds = physicalIds,
            focalLengths = focalLengths,
            streamSizes = streamSizes,
            aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                ?.toSet().orEmpty(),
            aeTargetFpsRanges = fpsRanges,
            aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
            sensitivityRangeMin = sensitivity?.lower ?: 0,
            sensitivityRangeMax = sensitivity?.upper ?: 0,
            exposureTimeRangeMinNs = exposureTime?.lower ?: 0L,
            exposureTimeRangeMaxNs = exposureTime?.upper ?: 0L,
            awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toSet().orEmpty(),
            awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false,
            manualPostProcessing = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                ?: false,
            colorCorrectionModes = readColorCorrectionModes(chars),
            cctKelvinMin = readCctKelvinMin(chars),
            cctKelvinMax = readCctKelvinMax(chars),
            afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.toSet().orEmpty(),
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            zoomRatioRangeMin = zoomRatioRange?.lower,
            zoomRatioRangeMax = zoomRatioRange?.upper,
            maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            activeArrayLeft = activeArray?.left ?: 0,
            activeArrayTop = activeArray?.top ?: 0,
            activeArrayRight = activeArray?.right ?: 0,
            activeArrayBottom = activeArray?.bottom ?: 0,
            noiseReductionModes = chars.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            )?.toSet().orEmpty(),
            edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.toSet().orEmpty(),
            faceDetectModes = chars.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
            )?.toSet().orEmpty(),
            sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.toSet().orEmpty(),
            videoStabilizationModes = chars.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
            )?.toSet().orEmpty(),
            aberrationModes = chars.get(
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            )?.toSet().orEmpty(),
        )
    }

    /**
     * COLOR_CORRECTION_AVAILABLE_MODES, including the CCT entry on devices that support API 36
     * native white balance. Empty below API 36 or when the device does not report the key, so
     * callers never see a CCT capability that is not real.
     */
    private fun readColorCorrectionModes(chars: CameraCharacteristics): Set<Int> {
        if (Build.VERSION.SDK_INT < 36) return emptySet()
        return chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
            ?.toSet()
            .orEmpty()
    }

    private fun readCctKelvinMin(chars: CameraCharacteristics): Int? {
        if (Build.VERSION.SDK_INT < 36) return null
        return chars.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)?.lower
    }

    private fun readCctKelvinMax(chars: CameraCharacteristics): Int? {
        if (Build.VERSION.SDK_INT < 36) return null
        return chars.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)?.upper
    }
}
