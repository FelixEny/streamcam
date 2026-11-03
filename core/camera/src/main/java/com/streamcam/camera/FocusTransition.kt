package com.streamcam.camera

/**
 * Pure AF state machine: maps an intent change plus the HAL's `CONTROL_AF_STATE` onto the next
 * intent and the state the UI should show. Extracted from `CameraEngine` so every transition —
 * success, failure, lock, unlock, drift — is unit-testable without a camera.
 */
object FocusTransition {

    /**
     * @param intent current intent (IDLE / TAPPING / LOCKED)
     * @param afState one of `CONTROL_AF_STATE_*`
     * @param focusDistance `LENS_FOCUS_DISTANCE` of the settling frame (dioptres); used when locked
     * @return the new intent and the UI state to publish (null = keep current UI state)
     */
    fun onAfState(
        intent: FocusIntent,
        afState: Int,
        focusDistance: Float?,
    ): Pair<FocusIntent, FocusState?> = when {
        intent is FocusIntent.TAPPING &&
            afState == Camera2Constants.AF_STATE_FOCUSED_LOCKED -> {
            // Focus succeeded: hold the *measured* distance, never guess.
            FocusIntent.LOCKED(focusDistance ?: 0f) to FocusState.LOCKED
        }

        intent is FocusIntent.TAPPING &&
            afState == Camera2Constants.AF_STATE_NOT_FOCUSED_LOCKED -> {
            // Focus failed: do not hold anything, report the failure honestly.
            FocusIntent.IDLE to FocusState.FAILED
        }

        intent is FocusIntent.LOCKED &&
            (afState == Camera2Constants.AF_STATE_PASSIVE_SCAN ||
                afState == Camera2Constants.AF_STATE_ACTIVE_SCAN) -> {
            // The HAL re-hunting (e.g. after a zoom/lens change) invalidates the hold.
            FocusIntent.IDLE to FocusState.IDLE
        }

        else -> intent to null
    }
}
