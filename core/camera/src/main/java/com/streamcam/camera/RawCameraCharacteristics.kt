package com.streamcam.camera

/**
 * Plain-Kotlin snapshot of the CameraCharacteristics fields this app consumes.
 * The Android-side [CapabilityProbe] builds it from CameraCharacteristics; unit
 * tests build it by hand. Null/empty entries mean "device did not report the key".
 */
data class RawCameraCharacteristics(
    val lensFacing: Int,
    val sensorOrientation: Int = 0,
    val hardwareLevel: Int,
    val physicalCameraIds: List<String> = emptyList(),
    val focalLengths: Map<String, Float> = emptyMap(),
    val streamSizes: List<IntSize> = emptyList(),
    val aeModes: Set<Int> = emptySet(),
    val aeTargetFpsRanges: List<FpsRange> = emptyList(),
    val aeLockAvailable: Boolean = false,
    val sensitivityRangeMin: Int = 0,
    val sensitivityRangeMax: Int = 0,
    val exposureTimeRangeMinNs: Long = 0,
    val exposureTimeRangeMaxNs: Long = 0,
    val awbModes: Set<Int> = emptySet(),
    val awbLockAvailable: Boolean = false,
    val manualPostProcessing: Boolean = false,
    val afModes: Set<Int> = emptySet(),
    val minFocusDistance: Float = 0f,
    val zoomRatioRangeMin: Float? = null,
    val zoomRatioRangeMax: Float? = null,
    val maxDigitalZoom: Float = 1f,
    val activeArrayLeft: Int = 0,
    val activeArrayTop: Int = 0,
    val activeArrayRight: Int = 0,
    val activeArrayBottom: Int = 0,
    val noiseReductionModes: Set<Int> = emptySet(),
    val edgeModes: Set<Int> = emptySet(),
    val faceDetectModes: Set<Int> = emptySet(),
    val sceneModes: Set<Int> = emptySet(),
    val videoStabilizationModes: Set<Int> = emptySet(),
    val aberrationModes: Set<Int> = emptySet(),
)
