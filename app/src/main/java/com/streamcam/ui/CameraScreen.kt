package com.streamcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcam.CameraViewModel
import com.streamcam.LensOption
import com.streamcam.camera.Camera2Constants
import com.streamcam.camera.CameraCapabilities
import com.streamcam.camera.CameraSettings
import com.streamcam.camera.ExposureMode
import com.streamcam.camera.FocusMode
import com.streamcam.camera.WhiteBalanceMode
import com.streamcam.camera.AppliedWhiteBalance
import com.streamcam.common.stream.StreamState
import com.streamcam.common.stream.StreamStatus
import com.streamcam.common.stream.StreamStats
import com.streamcam.imageproc.ShutterFormat
import com.streamcam.settings.OutputSize
import com.streamcam.settings.StreamSettings

private enum class ControlTab(val title: String) {
    WB("WB"),
    EXPOSURE("Exposure"),
    FOCUS("Focus"),
    ZOOM("Zoom"),
    LENS("Lens"),
    STREAM("Stream"),
    PROC("Proc"),
}

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val streamSettings by vm.streamSettings.collectAsState()
    val caps by vm.capabilities.collectAsState()
    val error by vm.error.collectAsState()
    val lensOptions by vm.lensOptions.collectAsState()
    val selectedCameraId by vm.selectedCameraId.collectAsState()
    val previewSize by vm.previewSize.collectAsState()
    val isCameraReady by vm.isCameraReady.collectAsState()
    val streamStatus by vm.streamStatus.collectAsState()
    val streamStats by vm.streamStats.collectAsState()
    val streamUrl by vm.streamUrl.collectAsState()
    val appliedWb by vm.appliedWb.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(context) {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
        }
        vm.onDisplayRotationChanged(
            when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            },
        )
    }

    var activeTab by remember { mutableStateOf(ControlTab.WB) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(
                previewSize = previewSize,
                sensorOrientation = caps?.sensorOrientation ?: 0,
                lensFacing = caps?.lensFacing ?: Camera2Constants.LENS_FACING_BACK,
                onSurfaceReady = vm::onPreviewSurfaceReady,
                onSurfaceDestroyed = vm::onPreviewSurfaceDestroyed,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "Camera permission is required",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        StatusChip(
            lensLabel = lensOptions.firstOrNull { it.cameraId == selectedCameraId }?.label ?: "—",
            previewSizeLabel = previewSize?.let { "${it.width}x${it.height}" } ?: "—",
            isReady = isCameraReady,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )

        error?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = vm::dismissError,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            StreamingBar(
                status = streamStatus,
                stats = streamStats,
                url = streamUrl,
                isCameraReady = isCameraReady,
                onStart = vm::startStreaming,
                onStop = vm::stopStreaming,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (caps != null) {
                ControlPanel(
                    settings = settings,
                    streamSettings = streamSettings,
                    caps = requireNotNull(caps),
                    appliedWb = appliedWb,
                    lensOptions = lensOptions,
                    selectedCameraId = selectedCameraId,
                    activeTab = activeTab,
                    lensEnabled = streamStatus.state == StreamState.IDLE,
                    onTabSelected = { activeTab = it },
                    onUpdate = vm::update,
                    onUpdateStream = vm::updateStream,
                    onSelectLens = vm::selectLens,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StreamingBar(
    status: StreamStatus,
    stats: StreamStats,
    url: String?,
    isCameraReady: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = status.state
    val active = state != StreamState.IDLE && state != StreamState.ERROR
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = modifier.padding(bottom = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.62f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatePill(state)
                Spacer(Modifier.width(8.dp))
                when (state) {
                    StreamState.IDLE, StreamState.ERROR -> Button(
                        onClick = onStart,
                        enabled = isCameraReady,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("Start stream") }

                    StreamState.STOPPING -> Button(
                        onClick = onStop,
                        enabled = false,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("Stopping…") }

                    else -> Button(
                        onClick = onStop,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("Stop") }
                }
                Spacer(Modifier.weight(1f))
                if (active && url != null) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("Copy URL", style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (active) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = url ?: "starting…",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                val statsLine = "%.0f fps · %.0f kb/s · drop %.1f/s · %d viewer(s)".format(
                    stats.sentFps,
                    stats.kbps,
                    stats.droppedFps,
                    stats.counters.clients,
                )
                Text(
                    text = statsLine,
                    color = if (state == StreamState.LIVE) Color(0xFF4CAF50)
                    else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (state == StreamState.ERROR) {
                val reason = status.errorReason
                Spacer(Modifier.height(4.dp))
                Text(
                    text = reason ?: "unknown error",
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun StatePill(state: StreamState) {
    val (symbol, label, color) = when (state) {
        StreamState.IDLE -> Triple("○", "OFF", Color.Gray)
        StreamState.STARTING -> Triple("◐", "STARTING", Color(0xFFFFB74D))
        StreamState.RUNNING -> Triple("○", "READY", Color(0xFFFFB74D))
        StreamState.LIVE -> Triple("●", "LIVE", Color(0xFFF44336))
        StreamState.STOPPING -> Triple("○", "STOPPING", Color.Gray)
        StreamState.ERROR -> Triple("●", "ERROR", Color(0xFFF44336))
    }
    Text(
        text = "$symbol $label",
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StatusChip(
    lensLabel: String,
    previewSizeLabel: String,
    isReady: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = if (isReady) "● LIVE PREVIEW" else "○ OPENING",
                color = if (isReady) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$lensLabel · $previewSizeLabel",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFB71C1C).copy(alpha = 0.92f),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ControlPanel(
    settings: CameraSettings,
    streamSettings: StreamSettings,
    caps: CameraCapabilities,
    appliedWb: AppliedWhiteBalance?,
    lensOptions: List<LensOption>,
    selectedCameraId: String?,
    activeTab: ControlTab,
    lensEnabled: Boolean,
    onTabSelected: (ControlTab) -> Unit,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onUpdateStream: ((StreamSettings) -> StreamSettings) -> Unit,
    onSelectLens: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.62f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (tab in ControlTab.entries) {
                    FilterChip(
                        selected = tab == activeTab,
                        onClick = { onTabSelected(tab) },
                        label = { Text(tab.title) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (activeTab) {
                ControlTab.WB -> WhiteBalancePanel(settings, caps, appliedWb, onUpdate)
                ControlTab.EXPOSURE -> ExposurePanel(settings, caps, onUpdate)
                ControlTab.FOCUS -> FocusPanel(settings, caps, onUpdate)
                ControlTab.ZOOM -> ZoomPanel(settings, caps, onUpdate)
                ControlTab.LENS -> LensPanel(lensOptions, selectedCameraId, lensEnabled, onSelectLens)
                ControlTab.STREAM -> StreamPanel(streamSettings, caps, lensEnabled, onUpdateStream)
                ControlTab.PROC -> ProcessingPanel(settings, onUpdate)
            }
        }
    }
}

@Composable
private fun WhiteBalancePanel(
    settings: CameraSettings,
    caps: CameraCapabilities,
    applied: AppliedWhiteBalance?,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    val manualAvailable = caps.supportsCctWhiteBalance || caps.supportsManualWhiteBalance
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!manualAvailable) {
                Text("Auto (manual WB not supported)", color = Color.White.copy(alpha = 0.7f))
                return@Column
            }
            AutoManualToggle(
                isManual = settings.whiteBalanceMode == WhiteBalanceMode.MANUAL,
                enabled = manualAvailable,
                onManualChange = { manual ->
                    onUpdate {
                        it.copy(
                            whiteBalanceMode = if (manual) {
                                WhiteBalanceMode.MANUAL
                            } else {
                                WhiteBalanceMode.AUTO
                            },
                            whiteBalanceLock = false,
                            // Start the manual dial at the current scene temperature so switching
                            // modes does not jump the image.
                            kelvin = if (manual) {
                                applied?.sceneCct?.coerceIn(2000, 10000) ?: it.kelvin
                            } else {
                                it.kelvin
                            },
                        )
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            when (settings.whiteBalanceMode) {
                WhiteBalanceMode.AUTO -> {
                    if (caps.awbLockAvailable) {
                        LabeledSwitch(
                            label = "AWB lock",
                            checked = settings.whiteBalanceLock,
                            onCheckedChange = { onUpdate { it.copy(whiteBalanceLock = it.whiteBalanceLock.not()) } },
                        )
                    } else {
                        Text("Auto", color = Color.White.copy(alpha = 0.7f))
                    }
                }
                WhiteBalanceMode.MANUAL -> {
                    Text(
                        text = if (caps.supportsCctWhiteBalance) {
                            "Native CCT"
                        } else {
                            "Manual WB (approx.)"
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (manualAvailable && settings.whiteBalanceMode == WhiteBalanceMode.MANUAL) {
            val kelvinRange = caps.cctKelvinRange ?: (2000..10000)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ValueLabel(
                    buildString {
                        append("Req ${settings.kelvin} K · tint ${settings.tint}")
                        if (applied?.colorTemperature != null) {
                            append("  ·  applied ${applied.colorTemperature} K")
                            if (applied.colorTint != null) append(" · tint ${applied.colorTint}")
                        }
                    },
                )
                Slider(
                    value = settings.kelvin.toFloat().coerceIn(
                        kelvinRange.first.toFloat(),
                        kelvinRange.last.toFloat(),
                    ),
                    onValueChange = { value ->
                        onUpdate { s -> s.copy(kelvin = value.toInt().coerceIn(kelvinRange)) }
                    },
                    valueRange = kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                    steps = ((kelvinRange.last - kelvinRange.first) / 100) - 1,
                )
                Spacer(Modifier.height(4.dp))
                ValueLabel("Tint ${settings.tint}")
                Slider(
                    value = settings.tint.toFloat(),
                    onValueChange = { value ->
                        onUpdate { s ->
                            s.copy(tint = value.toInt().coerceIn(
                                Camera2Constants.MIN_COLOR_TINT,
                                Camera2Constants.MAX_COLOR_TINT,
                            ))
                        }
                    },
                    valueRange = Camera2Constants.MIN_COLOR_TINT.toFloat()..
                        Camera2Constants.MAX_COLOR_TINT.toFloat(),
                    steps = 99,
                )
            }
        }
    }
}

@Composable
private fun ExposurePanel(
    settings: CameraSettings,
    caps: CameraCapabilities,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    val supportsManual = Camera2Constants.AE_MODE_OFF in caps.aeModes &&
        caps.sensitivityRange.first <= caps.sensitivityRange.last &&
        caps.exposureTimeRange.first <= caps.exposureTimeRange.last
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutoManualToggle(
                isManual = settings.exposureMode == ExposureMode.MANUAL,
                enabled = supportsManual,
                onManualChange = { manual ->
                    onUpdate {
                        it.copy(
                            exposureMode = if (manual) ExposureMode.MANUAL else ExposureMode.AUTO,
                            aeLock = false,
                        )
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            if (settings.exposureMode == ExposureMode.AUTO) {
                if (caps.aeLockAvailable) {
                    LabeledSwitch(
                        label = "AE lock",
                        checked = settings.aeLock,
                        onCheckedChange = { onUpdate { s -> s.copy(aeLock = !s.aeLock) } },
                    )
                } else {
                    Text("Auto", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        if (settings.exposureMode == ExposureMode.MANUAL && supportsManual) {
            val isoStops = rememberIsoStops(caps)
            val shutterStops = rememberShutterStops(caps)
            val isoIndex = isoStops.indexOfFirst { it >= settings.iso }.coerceAtLeast(0)
            val shutterIndex = shutterStops.indexOfFirst { it >= settings.shutterNs }.coerceAtLeast(0)
            Spacer(Modifier.height(4.dp))
            ValueLabel("ISO ${isoStops.getOrElse(isoIndex) { settings.iso }}")
            Slider(
                value = isoIndex.toFloat(),
                onValueChange = { index ->
                    val iso = isoStops.getOrNull(index.toInt()) ?: return@Slider
                    onUpdate { it.copy(iso = iso) }
                },
                valueRange = 0f..(isoStops.size - 1).coerceAtLeast(0).toFloat(),
                steps = (isoStops.size - 2).coerceAtLeast(0),
            )
            ValueLabel("Shutter ${ShutterFormat.format(shutterStops.getOrElse(shutterIndex) { settings.shutterNs })}")
            Slider(
                value = shutterIndex.toFloat(),
                onValueChange = { index ->
                    val shutter = shutterStops.getOrNull(index.toInt()) ?: return@Slider
                    onUpdate { it.copy(shutterNs = shutter) }
                },
                valueRange = 0f..(shutterStops.size - 1).coerceAtLeast(0).toFloat(),
                steps = (shutterStops.size - 2).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun rememberIsoStops(caps: CameraCapabilities): List<Int> = remember(caps) {
    listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800).filter { it in caps.sensitivityRange }
        .ifEmpty { listOf(caps.sensitivityRange.first.coerceAtLeast(1)) }
}

@Composable
private fun rememberShutterStops(caps: CameraCapabilities): List<Long> = remember(caps) {
    listOf(
        125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
        10_000_000L, 16_666_667L, 20_000_000L, 25_000_000L, 33_333_333L, 40_000_000L,
        66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L,
        2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L,
    ).filter { it in caps.exposureTimeRange }
        .ifEmpty { listOf(caps.exposureTimeRange.first.coerceAtLeast(1L)) }
}

@Composable
private fun FocusPanel(
    settings: CameraSettings,
    caps: CameraCapabilities,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    if (!caps.supportsManualFocus) {
        Text("Fixed-focus lens — manual focus unavailable", color = Color.White.copy(alpha = 0.7f))
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutoManualToggle(
                isManual = settings.focusMode == FocusMode.MANUAL,
                enabled = true,
                onManualChange = { manual ->
                    onUpdate {
                        it.copy(
                            focusMode = if (manual) FocusMode.MANUAL else FocusMode.AUTO,
                            focusDistanceDiopters = 0f,
                        )
                    }
                },
            )
        }
        if (settings.focusMode == FocusMode.MANUAL) {
            Spacer(Modifier.height(4.dp))
            val diopters = settings.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistance)
            ValueLabel(
                if (diopters <= 0.01f) "Focus: infinity (∞)" else "Focus: ${"%.1f".format(diopters)} dpt",
            )
            Slider(
                value = diopters,
                onValueChange = { onUpdate { s -> s.copy(focusDistanceDiopters = it) } },
                valueRange = 0f..caps.minFocusDistance,
            )
        }
    }
}

@Composable
private fun ZoomPanel(
    settings: CameraSettings,
    caps: CameraCapabilities,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    val maxZoom = caps.maxZoom
    Column {
        ValueLabel("Zoom ${"%.1f".format(settings.zoomRatio)}x")
        Slider(
            value = settings.zoomRatio.coerceIn(1f, maxZoom),
            onValueChange = { onUpdate { s -> s.copy(zoomRatio = it) } },
            valueRange = 1f..maxZoom.coerceAtLeast(1.01f),
        )
    }
}

@Composable
private fun LensPanel(
    lensOptions: List<LensOption>,
    selectedCameraId: String?,
    enabled: Boolean,
    onSelectLens: (String) -> Unit,
) {
    if (lensOptions.isEmpty()) {
        Text("No cameras found", color = Color.White.copy(alpha = 0.7f))
        return
    }
    if (!enabled) {
        Text(
            "Lens switching is disabled while streaming",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (lens in lensOptions) {
            FilterChip(
                selected = lens.cameraId == selectedCameraId,
                onClick = { onSelectLens(lens.cameraId) },
                enabled = enabled,
                label = { Text(lens.label) },
            )
        }
    }
}

@Composable
private fun StreamPanel(
    streamSettings: StreamSettings,
    caps: CameraCapabilities,
    idle: Boolean,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val mbps = streamSettings.bitrateBps / 1_000_000f
        ValueLabel("Bitrate %.1f Mb/s".format(mbps))
        Slider(
            value = mbps,
            onValueChange = { onUpdate { s -> s.copy(bitrateBps = (it * 1_000_000f).toInt()) } },
            valueRange = StreamSettings.MIN_BITRATE_BPS / 1_000_000f..StreamSettings.MAX_BITRATE_BPS / 1_000_000f,
            steps = (StreamSettings.MAX_BITRATE_BPS - StreamSettings.MIN_BITRATE_BPS) /
                StreamSettings.BITRATE_STEP_BPS - 1,
        )

        Spacer(Modifier.height(4.dp))
        ValueLabel(
            if (streamSettings.fps == StreamSettings.FPS_AUTO) "FPS Auto"
            else "FPS ${streamSettings.fps}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fpsChoices = StreamSettings.FPS_CHOICES.filter { choice ->
                choice == StreamSettings.FPS_AUTO ||
                    caps.aeTargetFpsRanges.any { choice in it.min..it.max }
            }
            for (choice in fpsChoices) {
                FilterChip(
                    selected = streamSettings.fps == choice,
                    onClick = { onUpdate { s -> s.copy(fps = choice) } },
                    label = { Text(if (choice == StreamSettings.FPS_AUTO) "Auto" else "$choice") },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        ValueLabel(
            when (streamSettings.outputSize) {
                OutputSize.AUTO -> "Resolution Auto"
                OutputSize.P1080 -> "Resolution 1080p"
                OutputSize.P720 -> "Resolution 720p"
            },
        )
        if (!idle) {
            Text(
                "Stop the stream to change resolution or fps",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val sizeChoices = buildList {
                add(OutputSize.AUTO to "Auto")
                if (caps.hasStream(1920, 1080)) add(OutputSize.P1080 to "1080p")
                if (caps.hasStream(1280, 720)) add(OutputSize.P720 to "720p")
            }
            for ((size, label) in sizeChoices) {
                FilterChip(
                    selected = streamSettings.outputSize == size,
                    onClick = { onUpdate { s -> s.copy(outputSize = size) } },
                    enabled = idle,
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        var portText by remember(streamSettings.port) { mutableStateOf(streamSettings.port.toString()) }
        OutlinedTextField(
            value = portText,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(5)
                portText = digits
                digits.toIntOrNull()?.let { port ->
                    if (port in StreamSettings.MIN_PORT..StreamSettings.MAX_PORT) {
                        onUpdate { s -> s.copy(port = port) }
                    }
                }
            },
            label = { Text("RTSP port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(0.5f),
        )

        Text(
            text = "Bitrate and port apply on the next stream start; all settings persist across restarts.",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ProcessingPanel(
    settings: CameraSettings,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    Column {
        LabeledSwitch(
            label = "Minimal processing (NR/edge/face/scene/stab off where supported)",
            checked = settings.minimalProcessing,
            onCheckedChange = { onUpdate { s -> s.copy(minimalProcessing = !s.minimalProcessing) } },
        )
        Text(
            text = "Android exposes no public sharpening control; vendor processing may remain.",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AutoManualToggle(
    isManual: Boolean,
    enabled: Boolean,
    onManualChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Auto",
            color = if (!isManual) Color.White else Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(6.dp))
        Switch(checked = isManual, enabled = enabled, onCheckedChange = onManualChange)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Manual",
            color = if (isManual) Color.White else Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}

@Composable
private fun ValueLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}
