# Phase 5 soak harness.
#
# Starts StreamCam, runs a live RTSP client for the requested duration, then dumps the on-device
# logcat ring buffer (size raised to 16 MB so a 30-minute run fits) and writes a summary.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/phase5-soak.ps1 -DurationMin 30
#   powershell -ExecutionPolicy Bypass -File scripts/phase5-soak.ps1 -DurationMin 2 -SkipInstall
#
# The script never changes production settings. It force-stops camera apps, launches StreamCam,
# taps Start/Stop through uiautomator (no hardcoded coordinates) and leaves the app stopped.

[CmdletBinding()]
param(
    [string]$Serial = "39081FDJG00F81",
    [int]$DurationMin = 30,
    [string]$OutDir = "docs\soak",
    [string]$Ffmpeg = "",
    [switch]$SkipInstall,
    [switch]$NoClient,
    [int]$StartupTimeoutSec = 90
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- tools -------------------------------------------------------------------------------------

$adbExe = if ($env:ADB -and (Test-Path $env:ADB)) {
    $env:ADB
} elseif (Test-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")) {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
} else {
    (Get-Command adb -ErrorAction Stop).Source
}

$ffmpegExe = $Ffmpeg
if (-not $ffmpegExe) {
    $cmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($cmd) { $ffmpegExe = $cmd.Source }
}
if (-not $ffmpegExe) {
    $known = "C:\Users\Client\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin\ffmpeg.exe"
    if (Test-Path $known) { $ffmpegExe = $known }
}
if (-not $NoClient -and -not $ffmpegExe) {
    throw "ffmpeg not found; pass -Ffmpeg <path> or -NoClient"
}

function adb {
    & $adbExe -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed ($LASTEXITCODE)" }
}

function adb-quiet {
    & $adbExe -s $Serial @args 2>$null | Out-Null
}

function Get-UiXml {
    adb shell "uiautomator dump /data/local/tmp/phase5-ui.xml" | Out-Null
    return ((adb exec-out cat /data/local/tmp/phase5-ui.xml) -join "`n")
}

function Get-NodeCenter([string]$xml, [string]$text) {
    $pattern = 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) { return $null }
    return [pscustomobject]@{
        X = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        Y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    }
}

