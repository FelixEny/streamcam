package com.streamcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.streamcam.LensOption
import com.streamcam.camera.AppliedWhiteBalance
import com.streamcam.camera.CameraCapabilities
import com.streamcam.camera.CameraSettings
import com.streamcam.camera.ExposureMode
import com.streamcam.camera.FocusMode
import com.streamcam.camera.WhiteBalanceMode
import com.streamcam.imageproc.IsoStops
import com.streamcam.imageproc.ShutterFormat
import com.streamcam.imageproc.ZoomMath
import com.streamcam.imageproc.ZoomRamp
import com.streamcam.settings.OutputSize
import com.streamcam.settings.StreamSettings
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Resolution + FPS ────────────────────────────────────────────────────────

@Composable
internal fun ResolutionFpsSheet(
    streamSettings: StreamSettings,
    caps: CameraCapabilities?,
    idle: Boolean,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    onCommitStream: () -> Unit,
) {
    SheetScaffold(title = "Resolution & Frame Rate") {
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
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val sizeChoices = buildList {
                add(OutputSize.AUTO to "Auto")
                if (caps?.hasStream(1920, 1080) == true) add(OutputSize.P1080 to "1080p")
                if (caps?.hasStream(1280, 720) == true) add(OutputSize.P720 to "720p")
            }
            for ((size, label) in sizeChoices) {
                FilterChip(
                    selected = streamSettings.outputSize == size,
                    onClick = {
                        onUpdate { s -> s.copy(outputSize = size) }
                        onCommitStream()
                    },
                    enabled = idle,
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ValueLabel(
            if (streamSettings.fps == StreamSettings.FPS_AUTO) "FPS Auto"
            else "FPS ${streamSettings.fps}",
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fpsChoices = StreamSettings.FPS_CHOICES.filter { choice ->
                choice == StreamSettings.FPS_AUTO ||
                    caps?.aeTargetFpsRanges?.any { choice in it.min..it.max } == true
            }
            for (choice in fpsChoices) {
                FilterChip(
                    selected = streamSettings.fps == choice,
                    onClick = {
                        onUpdate { s -> s.copy(fps = choice) }
                        onCommitStream()
                    },
                    label = { Text(if (choice == StreamSettings.FPS_AUTO) "Auto" else "$choice") },
                )
            }
        }
    }
}

// ── White balance + tint ────────────────────────────────────────────────────

@Composable
internal fun WbSheet(
    settings: CameraSettings,
    caps: CameraCapabilities?,
    appliedWb: AppliedWhiteBalance?,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onCommit: () -> Unit,
) {
    val manualAvailable = caps?.supportsCctWhiteBalance == true ||
        caps?.supportsManualWhiteBalance == true

    SheetScaffold(title = "White Balance") {
        if (!manualAvailable) {
            Text("Auto (manual WB not supported)", color = Color.White.copy(alpha = 0.7f))
            return@SheetScaffold
        }

        AutoManualToggle(
            isManual = settings.whiteBalanceMode == WhiteBalanceMode.MANUAL,
            enabled = true,
            onManualChange = { manual ->
                onUpdate {
                    it.copy(
                        whiteBalanceMode =
                            if (manual) WhiteBalanceMode.MANUAL else WhiteBalanceMode.AUTO,
                        whiteBalanceLock = false,
                        kelvin = if (manual) {
                            appliedWb?.sceneCct?.coerceIn(2000, 10000) ?: it.kelvin
                        } else it.kelvin,
                    )
                }
                onCommit()
            },
        )

        when (settings.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> {
                Spacer(Modifier.height(8.dp))
                if (caps?.awbLockAvailable == true) {
                    LabeledSwitch(
                        label = "AWB lock",
                        checked = settings.whiteBalanceLock,
                        onCheckedChange = {
                            onUpdate { it.copy(whiteBalanceLock = !it.whiteBalanceLock) }
                            onCommit()
                        },
                    )
                } else {
                    Text("Auto", color = Color.White.copy(alpha = 0.7f))
                }
            }

            WhiteBalanceMode.MANUAL -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (caps?.supportsCctWhiteBalance == true) "Native CCT"
                    else "Manual WB (approx.)",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))

                val kelvinRange = caps?.cctKelvinRange ?: (2000..10000)
                ValueLabel(
                    buildString {
                        append("Req ${settings.kelvin} K · tint ${settings.tint}")
                        if (appliedWb?.colorTemperature != null) {
                            append("  ·  applied ${appliedWb.colorTemperature} K")
                            if (appliedWb.colorTint != null) append(" · tint ${appliedWb.colorTint}")
                        }
                    },
                )
                Slider(
                    value = settings.kelvin.toFloat()
                        .coerceIn(kelvinRange.first.toFloat(), kelvinRange.last.toFloat()),
                    onValueChange = { v ->
                        onUpdate { s -> s.copy(kelvin = v.toInt().coerceIn(kelvinRange)) }
                    },
                    valueRange = kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                    steps = ((kelvinRange.last - kelvinRange.first) / 100) - 1,
                    onValueChangeFinished = { onCommit() },
                )

                Spacer(Modifier.height(4.dp))
                ValueLabel("Tint ${settings.tint}")
                Slider(
                    value = settings.tint.toFloat(),
                    onValueChange = { v ->
                        onUpdate { s -> s.copy(tint = v.toInt().coerceIn(-50, 50)) }
                    },
                    valueRange = -50f..50f,
                    steps = 99,
                    onValueChangeFinished = { onCommit() },
                )
            }
        }
    }
}

