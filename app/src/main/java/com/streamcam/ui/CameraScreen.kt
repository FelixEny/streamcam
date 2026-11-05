package com.streamcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcam.CameraViewModel
import com.streamcam.camera.AppliedWhiteBalance
import com.streamcam.camera.Camera2Constants
import com.streamcam.camera.CameraCapabilities
import com.streamcam.camera.CameraSettings
import com.streamcam.camera.ExposureMode
import com.streamcam.camera.FocusState
import com.streamcam.camera.WhiteBalanceMode
import com.streamcam.common.stream.StreamState
import com.streamcam.common.stream.StreamStatus
import com.streamcam.common.stream.StreamStats
import com.streamcam.common.vmix.VmixBus
import com.streamcam.imageproc.ShutterFormat
import com.streamcam.imageproc.ZoomMath
import com.streamcam.settings.OutputSize
import com.streamcam.settings.StreamSettings
import kotlin.math.exp
import kotlin.math.ln

private enum class SheetType {
    RESOLUTION_FPS, WB, ISO, SHUTTER, SETTINGS, FOCUS
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val previewViewSize by vm.previewViewSize.collectAsState()
    val isCameraReady by vm.isCameraReady.collectAsState()
    val streamStatus by vm.streamStatus.collectAsState()
    val streamStats by vm.streamStats.collectAsState()
    val streamUrl by vm.streamUrl.collectAsState()
    val appliedWb by vm.appliedWb.collectAsState()
    val appliedIso by vm.appliedIso.collectAsState()
    val appliedShutterNs by vm.appliedShutterNs.collectAsState()
    val vmixBus by vm.vmixBus.collectAsState()
    val focusState by vm.focusState.collectAsState()
    var focusReticlePos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var activeSheet by remember { mutableStateOf<SheetType?>(null) }
    var statsBarVisible by remember { mutableStateOf(true) }

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