function Tap-Text([string]$text, [int]$timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        $node = Get-NodeCenter (Get-UiXml) $text
        if ($node) {
            adb shell "input tap $($node.X) $($node.Y)" | Out-Null
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Field([string]$line, [string]$name) {
    $m = [regex]::Match($line, [regex]::Escape($name) + '=([^\s]+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Numbers($lines, [string]$name) {
    $values = New-Object System.Collections.Generic.List[double]
    foreach ($line in $lines) {
        $raw = Field $line $name
        if ($raw -and $raw -ne "na" -and $raw -match '^-?\d+(\.\d+)?$') {
            $values.Add([double]$raw)
        }
    }
    return $values
}

function Describe($values, [string]$unit = "") {
    if ($values.Count -eq 0) { return "n/a" }
    $avg = ($values | Measure-Object -Average).Average
    $min = ($values | Measure-Object -Minimum).Minimum
    $max = ($values | Measure-Object -Maximum).Maximum
    return ("avg {0:N1}{3} / min {1:N1}{3} / max {2:N1}{3}" -f $avg, $min, $max, $unit)
}

# --- setup -------------------------------------------------------------------------------------

if (-not $SkipInstall) {
    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        throw "APK missing: $apk - run gradlew :app:assembleDebug first"
    }
    Write-Host "Installing $apk"
    adb install -r $apk | Out-Null
}

Write-Host "Resetting device state"
adb-quiet reverse "--remove-all"
adb-quiet shell "input keyevent 224"
adb-quiet shell "wm dismiss-keyguard"
adb-quiet shell "am force-stop com.streamcam"
adb-quiet shell "am force-stop com.google.android.GoogleCamera"
adb-quiet shell "am force-stop com.android.camera"
adb-quiet shell "svc power stayon usb"
adb-quiet logcat -G 16M
adb-quiet logcat -c

adb shell "am start -n com.streamcam/.MainActivity" | Out-Null
Start-Sleep -Seconds 3

$deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
while ($true) {
    $open = ((adb logcat -d -s StreamMetrics) -join "`n")
    if ($open -match "open camera=") { break }
    if ((Get-Date) -gt $deadline) { throw "camera did not open within $StartupTimeoutSec s" }
    Start-Sleep -Seconds 1
}

if (-not (Tap-Text "Start stream" 15)) {
    throw "Start stream button not found"
}

$deadline = (Get-Date).AddSeconds(20)
$url = $null
while ($true) {
    $ui = Get-UiXml
    $m = [regex]::Match($ui, 'text="(rtsp://\d+\.\d+\.\d+\.\d+:\d+/[^"]*)"')
    if ($m.Success) { $url = $m.Groups[1].Value; break }
    $logs = ((adb logcat -d -s StreamMetrics RtspServer) -join "`n")
    if ($logs -match "Server started:") {
        $ip = [regex]::Match($logs, 'Server started: (\d+\.\d+\.\d+\.\d+)')
        if (-not $ip.Success) {
            $ip = [regex]::Match(((adb shell "ip -f inet addr show wlan0") -join "`n"), 'inet\s+(\d+\.\d+\.\d+\.\d+)')
        }
        if ($ip.Success) { $url = "rtsp://$($ip.Groups[1].Value):8554/stream"; break }
    }
    if ((Get-Date) -gt $deadline) { throw "stream URL not found" }
    Start-Sleep -Seconds 1
}
Write-Host "Stream URL: $url"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$logPath = Join-Path $OutDir "soak-$stamp.log"
$progressPath = Join-Path $OutDir "client-$stamp.progress"
$clientErr = Join-Path $OutDir "client-$stamp.err"
$summaryPath = Join-Path $OutDir "summary-$stamp.md"

# --- run ---------------------------------------------------------------------------------------

$seconds = [Math]::Max(1, $DurationMin * 60)
$client = $null
$clientExit = $null
if (-not $NoClient) {
    Write-Host "Starting ffmpeg client for $DurationMin min (TCP decode to null)"
    $ffArgs = @(
        "-hide_banner", "-loglevel", "warning", "-nostats",
        "-progress", $progressPath,
        "-rtsp_transport", "tcp",
        "-i", $url,
        "-t", "$seconds",
        "-f", "null", "-"
    )
    $client = Start-Process -FilePath $ffmpegExe -ArgumentList $ffArgs -PassThru -NoNewWindow `
        -RedirectStandardError $clientErr
}

$endAt = (Get-Date).AddSeconds($seconds)
while ((Get-Date) -lt $endAt) {
    if ($client -and $client.HasExited) {
        $clientExit = $client.ExitCode
        Write-Warning "ffmpeg exited early (code $clientExit); continuing the device soak"
        break
    }
    Start-Sleep -Seconds 5
}

if ($client -and -not $client.HasExited) {
    $client.WaitForExit(30000) | Out-Null
    if (-not $client.HasExited) { $client.Kill() }
}
if ($client -and $client.HasExited) {
    try { $clientExit = $client.ExitCode } catch { $clientExit = "killed" }
}

Write-Host "Stopping stream"
Tap-Text "Stop" 10 | Out-Null
Start-Sleep -Seconds 3

# Pull the on-device ring buffer; raised to 16 MB so the whole run survives adb hiccups.
adb logcat -d -v time -s StreamMetrics StreamCamera RtspServer H264Encoder | Out-File -Encoding utf8 $logPath

# --- summarize ---------------------------------------------------------------------------------

$lines = Get-Content $logPath
$metrics = @($lines | Where-Object { $_ -match 'METRICS ' })

$encFps = Numbers $metrics "enc_fps"
$sentFps = Numbers $metrics "sent_fps"
$droppedFps = Numbers $metrics "dropped_fps"
$kbps = Numbers $metrics "kbps"
$cpu = @(Numbers $metrics "cpu_pct" | Where-Object { $_ -gt 0 })
$battery = Numbers $metrics "battery_c"
$rssi = Numbers $metrics "wifi_rssi"
$link = Numbers $metrics "wifi_mbps"
$latP50 = Numbers $metrics "lat_p50_ms"
$latP95 = Numbers $metrics "lat_p95_ms"
$latMax = Numbers $metrics "lat_max_ms"
$latN = Numbers $metrics "lat_n"

$thermalGroups = @($metrics | ForEach-Object { Field $_ "thermal" } |
    Where-Object { $_ } | Group-Object | Sort-Object Name | ForEach-Object {
        "thermal=$($_.Name): $($_.Count)s"
    }) -join ", "

$finalDropped = @($metrics | ForEach-Object { Field $_ "dropped_total" } |
    Where-Object { $_ -match '^\d+$' } | ForEach-Object { [long]$_ }) |
    Select-Object -Last 1
$finalSent = @($metrics | ForEach-Object { Field $_ "sent_total" } |
    Where-Object { $_ -match '^\d+$' } | ForEach-Object { [long]$_ }) |
    Select-Object -Last 1

$liveTicks = @($metrics | Where-Object { $_ -match 'state=LIVE' }).Count
$errorLines = @($lines | Where-Object {
    $_ -match 'camera error|reopen attempt|SessionFailed|rejected request'
})
$reconnects = @($lines | Where-Object { $_ -match 'reopen attempt|reconnected' }).Count

$clientSummary = "n/a"
if (Test-Path $progressPath) {
    $progress = Get-Content $progressPath -ErrorAction SilentlyContinue
    if ($progress) {
        $get = {
            param($name)
            $hit = @($progress | Where-Object { $_ -match "^$name=" }) | Select-Object -Last 1
            if ($hit) { return ($hit -replace "^$name=", "") }
            return "n/a"
        }
        $clientSummary = "frames=$(& $get 'frame') fps=$(& $get 'fps') " +
            "out_time=$(& $get 'out_time') drop_frames=$(& $get 'drop_frames') " +
            "bitrate=$(& $get 'bitrate') exit=$clientExit"
    }
}

$duration = if ($metrics.Count -gt 0) { $metrics.Count } else { 0 }
$summary = @"
# Phase 5 soak - $stamp

- Duration requested: $DurationMin min
- Metric ticks: $duration (1 Hz)
- Stream URL: $url
- ffmpeg: $clientSummary

## Throughput and pacing

| Metric | Value |
|---|---|
| enc_fps | $(Describe $encFps) |
| sent_fps | $(Describe $sentFps) |
| dropped_fps | $(Describe $droppedFps) |
| bitrate | $(Describe $kbps " kb/s") |
| LIVE ticks (client connected) | $liveTicks / $duration |
| sent_total (final) | $finalSent |
| dropped_total (final) | $finalDropped |

## Capture-to-encode latency

| Metric | Value |
|---|---|
| p50 per tick | $(Describe $latP50 " ms") |
| p95 per tick | $(Describe $latP95 " ms") |
| max | $(Describe $latMax " ms") |
| samples (final tick) | $(@($latN | Select-Object -Last 1)) |

## Device

| Metric | Value |
|---|---|
| CPU (one core = 100%) | $(Describe $cpu "%") |
| battery temperature | $(Describe $battery " C") |
| Wi-Fi RSSI | $(Describe $rssi " dBm") |
| Wi-Fi link | $(Describe $link " Mbps") |
| thermal time | $thermalGroups |

## Health

- Camera/transport error lines: $($errorLines.Count)
- Camera reopen triggers: $reconnects

Error lines (if any):
$($errorLines -join "`n")

Raw log: $logPath
"@

$summary | Out-File -Encoding utf8 $summaryPath
Write-Host ""
Write-Host $summary
Write-Host "Summary: $summaryPath"
Write-Host "Raw log: $logPath"
