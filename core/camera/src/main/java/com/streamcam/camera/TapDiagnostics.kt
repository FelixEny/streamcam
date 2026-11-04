package com.streamcam.camera

/**
 * Diagnostic bookkeeping for one tap-to-focus attempt.
 *
 * Kept pure so the "log once per change" and lifecycle-reset rules can be unit-tested without a
 * `CameraDevice`. The engine owns one instance; every lifecycle boundary (unlock, camera close,
 * session rebuild) calls [reset] so a new focus operation can never inherit a previous attempt's
 * returned region.
 */
class TapDiagnostics {

    var active: Boolean = false
        private set

    private var lastReturnedRegions: String? = null

    /** Begins a new attempt: enables logging and forgets any previous region. */
    fun start() {
        active = true
        lastReturnedRegions = null
    }

    /** Ends the attempt and clears all state (unlock, camera close, session rebuild). */
    fun reset() {
        active = false
        lastReturnedRegions = null
    }

    /**
     * @return true when [regions] is a new non-null value that should be logged. A HAL that
     * repeats the same region for every frame of a scan therefore produces one line, not dozens.
     */
    fun onReturnedRegions(regions: String?): Boolean {
        if (!active || regions == null || regions == lastReturnedRegions) return false
        lastReturnedRegions = regions
        return true
    }

    /**
     * Terminal AF states end the attempt so later captures cannot keep logging. Non-terminal
     * states (passive/active scan, focused) leave the attempt running.
     */
    fun onAfState(afState: Int?) {
        if (afState == Camera2Constants.AF_STATE_FOCUSED_LOCKED ||
            afState == Camera2Constants.AF_STATE_NOT_FOCUSED_LOCKED
        ) {
            reset()
        }
    }
}