    val vmixColor = when (vmixBus) {
        VmixBus.PROGRAM -> Color(0xFFFF3B30)
        VmixBus.PREVIEW -> Color(0xFF34C759)
        else -> null
    }
    val vmixBorder = if (vmixColor != null) Modifier.border(6.dp, vmixColor) else Modifier

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            Row(Modifier.fillMaxSize()) {
                // ── Left sidebar: zoom ──────────────────────────────────────
                ZoomSidebar(
                    zoomRatio = settings.zoomRatio,
                    zoomRange = caps?.zoomRatioRange,
                    onZoomChange = { ratio -> vm.update { it.copy(zoomRatio = ratio) } },
                    onReset = { vm.setZoomPreset(1f) },
                    modifier = Modifier.width(76.dp).fillMaxHeight(),
                )

                // ── Centre column: pills + preview + stats ─────────────────
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PillRow(
                        streamSettings = streamSettings,
                        settings = settings,
                        appliedIso = appliedIso,
                        appliedShutterNs = appliedShutterNs,
                        focusState = focusState,
                        streamState = streamStatus.state,
                        onResolutionClick = { activeSheet = SheetType.RESOLUTION_FPS },
                        onWbClick = { activeSheet = SheetType.WB },
                        onIsoClick = { activeSheet = SheetType.ISO },
                        onShutterClick = { activeSheet = SheetType.SHUTTER },
                        onFocusClick = { if (focusState == FocusState.LOCKED) vm.unlockFocus() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CameraPreview(
                            previewSize = previewSize,
                            sensorOrientation = caps?.sensorOrientation ?: 0,
                            lensFacing = caps?.lensFacing ?: Camera2Constants.LENS_FACING_BACK,
                            onSurfaceReady = vm::onPreviewSurfaceReady,
                            onSurfaceDestroyed = vm::onPreviewSurfaceDestroyed,
                            onSizeChanged = vm::onPreviewViewSize,
                            onTap = { x, y ->
                                val size = previewViewSize
                                if (size != null && vm.tapFocus(x, y, size.width, size.height)) {
                                    focusReticlePos = x to y
                                }
                            },
                            modifier = Modifier.fillMaxSize().then(vmixBorder),
                        )

                        focusReticlePos?.let { (x, y) ->
                            val density = LocalDensity.current
                            FocusReticle(
                                state = focusState,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(
                                        x = with(density) { x.toDp() } - 12.dp,
                                        y = with(density) { y.toDp() } - 12.dp,
                                    ),
                                onHidden = { focusReticlePos = null },
                            )
                        }
                    }

                    StatsBar(
                        visible = statsBarVisible,
                        onToggle = { statsBarVisible = !statsBarVisible },
                        url = streamUrl,
                        stats = streamStats,
                        state = streamStatus.state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── Right sidebar: settings / start / focus ─────────────────
                ActionSidebar(
                    streamState = streamStatus.state,
                    isCameraReady = isCameraReady,
                    onStartStop = {
                        when (streamStatus.state) {
                            StreamState.IDLE, StreamState.ERROR -> vm.startStreaming()
                            StreamState.STARTING, StreamState.RUNNING, StreamState.LIVE ->
                                vm.stopStreaming()
                            StreamState.STOPPING -> Unit
                        }
                    },
                    onSettings = { activeSheet = SheetType.SETTINGS },
                    onFocus = { activeSheet = SheetType.FOCUS },
                    modifier = Modifier.width(76.dp).fillMaxHeight(),
                )
            }
        } else {
            Text(
                text = "Camera permission is required",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        error?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = vm::dismissError,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            )
        }

        // ── Bottom sheets ──────────────────────────────────────────────────
        activeSheet?.let { sheet ->
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = Color(0xFF1C1C1E),
            ) {
                when (sheet) {
                    SheetType.RESOLUTION_FPS -> ResolutionFpsSheet(
                        streamSettings = streamSettings,
                        caps = caps,
                        idle = !streamStatus.state.isActive(),
                        onUpdate = vm::updateStream,
                    )

                    SheetType.WB -> WbSheet(
                        settings = settings,
                        caps = caps,
                        appliedWb = appliedWb,
                        onUpdate = vm::update,
                    )

                    SheetType.ISO -> IsoSheet(
                        settings = settings,
                        caps = caps,
                        appliedIso = appliedIso,
                        onUpdate = vm::update,
                    )

                    SheetType.SHUTTER -> ShutterSheet(
                        settings = settings,
                        caps = caps,
                        appliedShutterNs = appliedShutterNs,
                        onUpdate = vm::update,
                    )

                    SheetType.SETTINGS -> SettingsSheet(
                        streamSettings = streamSettings,
                        settings = settings,
                        caps = caps,
                        lensOptions = lensOptions,
                        selectedCameraId = selectedCameraId,
                        lensEnabled = !streamStatus.state.isActive(),
                        onUpdateStream = vm::updateStream,
                        onUpdate = vm::update,
                        onSelectLens = vm::selectLens,
                    )

                    SheetType.FOCUS -> FocusSheet(
                        settings = settings,
                        caps = caps,
                        onUpdate = vm::update,
                    )
                }
            }
        }
    }
}

// ── Pill row ────────────────────────────────────────────────────────────────

