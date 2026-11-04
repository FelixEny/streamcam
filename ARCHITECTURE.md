# StreamCam — Architecture

Minimal-processing Android streaming camera: Camera2 → hardware H.264 → RTSP over local Wi-Fi → **vMix** (OBS deferred). No cloud, no accounts, no backend.

Status: Phase 0 ✅. Phase 1 ✅ — camera preview + manual controls (WB/exposure/focus/zoom/lens/minimal-processing) with runtime capability probe, device-verified on Pixel 8 Pro via applied request ops. Phase 2 implemented and transport-verified — `StreamTransport` contract (`:core:common`), `H264Encoder` input-surface encoder (`:core:encoder`), `RtspTransport` video-only server wrapper (`:transport:rtsp`, TCP+UDP per SETUP), dual-target camera session (`CameraEngine.setStreamingSurface`), STREAM start/stop UI with state pill, 1 Hz `METRICS` logcat, WifiLock LOW_LATENCY, stream stops on background and preview reopens on resume. Evidence: `ffprobe` clean over TCP and UDP (H.264 Baseline 4.0, 1920×1080, 30 fps), 300 s TCP soak at 30 fps with 0 decode errors, 2.5 min client at 30 fps while changing zoom/ISO/focus mid-stream, measured ~8.7 Mb/s received. vMix 28 interop confirmed — video-only H.264 accepted over RTSP/TCP (`clients=1`, `state=LIVE`, ~29.97 fps, ~7.98 Mb/s, 0 drops) and over RTSP/UDP; TCP observed smoother in vMix. This required vendoring RTSP-Server 1.4.1: upstream sent `Content-Base: rtsp://:0/` (empty host/port) on DESCRIBE, which vMix rejects as `Invalid Stream Control URI`; the vendored copy (`thirdparty/rtspserver`) passes the server address to the per-client command manager. Preview geometry overhauled and device-verified 2026-10-23: the TextureView applies the documented display-only rotation correction (`-displayRotation`, not `sensor - display`) with a letterbox scale that matches the streamed 16:9 framing (2112×1188 centered in the Pixel's ~2.39:1 view), front lenses are mirrored view-only, and the transform is derived from the live display on every relevant change so a transient `ROTATION_0` cannot leave the preview sideways. Manual WB was reworked from the original display-RGB math to a camera-style pipeline: native CCT (`COLOR_CORRECTION_MODE_CCT`, API 36) when the camera advertises it at runtime (Pixel 8 Pro does not — probed `colorCorrectionModes=[0,1,2]`, `cctRange=null`), otherwise a camera-aware fallback that freezes the HAL's own gains, color transform and scene CCT (from `SENSOR_NEUTRAL_COLOR_POINT`) as the reference when manual is entered, then applies Planckian-relative gains (target == reference reproduces the HAL's calibration), plus a separate −50..+50 green/magenta tint; applied kelvin/tint/gains are read back from capture results and logged throttled. Preview orientation re-verified after relaunch, background/resume and lens switch. Camera session creation now rejects abandoned surfaces instead of crashing (`Surface was abandoned`). Phase 3 ✅ — LIVE pill, stats overlay, lens switching and settings: `CameraSettings` plus stream settings (bitrate 2–20 Mb/s, fps Auto/15/24/30/60 clamped to probed AE ranges, output size Auto/1080p/720p driving preview+stream together, RTSP port) persist across restarts via Jetpack DataStore; bitrate/fps/port apply on the next stream start, while output size and fps reconfigure the idle camera (disabled while streaming). Resolution/fps choices are runtime-detected, never assumed. See `docs/adr/` for decision records. Phase 4 fault-injection results are in `docs/phase4-fault-injection-checklist.md`; the Phase 5 30-minute soak passed on 2026-11-04 (`docs/phase5-soak-results.md`).

Note: Android Camera2 exposes **no public sharpening key** (verified against API 34 — no `SHARPENING_MODE` in `CameraCharacteristics`/`CaptureRequest`). Minimal-processing mode therefore targets NR/edge/face-detect/scene/stabilization/aberration only; vendor sharpening cannot be disabled via the public API.

## 1. Goals and non-goals (MVP)

**Goals**

- Manual white balance (+ lock), exposure ISO/shutter (+ lock), manual focus (+ lock)
- Smooth digital zoom, physical lens switching (runtime-detected)
- 1080p30 default resolution/frame rate, detected from device capabilities
- Persistent LIVE indicator with connection + dropped-frame feedback
- Minimally processed image mode: disable sharpening/NR/edge/face-detect **where the HAL exposes the keys** (no claim of removing vendor processing)
- Low latency, stable frame rate, low CPU, thermal awareness
- Insta360 Flow zoom-wheel support via supported Android input APIs; on-screen zoom always available. Implemented 2026-11-03: the Flow enumerates as a keyboard (`sources=0x101`) and its wheel emits `KEYCODE_ZOOM_IN`/`KEYCODE_ZOOM_OUT`, mapped to a rate-based zoom ramp (`ZoomRamp`, adjustable 0.02–0.6 ln-units/s in Zoom → Wheel speed; default 0.2 ≈ 11.5 s, slowest 0.02 ≈ 115 s, fastest 0.6 ≈ 3.8 s for 1x→10x; release after 120 ms) bounded by the device range (0.49x on the Pixel, so the 0.5x ultrawide works) and the app's 10x ceiling, then consumed (shutter is `KEYCODE_VOLUME_UP` and is deliberately left unhandled — it changes system media volume; the mode button emits `KEYCODE_UNKNOWN`; the trigger emits nothing).

**Non-goals**

- NDI in this release (parked — see ADR-0001), cloud features, custom Windows receiver, RAW video, audio (video-only MVP)

## 2. Module graph

```
:app ────────────── UI (Compose): preview, controls, LIVE/stats, settings, permissions
  │
  ├── :core:camera ──────── Camera2 capture + capability probe + request applier
  ├── :core:imageproc ───── Image math: WB gains, zoom/crop, minimal-processing policy
  ├── :core:encoder ─────── MediaCodec H.264 (input Surface, zero-copy)
  ├── :transport:rtsp ───── StreamTransport impl: RTSP server + RTP (wraps vendored RTSP-Server 1.4.1)
  └── :core:common ──────── Shared contracts (StreamTransport, models), metrics samplers, logging
```

Dependency rules:

- UI depends on modules through their public controllers/models only; no Camera2/MediaCodec types leak into `:app`.
- `:core:camera` and `:core:encoder` know each other only through `android.view.Surface` (the encoder input surface handed to the camera session).
- `:transport:rtsp` implements `StreamTransport` from `:core:common`; the NDI (or any) transport can be added later without touching camera/encoder/UI.
- `:core:imageproc` is pure Kotlin (no Android camera classes) so its math is unit-testable on the JVM.

## 3. Pipeline (video path)

```
Camera HAL ──repeating request──┬── preview Surface (SurfaceTexture → Compose AndroidView)
                                └── MediaCodec input Surface          ← zero CPU copies
                                        │  (hardware encoder, no B-frames, 1s GOP)
                                        ▼  drain thread (NAL access units, Annex-B)
                                  StreamTransport.submit(frame)
                                        ▼
                                  RTSP server :8554  ── RTP/AVP TCP interleaved (default)
                                                       RTP/AVP UDP unicast (option)
                                        ▼
                                  vMix "Add Input → Stream/SRT" → rtsp://<phone-ip>:8554/stream
```

- **No frame copies on the capture path**: camera writes directly into the encoder input surface; encoded NALs are written to sockets as buffer slices (gather writes), never re-buffered whole.
- **Timestamps**: camera `SENSOR_TIMESTAMP` propagates through the surface into encoder PTS → RTP 90 kHz timestamp. Latency and pacing metrics derive from these.
- **Preview geometry**: `TextureView` stretches its buffer to its bounds, so `PreviewTransform.geometry()` (`:core:camera`, unit-tested) derives the rotation *and* the letterbox scale in one pass: the rotation is the documented display-only correction `-displayRotation` ([camera-preview docs](https://developer.android.com/media/camera/camera2/camera-preview); display rotations are reported counterclockwise), and the scale is the largest correction that keeps the 16:9 buffer aspect inside the ~2.39:1 view — a centered letterbox that matches the streamed framing vMix sees (Pixel 8 Pro: rendered 2112×1188, ~364 px black bars per side, measured from screenshots). A quarter-turn rotation transposes the content footprint, so the scale branches on the *applied rotation's* parity. Front lenses add a view-only horizontal mirror; the stream stays unmirrored. Matrix order is `postRotate` → `postScale` → optional mirror (Android's `post*` methods prepend, so that call order applies rotate-then-scale-then-mirror to the point). Rotation is read from the *current* display when the transform is applied (not cached at camera open, which could latch a transient `ROTATION_0` and leave the preview sideways), and the transform is re-applied on attach, layout-size, display-rotation, preview-size, sensor and lens changes. Device-verified on Pixel 8 Pro 2026-10-23: upright back and front previews, cold start, background/resume, lens switch (`rotation=270 … mirrored=true/false` in logcat), and an RTSP frame pulled over Wi-Fi matching the preview orientation with the capture path untouched. `ROTATION_270` is unreachable while the activity is fixed-landscape and is covered by unit tests instead.

### Threading

| Thread | Work |
|---|---|
| main | Compose UI, StateFlow collection |
| `camera` (HandlerThread) | camera open/close, session, `setRepeatingRequest`, all `CaptureRequest` updates |
| `encoder-drain` (HandlerThread) | `dequeueOutputBuffer` loop, flags/SPS-PPS capture, submit to transport |
| RTSP server I/O (library coroutines/sockets) | RTSP handshake, RTP send, per-client queues |
| app `monitorScope` (Dispatchers.Default) | CPU/thermal/network stats sampling |

Backpressure contract: `StreamTransport.submit()` is non-blocking; if a client cannot keep up, frames are dropped at the transport boundary (counted), and the transport requests an IDR frame (`encoder.requestSyncFrame()`) after congestion so receivers recover without a frozen image.

## 4. Control plane (manual controls)

UI intent → `CameraViewModel` → `CameraEngine.apply(settings)` → single `CaptureRequest` build → `setRepeatingRequest`.

- All controls are applied as repeating-request updates (no session rebuild): AE/AWB/AF modes + values, zoom crop region, minimal-processing flags.
- Session is rebuilt only for: camera/lens switch, resolution or frame-rate change, encoder (re)start.
- **Runtime capability detection** (`CapabilityProbe`): every feature is read from `CameraCharacteristics` for the *currently open* camera. Unsupported controls are hidden, not disabled-but-broken. Never assume Pixel model parity.
- **Persistence**: control settings and stream settings are stored with Jetpack DataStore (`SettingsRepository` in `:app`, pure codec in `SettingsCodec`); values are clamped to the probed ranges of the currently open camera on load and on every update, so a value saved on a device with wider ranges can never produce an invalid request.

Probed: stream configs for 1080p30, FPS ranges, AE/AWB/AF available modes + ranges, AE/AWB lock availability, AF region count (`CONTROL_MAX_REGIONS_AF`), zoom ratio range, physical camera IDs + focal lengths, NR/edge/sharpness/face-detect mode lists (minimal-processing support), hardware level.

## 5. Image policy (`:core:imageproc`)

- **Minimal processing mode** (user toggle): request `NOISE_REDUCTION_MODE_OFF`, `SHARPENING_MODE_OFF`, `EDGE_MODE_OFF`, face detect off, scene mode off, video stabilization off, aberration correction off — only keys the device reports as available. Documented as "disables processing the HAL exposes".
- **White balance**: native CCT mode first (`COLOR_CORRECTION_MODE_CCT` + kelvin + tint, API 36) when the open camera advertises the mode and a kelvin range at runtime. Otherwise the fallback: on switching to manual the engine freezes the HAL's current calibration (learned `COLOR_CORRECTION_TRANSFORM` + `COLOR_CORRECTION_GAINS` + scene CCT from `SENSOR_NEUTRAL_COLOR_POINT`) as a `ManualWbReference`, then requests `AWB_MODE_OFF` + `TRANSFORM_MATRIX` with the frozen transform and Planckian-relative gains (target kelvin == reference kelvin reproduces the HAL gains). Tint (−50..+50, green/magenta) scales the green channel against R/B reciprocally. Applied kelvin/tint/gains are read back from capture results (`onWbResult`), logged throttled — never per-frame. Manual mode is hidden entirely when neither CCT nor `MANUAL_POST_PROCESSING` is available; auto mode uses `AWB_LOCK`.
- **Exposure**: manual = `AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` (device ranges probed); auto-lock = `AE_LOCK`.
- **Focus**: `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` (diopters, probed range); auto mode uses continuous AF. **Tap-to-focus** (Auto mode only; the Focus tab explains why it is unavailable while manual focus is active): a tap maps view coordinates → sensor active-array via `FocusMapper`, which inverts the preview transform in the exact reverse order (**unmirror → unscale → unrotate**), rejects taps in the letterboxed black bars, honours the live `SCALER_CROP_REGION` (zoom-aware), and returns null when `CONTROL_MAX_REGIONS_AF` is 0 — no fabricated point focus. The engine then sends the repeating request with `AF_MODE_AUTO` (the documented one-shot mode; regions only when advertised, trigger always IDLE) and ONE capture carrying `AF_TRIGGER_START`; on `AF_STATE_FOCUSED_LOCKED` it reads the actual `CaptureResult.LENS_FOCUS_DISTANCE` and holds it by re-planning with `AF_MODE_OFF` + explicit `LENS_FOCUS_DISTANCE` (never guessing; `CONTROL_AF_LOCK` was removed from API 36). `AF_STATE_NOT_FOCUSED_LOCKED` reports FAILED and returns to continuous AF — failure is never shown as success. A re-hunting HAL invalidates the hold. UI: reticle at the tap (grey hunting / green locked / amber failed, hides after 2.5 s) plus a persistent status chip (`AF ACTIVE` / `FOCUS LOCKING` / `FOCUS LOCKED` + dedicated **Unlock Focus** button / `FOCUS FAILED`); tapping a new subject while locked re-focuses and re-locks without unlocking first. Touch handling ignores drags, multi-touch and cancelled gestures. On the Pixel 8 Pro both logical cameras report `CONTROL_MAX_REGIONS_AF = 1`, so taps send a real region (verified through the normal tap path: near subject vs far background locked at 3.99–4.17 vs 5.13–5.27 dioptres, repeatable; lock held at `AF_MODE_OFF` + distance, unlock restores `AF_MODE` 3; streaming stayed at 30 fps/0 drops during focus changes). Clearing regions must unset `CONTROL_AF_REGIONS` (null) — an empty `MeteringRectangle[]` is rejected by this HAL. Physical cameras report one region each but cannot be targeted: they are not directly openable and the logical session does not expose `CONTROL_AF_REGIONS`/`CONTROL_AF_MODE` for them. See `docs/focus-diagnostics-pixel8pro.md`.
- **Zoom**: `CONTROL_ZOOM_RATIO` (`SetZoomRatio`) on the logical camera when advertised, else `SCALER_CROP_REGION` digital crop. The app bounds zoom to the device range capped at `ZoomMath.MAX_ZOOM` = 10x (pure digital beyond the 5x optical is unusable for streaming) — Pixel 8 Pro device range `0.4944783..30.0`, so the 0.5x ultrawide is reachable. Wheel input is rate-based (`ZoomRamp`, adjustable 0.02–0.6 ln-units/s via the log-scaled "Wheel speed" slider in the Zoom tab; persisted in camera settings; default 0.2 ≈ 11.5 s, slowest 0.02 ≈ 115 s for 1x→10x; 120 ms release) applied at 50 ms ticks and persisted once on settle; slider changes are immediate. The Zoom tab also has 0.5x/1x/2x/5x presets (`ZoomMath.ZOOM_PRESETS`, filtered to the camera's range), verified on device: 5.0 → tele 18 mm, 2.0 → main digital, 1.0 → main, 0.5 → ultrawide 2.23 mm. Verified 2026-11-03 by logging `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` while stepping zoom: 0.83x → physical 3, focal 2.23 mm (ultrawide); 1.0x → physical 2, focal 6.9 mm (main); 2.13x → physical 5, focal 6.9 mm (same main lens, digital crop); 5.29x → physical 4, focal 18.0 mm (periscope tele). Wheel run reached 10.0x in ~2.4 s and held the cap.

## 6. Transport contract

```kotlin
interface StreamTransport {
    val state: StateFlow<TransportState>      // STOPPED / STARTING / LIVE / ERROR
    val stats: StateFlow<TransportStats>      // clients, bytes, drops, bitrate, errors
    fun start(config: TransportConfig)        // port, path, SPS/PPS, dimensions, fps
    fun submit(frame: EncodedFrame): Boolean  // non-blocking; false = dropped
    fun stop()
}
```

vMix pulls with *Add Input → Stream/SRT → RTSP over TCP* (or UDP), URL `rtsp://<phone-ip>:8554/stream`. Implementation: RTSP-Server 1.4.1, vendored in `:thirdparty:rtspserver` with one upstream bug fixed (per-client DESCRIBE advertised `Content-Base: rtsp://:0/`, rejected by vMix) — see ADR-0002; custom packetizer/server remains the documented fallback if backpressure misbehaves.

## 7. LIVE state, metrics, failure handling

- `TransportState.LIVE` when ≥1 RTSP client is PLAYING; pill shows LIVE, client count, fps, drops, bitrate.
- Metrics (1 Hz sampled): encoder fps/bitrate, dropped frames (transport + camera-vs-encoder delta), plus device diagnostics from `DeviceMetrics`: CPU% (`Process.getElapsedCpuTime()` deltas, one-core = 100), thermal status (`PowerManager.currentThermalStatus`, API 29+), battery temperature, Wi-Fi RSSI/link speed (`WifiInfo`; the platform may redact RSSI without location access — logged as-is, no extra permissions requested).
- Capture-to-encode latency (`LatencyTracker`, unit-tested): each encoded frame's `BufferInfo.presentationTimeUs` carries the input Surface timestamp; the age is sampled against `elapsedRealtimeNanos()` or `nanoTime()` (whichever lands in the plausible window, because Pixel HALs may use `CLOCK_MONOTONIC` even when Camera2 reports a REALTIME source) and reported as p50/p95/max in the 1 Hz `METRICS` line. This is device-side pipeline latency (sensor → encoded NAL), not glass-to-glass.
- Failures: camera `onError`/`onDisconnected` → banner + bounded exponential backoff reopen (5 attempts, 500 ms → 8 s, reset on success/resume/lens switch); a mid-stream camera failure stops the stream cleanly (encoder has no frames) instead of leaving frozen video. Network: a default-network callback halts the stream when Wi-Fi is lost or left for cellular (intent kept) and auto-restarts when Wi-Fi returns, the app is foregrounded and the user still wants it; RTSP client loss → stay RUNNING (server waits for next PLAY).
- Thermal: `ThermalLadder` (pure, unit-tested) degrades the live encoder via `MediaCodec` `PARAMETER_KEY_VIDEO_BITRATE` — MODERATE → 75% of the configured bitrate, SEVERE and above → 50%, restore only after 5 consecutive cool samples — and requests an IDR so the change takes effect. A rejected bitrate change is logged and never stops the stream.
- Flow diagnostics: `InputDiag` logs every key/motion-axis event at the activity entry point plus all input devices (id, name, sources), so gimbal support is decided from observed HID events rather than assumptions. Log-only; nothing is consumed.
- Lifecycle: `onStop` (background/screen off) → **stop transport + close camera** (decision: clean stop, no foreground service). `FLAG_KEEP_SCREEN_ON` keeps the display awake while the app is foregrounded (pressing power still sleeps it). Streaming output is landscape-locked (no GPU rotation pass in MVP).
- vMix bus indicator: while streaming with a vMix host configured (Stream tab, Web Controller port 8088; cleartext HTTP allowed), the app polls `http://<host>:8088/api/` at 2 Hz, matches our RTSP input by URL/title (`VmixXml`), and draws a **red border when the input is on Program/Live and a green border when it is in Preview**; no border otherwise. Polling stops with the stream and errors are throttled. Verified on device 2026-11-03 (PROGRAM → RGB 255,59,48 border; PREVIEW → 52,199,89). A reachable vMix whose input titles match nothing used to be indistinguishable from an unconfigured host; it now logs the candidates and the real titles once per transition (`vmix host=… reachable but no input matched candidates=[…] inputs=[…]`), verified 2026-11-04 with a fake Web Controller API.
- Wi-Fi: `WifiLock(FULL_LOW_LATENCY)` held while streaming.

## 8. Toolchain

- JDK 17 (Temurin, user-scope `C:\Users\Client\tools\jdk-17.0.20.1+1`, `JAVA_HOME` set for user)
- Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platforms 33/34/36, build-tools 34.0.0), `local.properties` points at it
- Gradle wrapper **8.13** (`gradlew`), AGP **8.13.2**, Kotlin **2.3.20** (forced upward by RootEncoder's kotlin-stdlib 2.3.20 — see ADR-0002), compileSdk **36** (API-36 CCT keys), targetSdk **34**, minSdk **26**
- Build: `gradlew assembleDebug` · Tests: `gradlew test` · Lint: `gradlew lint`

## 9. Phases and gates

| Phase | Deliverable | Gate |
|---|---|---|
| 0 ✅ | Multi-module skeleton, this doc, ADRs | `assembleDebug` green |
| 1 ✅ | Camera preview + all manual controls (no streaming) | Controls verified on Pixel via adb |
| 2 ✅ | Encoder + RTSP transport, minimal debug UI | `ffprobe` clean ✅ · 300 s soak ✅ · vMix 28 ≥5 min over TCP (~10 min, 0 drops) ✅ · vMix over UDP ✅ |
| 3 ✅ | LIVE pill, stats overlay, lens switching, settings (DataStore persistence + bitrate/fps/output-size/port) | 1080p30 stable with UI live; drops <0.5% ✅ (30 fps, 0 drops mid-stream; settings verified across restarts on Pixel 8 Pro) |
| 4 | Resilience (background/Wi-Fi/thermal) + Flow input diagnostics | Fault-injection checklist passes |
| 5 ✅ | 30-min soak + latency/CPU/thermal numbers | Documented results ✅ (30 min: 30 fps, 0 drops, capture-to-encode p50 121.8 ms, CPU 50.2%, battery ≤44.5 °C, thermal NONE throughout) |
| 6 | README, vMix setup guide, limitations | Docs match measured behavior |

Phase 4 implementation landed 2026-11-02 (device metrics, thermal degrade ladder, camera backoff, Wi-Fi loss/recovery, Flow input diagnostics; unit-tested off-device). The gate stays open until the fault-injection checklist has been run on the Pixel — see `docs/phase4-fault-injection-checklist.md`.

Phase 5 harness: `scripts/phase5-soak.ps1 -DurationMin 30` starts the stream, runs a TCP ffmpeg client, raises the logcat ring buffer to 16 MB (so a 30-minute run survives an adb hiccup), then writes throughput/drop/latency/CPU/thermal/battery/Wi-Fi summaries. First run 2026-11-04 passed: 54,146 frames submitted and 53,953 decoded with 0 drops, capture-to-encode p50 121.8 ms / p95 129.7 ms, CPU 50.2%, battery 40.4 → 44.5 °C, thermal NONE throughout, no camera/transport errors. Results: `docs/phase5-soak-results.md`.

## 10. Risks (top)

1. RTSP library interop/backpressure with vMix → ADR-0002 fallback to custom server behind the same interface.
2. Manual-control availability varies per Pixel → probe + hide, never assume.
3. Physical lens IDs may refuse direct sessions → fallback to logical camera + zoom-ratio lens selection.
4. Wi-Fi power save/jitter → WifiLock, 5 GHz, AP settings documented.
5. Thermal throttle in 30-min soak → bitrate setting + degrade ladder (Phase 4/5).
