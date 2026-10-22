package com.streamcam.imageproc

// Mirrors android.hardware.camera2.CameraMetadata constants (verified against API 34).
// Kept local so :core:imageproc stays pure-Kotlin and JVM-testable.
object ProcessingModes {
    const val NOISE_REDUCTION_OFF = 0
    const val EDGE_OFF = 0
    const val FACE_DETECT_OFF = 0
    const val SCENE_DISABLED = 0
    const val VIDEO_STABILIZATION_OFF = 0
    const val ABERRATION_OFF = 0
}

// null = leave the HAL default for that control (device does not report an OFF mode).
// Android exposes no public sharpening key, so sharpening is intentionally absent here.
data class MinimalProcessingSelection(
    val noiseReductionMode: Int?,
    val edgeMode: Int?,
    val faceDetectMode: Int?,
    val sceneMode: Int?,
    val videoStabilizationMode: Int?,
    val aberrationMode: Int?,
)

object ProcessingPolicy {
    fun minimalProcessingSelection(
        availableNoiseReductionModes: Set<Int>,
        availableEdgeModes: Set<Int>,
        availableFaceDetectModes: Set<Int>,
        availableSceneModes: Set<Int>,
        availableVideoStabilizationModes: Set<Int>,
        availableAberrationModes: Set<Int>,
    ): MinimalProcessingSelection = MinimalProcessingSelection(
        noiseReductionMode = ProcessingModes.NOISE_REDUCTION_OFF.takeIf { it in availableNoiseReductionModes },
        edgeMode = ProcessingModes.EDGE_OFF.takeIf { it in availableEdgeModes },
        faceDetectMode = ProcessingModes.FACE_DETECT_OFF.takeIf { it in availableFaceDetectModes },
        sceneMode = ProcessingModes.SCENE_DISABLED.takeIf { it in availableSceneModes },
        videoStabilizationMode = ProcessingModes.VIDEO_STABILIZATION_OFF.takeIf { it in availableVideoStabilizationModes },
        aberrationMode = ProcessingModes.ABERRATION_OFF.takeIf { it in availableAberrationModes },
    )
}