@Composable
private fun PillRow(
    streamSettings: StreamSettings,
    settings: CameraSettings,
    appliedIso: Int?,
    appliedShutterNs: Long?,
    focusState: FocusState,
    streamState: StreamState,
    onResolutionClick: () -> Unit,
    onWbClick: () -> Unit,
    onIsoClick: () -> Unit,
    onShutterClick: () -> Unit,
    onFocusClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // LIVE — status only, not clickable
        Pill(
            text = if (streamState == StreamState.LIVE) "LIVE" else "OFF",
            dotColor = if (streamState == StreamState.LIVE) Color(0xFFF44336) else Color.Gray,
            onClick = null,
        )

        // Resolution · FPS
        val resLabel = when (streamSettings.outputSize) {
            OutputSize.AUTO -> "AUTO"
            OutputSize.P1080 -> "1080P"
            OutputSize.P720 -> "720P"
        }
        val fpsLabel =
            if (streamSettings.fps == StreamSettings.FPS_AUTO) "AUTO"
            else "${streamSettings.fps}F"
        Pill(text = "$resLabel · $fpsLabel", onClick = onResolutionClick)

        // White balance
        val wbLabel =
            if (settings.whiteBalanceMode == WhiteBalanceMode.AUTO) "AUTO"
            else "${settings.kelvin}K"
        Pill(text = "WB $wbLabel", onClick = onWbClick)

        // ISO
        val isoValue =
            if (settings.exposureMode == ExposureMode.AUTO) appliedIso ?: settings.iso
            else settings.iso
        Pill(text = "ISO $isoValue", onClick = onIsoClick)

        // Shutter
        val shutterValue =
            if (settings.exposureMode == ExposureMode.AUTO) appliedShutterNs ?: settings.shutterNs
            else settings.shutterNs
        Pill(text = ShutterFormat.format(shutterValue), onClick = onShutterClick)

        // Focus state — tappable only when locked (unlock)
        val (focusLabel, focusColor) = when (focusState) {
            FocusState.IDLE -> "AF ACTIVE" to Color.White.copy(alpha = 0.65f)
            FocusState.TAPPING -> "FOCUSING…" to Color.White
            FocusState.LOCKED -> "FOCUS LOCKED" to Color(0xFF34C759)
            FocusState.FAILED -> "FOCUS FAILED" to Color(0xFFFFCC00)
        }
        Pill(
            text = focusLabel,
            textColor = focusColor,
            onClick = if (focusState == FocusState.LOCKED) onFocusClick else null,
        )
    }
}

@Composable
private fun Pill(
    text: String,
    onClick: (() -> Unit)?,
    textColor: Color = Color.White,
    dotColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

// ── Left sidebar: zoom ──────────────────────────────────────────────────────

@Composable
private fun ZoomSidebar(
    zoomRatio: Float,
    zoomRange: ClosedFloatingPointRange<Float>?,
    onZoomChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minZoom = zoomRange?.start ?: 1f
    val maxZoom = minOf(zoomRange?.endInclusive ?: 10f, ZoomMath.MAX_ZOOM)
        .coerceAtLeast(minZoom + 0.01f)

    Column(
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VerticalZoomSlider(
            value = zoomRatio,
            valueRange = minZoom..maxZoom,
            onValueChange = onZoomChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp, vertical = 12.dp),
        )
        IconButton(onClick = onReset) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Reset zoom to 1x",
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun VerticalZoomSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = remember(valueRange) {
        val base = ZoomMath.presetsFor(valueRange.start, valueRange.endInclusive)
        // The slider's top label should always show the range max (e.g. 10x).
        if (base.lastOrNull() != valueRange.endInclusive) base + valueRange.endInclusive else base
    }
    val logMin = ln(valueRange.start)
    val logMax = ln(valueRange.endInclusive)
    val logSpan = (logMax - logMin).coerceAtLeast(0.0001f)

    fun toFraction(v: Float): Float =
        ((ln(v.coerceIn(valueRange.start, valueRange.endInclusive)) - logMin) / logSpan)
            .coerceIn(0f, 1f)

    fun fromFraction(f: Float): Float =
        exp(logMin + f.coerceIn(0f, 1f) * logSpan)

    BoxWithConstraints(
        modifier = modifier
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val f = 1f - (down.position.y / size.height)
                    onValueChange(fromFraction(f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val nf = 1f - (change.position.y / size.height)
                        onValueChange(fromFraction(nf))
                        change.consume()
                    }
                }
            },
    ) {
        val trackHeight = constraints.maxHeight.toFloat()
        val thumbFraction = toFraction(value)

        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val trackTop = 20.dp.toPx()
            val trackBottom = size.height - 20.dp.toPx()
            val trackLen = trackBottom - trackTop

            // Track
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(cx, trackTop),
                end = Offset(cx, trackBottom),
                strokeWidth = 2.dp.toPx(),
            )

            // Tick marks at presets
            for (preset in presets) {
                val y = trackBottom - toFraction(preset) * trackLen
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(cx - 6.dp.toPx(), y),
                    end = Offset(cx + 6.dp.toPx(), y),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            // Thumb
            val ty = trackBottom - thumbFraction * trackLen
            drawCircle(color = Color.White, radius = 12.dp.toPx(), center = Offset(cx, ty))
            drawCircle(color = Color(0xFF1C1C1E), radius = 7.dp.toPx(), center = Offset(cx, ty))
        }

        // Labels next to tick marks
        val trackTopPx = with(LocalDensity.current) { 20.dp.toPx() }
        val trackBottomPx = trackHeight - trackTopPx
        for (preset in presets) {
            val y = trackBottomPx - toFraction(preset) * (trackBottomPx - trackTopPx)
            Text(
                text = if (preset == preset.toInt().toFloat()) "${preset.toInt()}x"
                else "%.1fx".format(preset),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = with(LocalDensity.current) { y.toDp() } - 8.dp)
                    .padding(start = 1.dp),
            )
        }

        // Current zoom readout near thumb
        val thumbTrackY = trackBottomPx - thumbFraction * (trackBottomPx - trackTopPx)
        Text(
            text = if (value == value.toInt().toFloat()) "${value.toInt()}x"
            else "%.1fx".format(value),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = with(LocalDensity.current) { thumbTrackY.toDp() } - 8.dp)
                .padding(start = 1.dp),
        )
    }
}

