package com.streamcam

import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.Locale

/**
 * Phase 4 diagnostics for external controllers (insta360 Flow zoom wheel): reports which input
 * devices the platform actually sees and which key/axis events they emit, so any future support is
 * based on observed events instead of assumptions. Log-only — it never consumes an event.
 */
object InputDiag {

    const val TAG = "InputDiag"
    private const val MOTION_LOG_INTERVAL_MS = 500L

    /** Axes worth logging for a gimbal wheel/joystick; `MotionEvent` has no axis-count API. */
    private val LOGGED_AXES = intArrayOf(
        MotionEvent.AXIS_X,
        MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z,
        MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HSCROLL,
        MotionEvent.AXIS_VSCROLL,
        MotionEvent.AXIS_WHEEL,
        MotionEvent.AXIS_THROTTLE,
        MotionEvent.AXIS_RUDDER,
        MotionEvent.AXIS_GENERIC_1,
        MotionEvent.AXIS_GENERIC_2,
        MotionEvent.AXIS_GENERIC_3,
        MotionEvent.AXIS_GENERIC_4,
    )

    private var lastMotionSignature: String? = null
    private var lastMotionLogAtMs = 0L

    fun logDevices(manager: InputManager?) {
        val ids = runCatching { manager?.inputDeviceIds }.getOrNull() ?: return
        for (id in ids) logDevice(manager, id)
    }

    fun logDevice(manager: InputManager?, deviceId: Int) {
        val device = runCatching { manager?.getInputDevice(deviceId) }.getOrNull() ?: return
        Log.i(
            TAG,
            "device id=${device.id} name=${device.name} vendor=${device.vendorId} " +
                "product=${device.productId} sources=0x${Integer.toHexString(device.sources)} " +
                "keyboard=${device.sources and InputDevice.SOURCE_KEYBOARD != 0} " +
                "mouse=${device.sources and InputDevice.SOURCE_MOUSE != 0} " +
                "joystick=${device.sources and InputDevice.SOURCE_JOYSTICK != 0} " +
                "touchscreen=${device.sources and InputDevice.SOURCE_TOUCHSCREEN != 0} " +
                "gamepad=${device.sources and InputDevice.SOURCE_GAMEPAD != 0}",
        )
    }

    fun logRemoved(deviceId: Int) {
        Log.i(TAG, "device removed id=$deviceId")
        resetMotionThrottle()
    }

    /** Logs user action key downs (number of lines matter: repeatCount shows a held wheel). */
    fun logKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        Log.i(
            TAG,
            "key down code=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) " +
                "scan=${event.scanCode} device=${event.deviceId} " +
                "source=0x${Integer.toHexString(event.source)} repeat=${event.repeatCount}",
        )
    }

    /**
     * Logs non-zero motion axes, throttled to changes (or once per 500 ms while a wheel is being
     * spun continuously in the same direction). Joystick-style streams therefore cannot flood
     * logcat while a real wheel axis is still visible.
     */
    fun logMotion(event: MotionEvent) {
        val values = ArrayList<String>(LOGGED_AXES.size)
        for (axis in LOGGED_AXES) {
            val value = event.getAxisValue(axis)
            if (value != 0f) {
                values.add(axisName(axis) + "=" + String.format(Locale.US, "%.3f", value))
            }
        }
        if (values.isEmpty()) return
        val signature = values.joinToString(",")
        val now = SystemClock.uptimeMillis()
        if (signature == lastMotionSignature && now - lastMotionLogAtMs < MOTION_LOG_INTERVAL_MS) {
            return
        }
        lastMotionSignature = signature
        lastMotionLogAtMs = now
        Log.i(
            TAG,
            "motion action=${event.actionMasked} device=${event.deviceId} " +
                "source=0x${Integer.toHexString(event.source)} axes=[$signature]",
        )
    }

    private fun axisName(axis: Int): String = when (axis) {
        MotionEvent.AXIS_X -> "X"
        MotionEvent.AXIS_Y -> "Y"
        MotionEvent.AXIS_Z -> "Z"
        MotionEvent.AXIS_RZ -> "RZ"
        MotionEvent.AXIS_HSCROLL -> "HSCROLL"
        MotionEvent.AXIS_VSCROLL -> "VSCROLL"
        MotionEvent.AXIS_WHEEL -> "WHEEL"
        MotionEvent.AXIS_THROTTLE -> "THROTTLE"
        MotionEvent.AXIS_RUDDER -> "RUDDER"
        else -> axis.toString()
    }

    fun resetMotionThrottle() {
        lastMotionSignature = null
        lastMotionLogAtMs = 0L
    }
}