// ── ISO ─────────────────────────────────────────────────────────────────────

@Composable
internal fun IsoSheet(
    settings: CameraSettings,
    caps: CameraCapabilities?,
    appliedIso: Int?,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onCommit: () -> Unit,
) {
    val supportsManual = caps != null &&
        caps.aeModes.contains(0) && // AE_MODE_OFF
        caps.sensitivityRange.first <= caps.sensitivityRange.last

    SheetScaffold(title = "ISO") {
        if (!supportsManual) {
            Text("Manual exposure not supported", color = Color.White.copy(alpha = 0.7f))
            return@SheetScaffold
        }

        AutoManualToggle(
            isManual = settings.exposureMode == ExposureMode.MANUAL,
            enabled = true,
            onManualChange = { manual ->
                onUpdate {
                    it.copy(
                        exposureMode = if (manual) ExposureMode.MANUAL else ExposureMode.AUTO,
                        aeLock = false,
                    )
                }
                onCommit()
            },
        )

        when (settings.exposureMode) {
            ExposureMode.AUTO -> {
                Spacer(Modifier.height(8.dp))
                if (caps.aeLockAvailable) {
                    LabeledSwitch(
                        label = "AE lock",
                        checked = settings.aeLock,
                        onCheckedChange = {
                            onUpdate { s -> s.copy(aeLock = !s.aeLock) }
                            onCommit()
                        },
                    )
                } else {
                    val iso = appliedIso ?: settings.iso
                    Text("Auto · ISO $iso", color = Color.White.copy(alpha = 0.7f))
                }
            }

            ExposureMode.MANUAL -> {
                val isoStops = rememberIsoStops(caps)
                val isoIndex = isoStops.indexOfFirst { it >= settings.iso }
                    .coerceAtLeast(0)
                val requestedIso = isoStops.getOrElse(isoIndex) { settings.iso }
                Spacer(Modifier.height(8.dp))
                ValueLabel("ISO $requestedIso")
                // The HAL quantizes sensitivity to its own gain steps; surface the applied value
                // when it differs so the readout stays honest.
                appliedIso?.takeIf { it != requestedIso }?.let { applied ->
                    Text(
                        "Camera reports ISO $applied",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Slider(
                    value = isoIndex.toFloat(),
                    onValueChange = { idx ->
                        val iso = isoStops.getOrNull(
                            idx.roundToInt().coerceIn(0, isoStops.lastIndex),
                        ) ?: return@Slider
                        onUpdate { it.copy(iso = iso) }
                    },
                    valueRange = 0f..(isoStops.size - 1).coerceAtLeast(0).toFloat(),
                    steps = (isoStops.size - 2).coerceAtLeast(0),
                    onValueChangeFinished = { onCommit() },
                )
            }
        }
    }
}

// ── Shutter ─────────────────────────────────────────────────────────────────

@Composable
internal fun ShutterSheet(
    settings: CameraSettings,
    caps: CameraCapabilities?,
    appliedShutterNs: Long?,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onCommit: () -> Unit,
) {
    val supportsManual = caps != null &&
        caps.aeModes.contains(0) &&
        caps.exposureTimeRange.first <= caps.exposureTimeRange.last

    SheetScaffold(title = "Shutter Speed") {
        if (!supportsManual) {
            Text("Manual exposure not supported", color = Color.White.copy(alpha = 0.7f))
            return@SheetScaffold
        }

        AutoManualToggle(
            isManual = settings.exposureMode == ExposureMode.MANUAL,
            enabled = true,
            onManualChange = { manual ->
                onUpdate {
                    it.copy(
                        exposureMode = if (manual) ExposureMode.MANUAL else ExposureMode.AUTO,
                        aeLock = false,
                    )
                }
                onCommit()
            },
        )

        when (settings.exposureMode) {
            ExposureMode.AUTO -> {
                Spacer(Modifier.height(8.dp))
                val ns = appliedShutterNs ?: settings.shutterNs
                Text(
                    "Auto · ${ShutterFormat.format(ns)}",
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            ExposureMode.MANUAL -> {
                val shutterStops = rememberShutterStops(caps)
                val shutterIndex = shutterStops.indexOfFirst { it >= settings.shutterNs }
                    .coerceAtLeast(0)
                Spacer(Modifier.height(8.dp))
                ValueLabel(
                    ShutterFormat.format(shutterStops.getOrElse(shutterIndex) { settings.shutterNs }),
                )
                Slider(
                    value = shutterIndex.toFloat(),
                    onValueChange = { idx ->
                        val ns = shutterStops.getOrNull(
                            idx.roundToInt().coerceIn(0, shutterStops.lastIndex),
                        ) ?: return@Slider
                        onUpdate { it.copy(shutterNs = ns) }
                    },
                    valueRange = 0f..(shutterStops.size - 1).coerceAtLeast(0).toFloat(),
                    steps = (shutterStops.size - 2).coerceAtLeast(0),
                    onValueChangeFinished = { onCommit() },
                )
            }
        }
    }
}

// ── Zoom ────────────────────────────────────────────────────────────────────

@Composable
internal fun ZoomSheet(
    settings: CameraSettings,
    caps: CameraCapabilities?,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onCommit: () -> Unit,
    onSelectPreset: (Float) -> Unit,
) {
    val minZoom = caps?.zoomRatioRange?.start ?: 1f
    val maxZoom = minOf(caps?.zoomRatioRange?.endInclusive ?: ZoomMath.MAX_ZOOM, ZoomMath.MAX_ZOOM)
        .coerceAtLeast(minZoom + 0.01f)
    val presets = remember(minZoom, maxZoom) {
        val base = ZoomMath.presetsFor(minZoom, maxZoom)
        // Always expose the range max (e.g. 10x) as a quick pick.
        if (base.lastOrNull() != maxZoom) base + maxZoom else base
    }
    // Chip highlight tolerates float noise and the lens's wide end (e.g. 0.973x reads as 1x);
    // arbitrary ratios such as 1.37x still leave every chip unselected.
    val selectedPreset = presets.firstOrNull { abs(it - settings.zoomRatio) < 0.05f }

    SheetScaffold(title = "Zoom") {
        ValueLabel("Current ${formatZoom(settings.zoomRatio)}")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in presets) {
                FilterChip(
                    selected = preset == selectedPreset,
                    onClick = { onSelectPreset(preset) },
                    label = { Text(formatZoom(preset)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Wheel speed (Insta360 Flow remote wheel ramp).
        val wheelRate = settings.zoomWheelRate.coerceIn(ZoomRamp.MIN_RATE, ZoomRamp.MAX_RATE)
        ValueLabel(
            "Wheel speed %.2f · ~%.0f s to 10x".format(
                wheelRate,
                ZoomRamp.secondsToTenX(wheelRate),
            ),
        )
        Slider(
            value = ZoomRamp.sliderFromRate(wheelRate),
            onValueChange = { onUpdate { s -> s.copy(zoomWheelRate = ZoomRamp.rateFromSlider(it)) } },
            valueRange = 0f..1f,
            onValueChangeFinished = { onCommit() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Smoother/slower ramps avoid visible steps on the Flow wheel.",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ── Settings (lens / bitrate / vMix / port) ─────────────────────────────────

@Composable
internal fun SettingsSheet(
    streamSettings: StreamSettings,
    lensOptions: List<LensOption>,
    selectedCameraId: String?,
    lensEnabled: Boolean,
    onUpdateStream: ((StreamSettings) -> StreamSettings) -> Unit,
    onCommitStream: () -> Unit,
    onSelectLens: (String) -> Unit,
) {
    SheetScaffold(title = "Settings") {
        // Lens
        Text("Lens", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (lensOptions.isEmpty()) {
            Text("No cameras found", color = Color.White.copy(alpha = 0.7f))
        } else {
            if (!lensEnabled) {
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
                        enabled = lensEnabled,
                        label = { Text(lens.label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Bitrate
        val mbps = streamSettings.bitrateBps / 1_000_000f
        ValueLabel("Bitrate %.1f Mb/s".format(mbps))
        Slider(
            value = mbps,
            onValueChange = { onUpdateStream { s -> s.copy(bitrateBps = (it * 1_000_000f).toInt()) } },
            valueRange = StreamSettings.MIN_BITRATE_BPS / 1_000_000f..
                StreamSettings.MAX_BITRATE_BPS / 1_000_000f,
            steps = (StreamSettings.MAX_BITRATE_BPS - StreamSettings.MIN_BITRATE_BPS) /
                StreamSettings.BITRATE_STEP_BPS - 1,
            onValueChangeFinished = { onCommitStream() },
        )

        Spacer(Modifier.height(8.dp))

        // vMix host
        var vmixHostText by remember(streamSettings.vmixHost) {
            mutableStateOf(streamSettings.vmixHost)
        }
        OutlinedTextField(
            value = vmixHostText,
            onValueChange = { raw ->
                val clean = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(64)
                vmixHostText = clean
                onUpdateStream { s -> s.copy(vmixHost = clean) }
                onCommitStream()
            },
            label = { Text("vMix host (Web Controller :8088)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(0.7f),
        )

        Spacer(Modifier.height(8.dp))

        // RTSP port
        var portText by remember(streamSettings.port) {
            mutableStateOf(streamSettings.port.toString())
        }
        OutlinedTextField(
            value = portText,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(5)
                portText = digits
                digits.toIntOrNull()?.let { port ->
                    if (port in StreamSettings.MIN_PORT..StreamSettings.MAX_PORT) {
                        onUpdateStream { s -> s.copy(port = port) }
                        onCommitStream()
                    }
                }
            },
            label = { Text("RTSP port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(0.5f),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Bitrate and port apply on the next stream start; all settings persist across restarts.",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ── Focus ───────────────────────────────────────────────────────────────────

@Composable
internal fun FocusSheet(
    settings: CameraSettings,
    caps: CameraCapabilities?,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onCommit: () -> Unit,
) {
    SheetScaffold(title = "Focus") {
        if (caps?.supportsManualFocus != true) {
            Text(
                "Fixed-focus lens — manual focus unavailable",
                color = Color.White.copy(alpha = 0.7f),
            )
            return@SheetScaffold
        }

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
                onCommit()
            },
        )

        if (settings.focusMode == FocusMode.MANUAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap-to-focus is unavailable while manual focus is active — " +
                    "switch to Auto to tap a subject.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            val diopters = settings.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistance)
            ValueLabel(
                if (diopters <= 0.01f) "Focus: infinity (∞)"
                else "Focus: ${"%.1f".format(diopters)} dpt",
            )
            Slider(
                value = diopters,
                onValueChange = { onUpdate { s -> s.copy(focusDistanceDiopters = it) } },
                valueRange = 0f..caps.minFocusDistance,
                onValueChangeFinished = { onCommit() },
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap the preview to focus on a subject.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────

@Composable
private fun SheetScaffold(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun AutoManualToggle(
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
internal fun LabeledSwitch(
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
internal fun ValueLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Shared zoom formatting: whole ratios as "2x", fractional ones as "1.5x". */
internal fun formatZoom(ratio: Float): String {
    // Display granularity is one decimal, so round there first: a lens wide end of 0.9731855
    // reads as "1x" rather than "1.0x", and float noise cannot add a spurious decimal.
    val r = (ratio * 10f).roundToInt() / 10f
    return if (r == r.toInt().toFloat()) "${r.toInt()}x" else "%.1fx".format(r)
}

@Composable
internal fun rememberIsoStops(caps: CameraCapabilities): List<Int> = remember(caps) {
    IsoStops.forRange(caps.sensitivityRange)
}

@Composable
internal fun rememberShutterStops(caps: CameraCapabilities): List<Long> = remember(caps) {
    listOf(
        125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
        10_000_000L, 16_666_667L, 20_000_000L, 25_000_000L, 33_333_333L, 40_000_000L,
        66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L,
        2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L,
    ).filter { it in caps.exposureTimeRange }
        .ifEmpty { listOf(caps.exposureTimeRange.first.coerceAtLeast(1L)) }
}
