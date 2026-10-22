package com.streamcam.camera

sealed class CameraEngineError {
    data class OpenFailed(val code: Int) : CameraEngineError()
    object Disconnected : CameraEngineError()
    object SessionFailed : CameraEngineError()
    data class ProbeFailed(val message: String) : CameraEngineError()
}
