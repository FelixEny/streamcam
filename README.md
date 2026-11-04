# StreamCam

Minimal-processing Android streaming camera: **Camera2 → hardware H.264 → RTSP over local Wi-Fi → vMix**. No cloud, no accounts, no backend. Video-only MVP.

Built and verified on a Google Pixel 8 Pro (Android 17). Every feature is runtime-probed from `CameraCharacteristics`, so unsupported controls are hidden rather than broken.

## What it does

- **Live RTSP stream** to vMix (`rtsp://<phone-ip>:8554/stream`, TCP by default, UDP optional), 1080p30 at ~8 Mb/s, hardware H.264, 1 s GOP.
- **Manual controls**: white balance (CCT when the HAL supports it, otherwise learned-transform), exposure (ISO/shutter), focus (AF / tap-to-focus / manual distance), zoom (0.5x ultrawide … 10x, wheel-ramp), lens switching (runtime-detected physical cameras).
- **Tap-to-focus** with one-shot AF, a measured focus lock, honest LOCKED/FAILED states, and an on-screen reticle + status chip.
- **LIVE pill** with client count, fps, drops and bitrate; **vMix bus border** (red = on air, green = preview) polled from vMix's Web Controller.
- **Insta360 Flow** zoom-wheel support (rate-adjustable, persisted).
- **Resilience**: stops cleanly on background/screen off, halts on Wi-Fi loss and auto-restarts on return, port-conflict and client-storm handling, thermal degrade ladder, bounded camera reopen.
- **Diagnostics**: 1 Hz `METRICS` logcat (fps, bitrate, drops, capture-to-encode latency p50/p95/max, CPU, thermal, battery, Wi-Fi), plus fault-injection and soak harnesses.

## Measured behaviour (Pixel 8 Pro, 30-min soak)

| Metric | Value |
|---|---|
| Resolution / fps | 1920×1080 @ 30 |
| Bitrate | ~7.95 Mb/s avg (9.69 Mb/s IDR peak) |
| Frames submitted / decoded (30 min) | 54,146 / 53,953 |
| Dropped frames | 0 (in-app and at the client) |
| Capture → encode latency | p50 121.8 ms / p95 129.7 ms / max 158 ms |
| CPU | ~50 % of one core (max 64 %) |
| Battery temperature | 40.4 → 44.5 °C, plateau ~43 °C |
| Thermal status | NONE for all 1,798 sampled seconds |
| Wi-Fi | −31 dBm avg, 65 Mbps link |

Full numbers: [`docs/phase5-soak-results.md`](docs/phase5-soak-results.md).

## Requirements

- Android device with Camera2 support (tested on Pixel 8 Pro; other devices probed at runtime).
- PC and phone on the same LAN; vMix 28+ on the PC.
- JDK 17, Android SDK (compileSdk 36), Gradle 8.

## Build

```powershell
$env:JAVA_HOME = "C:\Users\<you>\tools\jdk-17.0.20.1+1"
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Run

1. Install the APK and open StreamCam. Grant camera permission.
2. Tap **Start stream**. The pill shows `LIVE` and the RTSP URL appears (e.g. `rtsp://192.168.0.21:8554/stream`).
3. In vMix: **Add Input → Stream → RTSP over TCP**, paste the URL. See [`docs/vmix-setup.md`](docs/vmix-setup.md).
4. Optional: enter the PC's IP in **Stream → vMix host** to get the red/green bus border.

The app keeps the screen awake while foregrounded; pressing power or backgrounding stops the stream and reopens the preview on return.

## Docs

- [`docs/vmix-setup.md`](docs/vmix-setup.md) — vMix interop and troubleshooting.
- [`docs/limitations.md`](docs/limitations.md) — known limits and non-goals.
- [`docs/focus-diagnostics-pixel8pro.md`](docs/focus-diagnostics-pixel8pro.md) — Pixel 8 Pro tap-to-focus hardware results.
- [`docs/face-priority-pixel8pro.md`](docs/face-priority-pixel8pro.md) — face-detection / FACE_PRIORITY investigation (not adopted).
- [`docs/phase4-fault-injection-checklist.md`](docs/phase4-fault-injection-checklist.md) — resilience fault-injection results.
- [`docs/phase5-soak-results.md`](docs/phase5-soak-results.md) — 30-minute soak numbers.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — full architecture and phases.

## Non-goals (MVP)

NDI, cloud features, custom Windows receiver, RAW video, audio (video-only), foreground service, ML-based subject tracking, simulated background blur.

## License

Not specified.