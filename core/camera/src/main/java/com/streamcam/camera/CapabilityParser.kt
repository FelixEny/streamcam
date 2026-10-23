package com.streamcam.camera

import com.streamcam.imageproc.IntRect

object CapabilityParser {
    fun parse(cameraId: String, raw: RawCameraCharacteristics): CameraCapabilities {
        val zoomRange = if (raw.zoomRatioRangeMin != null && raw.zoomRatioRangeMax != null) {
            raw.zoomRatioRangeMin..raw.zoomRatioRangeMax
        } else {
            null
        }
        val cctRange = if (raw.cctKelvinMin != null && raw.cctKelvinMax != null) {
            raw.cctKelvinMin..raw.cctKelvinMax
        } else {
            null
        }
        return CameraCapabilities(
            cameraId = cameraId,
            lensFacing = raw.lensFacing,
            sensorOrientation = raw.sensorOrientation,
            physicalCameraIds = raw.physicalCameraIds,
            focalLengthsByCamera = raw.focalLengths,
            hardwareLevel = raw.hardwareLevel,
            streamSizes = raw.streamSizes,
            aeModes = raw.aeModes,
            aeTargetFpsRanges = raw.aeTargetFpsRanges,
            aeLockAvailable = raw.aeLockAvailable,
            sensitivityRange = raw.sensitivityRangeMin..raw.sensitivityRangeMax,
            exposureTimeRange = raw.exposureTimeRangeMinNs..raw.exposureTimeRangeMaxNs,
            awbModes = raw.awbModes,
            awbLockAvailable = raw.awbLockAvailable,
            manualPostProcessing = raw.manualPostProcessing,
            colorCorrectionModes = raw.colorCorrectionModes,
            cctKelvinRange = cctRange,
            afModes = raw.afModes,
            minFocusDistance = raw.minFocusDistance,
            zoomRatioRange = zoomRange,
            maxDigitalZoom = raw.maxDigitalZoom.coerceAtLeast(1f),
            activeArraySize = IntRect(
                raw.activeArrayLeft,
                raw.activeArrayTop,
                raw.activeArrayRight,
                raw.activeArrayBottom,
            ),
            noiseReductionModes = raw.noiseReductionModes,
            edgeModes = raw.edgeModes,
            faceDetectModes = raw.faceDetectModes,
            sceneModes = raw.sceneModes,
            videoStabilizationModes = raw.videoStabilizationModes,
            aberrationModes = raw.aberrationModes,
        )
    }
}
