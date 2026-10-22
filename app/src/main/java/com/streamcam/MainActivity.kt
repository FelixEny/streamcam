package com.streamcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.streamcam.ui.CameraScreen

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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
}
