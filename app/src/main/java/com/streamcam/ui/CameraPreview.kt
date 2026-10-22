package com.streamcam.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.streamcam.camera.IntSize
import com.streamcam.camera.PreviewOrientation
import com.streamcam.camera.PreviewTransform

/**
 * Camera preview surface.
 *
 * Camera2 delivers buffers in the sensor's native frame; the rotation between the sensor
 * orientation and the *current* display rotation is applied as a view transform so the preview
 * appears upright. The transform is display-only: the encoder surface is fed directly by the
 * camera, so the streamed picture keeps the camera's native framing.
 *
 * `TextureView` stretches its buffer to its own bounds, so the transform also applies the
 * [PreviewTransform] fill correction: the preview covers the whole view with square pixels
 * (center crop), while the stream keeps the full frame.
 *
 * The transform is only ever derived from a *known* display rotation: while the view has no
 * display (not attached yet) nothing is applied, and it is re-applied on attach, on layout/size
 * changes, when the display rotation changes and when the preview size or sensor changes. A
 * transient "rotation 0" must never be used, because it would rotate the preview 90 degrees and the
 * stale transform would survive relaunches (the camera reopens without re-laying out the view).
 */
@Composable
fun CameraPreview(
    previewSize: IntSize?,
    sensorOrientation: Int,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
    val onReadyState = rememberUpdatedState(onSurfaceReady)
    val onDestroyedState = rememberUpdatedState(onSurfaceDestroyed)
    val sizeState = rememberUpdatedState(previewSize)
    val sensorState = rememberUpdatedState(sensorOrientation)
    val viewHolder = remember { arrayOfNulls<TextureView>(1) }
    val listenerHolder = remember { arrayOfNulls<DisplayManager.DisplayListener>(1) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                viewHolder[0] = this
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        reapply(viewHolder, sizeState.value, sensorState.value)
                    }

                    override fun onViewDetachedFromWindow(view: View) = Unit
                })
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        sizeState.value?.let { texture.setDefaultBufferSize(it.width, it.height) }
                        applyPreviewTransform(this@apply, sizeState.value, sensorState.value)
                        onReadyState.value(Surface(texture))
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        applyPreviewTransform(this@apply, sizeState.value, sensorState.value)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        onDestroyedState.value()
                        texture.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    reapply(viewHolder, sizeState.value, sensorState.value)
                }

                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit
                    override fun onDisplayRemoved(displayId: Int) = Unit
                    override fun onDisplayChanged(displayId: Int) {
                        reapply(viewHolder, sizeState.value, sensorState.value)
                    }
                }
                listenerHolder[0] = listener
                displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            }
        },
        update = { textureView ->
            val texture = textureView.surfaceTexture ?: return@AndroidView
            val size = sizeState.value ?: return@AndroidView
            texture.setDefaultBufferSize(size.width, size.height)
            applyPreviewTransform(textureView, size, sensorState.value)
        },
        onRelease = {
            listenerHolder[0]?.let { displayManager?.unregisterDisplayListener(it) }
            listenerHolder[0] = null
            viewHolder[0] = null
        },
        modifier = modifier,
    )
}

private fun reapply(holder: Array<TextureView?>, size: IntSize?, sensorOrientation: Int) {
    holder[0]?.let { applyPreviewTransform(it, size, sensorOrientation) }
}

private fun applyPreviewTransform(view: TextureView, size: IntSize?, sensorOrientation: Int) {
    if (size == null || view.width <= 0 || view.height <= 0) return
    // Without an attached display the rotation is unknown; applying a guess would rotate the
    // preview by 90 degrees and nothing would correct it until the next layout change.
    if (!view.isAttachedToWindow) return
    val displayRotation = view.display?.rotation ?: return
    val displayDegrees = displayRotationDegrees(displayRotation)
    val rotation = PreviewOrientation.relativeRotation(sensorOrientation, displayDegrees)
    val scale = PreviewTransform.fillScale(
        viewWidth = view.width,
        viewHeight = view.height,
        bufferWidth = size.width,
        bufferHeight = size.height,
        rotationDegrees = rotation,
    )
    val centerX = view.width / 2f
    val centerY = view.height / 2f
    view.setTransform(
        Matrix().apply {
            postRotate(rotation.toFloat(), centerX, centerY)
            postScale(scale.x, scale.y, centerX, centerY)
        },
    )
    Log.i(
        TAG,
        "preview view=${view.width}x${view.height} buffer=${size.width}x${size.height} " +
            "displayRotation=$displayDegrees sensor=$sensorOrientation rotation=$rotation " +
            "scale=${scale.x}x${scale.y}",
    )
}

private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private const val TAG = "CameraPreview"
