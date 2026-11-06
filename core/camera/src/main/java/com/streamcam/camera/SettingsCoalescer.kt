package com.streamcam.camera

/**
 * Latest-value-wins scheduler for camera settings updates.
 *
 * Rapid control movements (e.g. dragging the shutter slider) submit many [CameraSettings] values in
 * a short window. Instead of queueing one Camera2 request per value, the coalescer keeps only the
 * newest pending value and schedules at most one drain task. The drain applies the newest value and
 * re-checks for anything that arrived while it was applying, so a burst collapses to one or two
 * requests instead of a backlog of obsolete ones.
 *
 * The scheduled flag stays set for the whole drain, so only one drain is ever posted at a time and
 * updates arriving mid-apply are folded into the next iteration rather than queued.
 *
 * Pure, thread-safe Kotlin so the scheduling rules are unit-testable; [CameraEngine] owns one
 * instance and runs the drain on the camera HandlerThread.
 */
class SettingsCoalescer {

    private val lock = Any()
    private var pending: CameraSettings? = null
    private var scheduled = false

    /**
     * Records [settings] as the latest desired value.
     *
     * @return true when the caller must post a drain task (the idle → scheduled transition).
     * Returning false means a drain is already queued or running and will pick this value up.
     */
    fun submit(settings: CameraSettings): Boolean = synchronized(lock) {
        pending = settings
        if (scheduled) {
            false
        } else {
            scheduled = true
            true
        }
    }

    /**
     * Drain step: returns the newest pending value, or null when idle (clearing the scheduled flag).
     * Only ever one drain runs at a time because [scheduled] remains set for the whole loop.
     */
    fun nextToApply(): CameraSettings? = synchronized(lock) {
        val next = pending
        if (next == null) {
            scheduled = false
            null
        } else {
            pending = null
            next
        }
    }
}
