package com.streamcam

import android.hardware.input.InputManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.streamcam.ui.CameraScreen
import com.streamcam.ui.StreamCamTypography

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()
    private val inputManager by lazy { getSystemService(InputManager::class.java) }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            InputDiag.resetMotionThrottle()
            InputDiag.logDevice(inputManager, deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            InputDiag.logRemoved(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            InputDiag.logDevice(inputManager, deviceId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Streaming rigs sit idle for long stretches; keep the display awake while the app is
        // visible. The flag is released automatically when the activity leaves the foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        InputDiag.logDevices(inputManager)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(),
                typography = StreamCamTypography,
            ) {
                CameraScreen(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppResumed()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackgrounded()
    }

    override fun onDestroy() {
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        super.onDestroy()
    }

    /**
     * Phase 4 Flow diagnostics: log every key before the view hierarchy can consume it. This is how
     * we learn whether the gimbal's zoom wheel maps to an Android key at all, and to which one.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        InputDiag.logKey(event)
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_ZOOM_IN || event.keyCode == KeyEvent.KEYCODE_ZOOM_OUT)
        ) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_ZOOM_IN) 1 else -1
            if (vm.adjustZoom(direction)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Same idea for motion axes, logged on the activity entry point (before Compose scrollables can
     * consume a wheel) so a HID wheel is observable even if nothing handles it.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        InputDiag.logMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }
}
