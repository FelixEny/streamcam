package com.streamcam.camera

import com.streamcam.imageproc.IntRect

data class FpsRange(val min: Int, val max: Int) {
    fun contains(fps: Int): Boolean = fps in min..max
}

data class IntSize(val width: Int, val height: Int)

data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: Int,
    val sensorOrientation: Int = 0,
    val physicalCameraIds: List<String>,
    val focalLengthsByCamera: Map<String, Float>,
    val hardwareLevel: Int,
    val streamSizes: List<IntSize>,
    val aeModes: Set<Int>,
    val aeTargetFpsRanges: List<FpsRange>,
    val aeLockAvailable: Boolean,
    val sensitivityRange: IntRange,
    val exposureTimeRange: LongRange,
    val awbModes: Set<Int>,
    val awbLockAvailable: Boolean,
    val manualPostProcessing: Boolean = false,
    val afModes: Set<Int>,
    val minFocusDistance: Float,
    val zoomRatioRange: ClosedFloatingPointRange<Float>?,
    val maxDigitalZoom: Float,
    val activeArraySize: IntRect,
    val noiseReductionModes: Set<Int>,
    val edgeModes: Set<Int>,
    val faceDetectModes: Set<Int>,
    val sceneModes: Set<Int>,
    val videoStabilizationModes: Set<Int>,
    val aberrationModes: Set<Int>,
) {
    val supportsManualFocus: Boolean get() = minFocusDistance > 0f
    val supportsZoomRatio: Boolean get() = zoomRatioRange != null
    val maxZoom: Float get() = zoomRatioRange?.endInclusive ?: maxDigitalZoom.coerceAtLeast(1f)

    /**
     * Manual white balance needs `AWB_MODE_OFF` plus `MANUAL_POST_PROCESSING` support: without the
     * latter the device ignores `COLOR_CORRECTION_GAINS` (Pixel devices only honor them together
     * with a `COLOR_CORRECTION_TRANSFORM`).
     */
    val supportsManualWhiteBalance: Boolean
        get() = manualPostProcessing && Camera2Constants.AWB_MODE_OFF in awbModes

    fun hasStream(width: Int, height: Int): Boolean =
        streamSizes.any { it.width == width && it.height == height }
}