// ── Right sidebar: settings / start / focus ─────────────────────────────────

@Composable
private fun ActionSidebar(
    streamState: StreamState,
    isCameraReady: Boolean,
    onStartStop: () -> Unit,
    onSettings: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Color.White,
            )
        }

        StartButton(
            state = streamState,
            isCameraReady = isCameraReady,
            onClick = onStartStop,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )

        IconButton(onClick = onFocus) {
            Icon(
                Icons.Filled.CenterFocusStrong,
                contentDescription = "Focus settings",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun StartButton(
    state: StreamState,
    isCameraReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, color, enabled) = when (state) {
        StreamState.IDLE -> Triple("START", Color(0xFF6C63FF), isCameraReady)
        StreamState.ERROR -> Triple("START", Color(0xFF6C63FF), isCameraReady)
        StreamState.STARTING -> Triple("READY", Color(0xFFFFB74D), true)
        StreamState.RUNNING -> Triple("STOP", Color(0xFFF44336), true)
        StreamState.LIVE -> Triple("STOP", Color(0xFFF44336), true)
        StreamState.STOPPING -> Triple("STOPPING…", Color(0xFF555555), false)
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ── Bottom stats bar ────────────────────────────────────────────────────────

@Composable
private fun StatsBar(
    visible: Boolean,
    onToggle: () -> Unit,
    url: String?,
    stats: StreamStats,
    state: StreamState,
    modifier: Modifier = Modifier,
) {
    if (visible) {
        Surface(color = Color.Black.copy(alpha = 0.62f), modifier = modifier) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url ?: "—",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "%.0f FPS · %.0f kbps · drop %.1f/s · %d viewer(s)".format(
                        stats.sentFps,
                        stats.kbps,
                        stats.droppedFps,
                        stats.counters.clients,
                    ),
                    color = if (state == StreamState.LIVE) Color(0xFF4CAF50)
                    else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = "Hide stats",
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    } else {
        Box(modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Show stats",
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// ── Overlays ────────────────────────────────────────────────────────────────

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
private fun FocusReticle(
    state: FocusState,
    modifier: Modifier = Modifier,
    onHidden: () -> Unit,
) {
    if (state == FocusState.IDLE) return
    val color = when (state) {
        FocusState.TAPPING -> Color(0xFFD0D4D8)
        FocusState.LOCKED -> Color(0xFF34C759)
        FocusState.FAILED -> Color(0xFFFFCC00)
        FocusState.IDLE -> return
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500L)
        onHidden()
    }
    Box(modifier = modifier.size(24.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(16.dp)
                .border(width = 1.5.dp, color = color, shape = CircleShape),
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun StreamState.isActive(): Boolean =
    this == StreamState.STARTING || this == StreamState.RUNNING ||
        this == StreamState.LIVE || this == StreamState.STOPPING
