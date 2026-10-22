# StreamCam — Architecture

Minimal-processing Android streaming camera: Camera2 → hardware H.264 → RTSP over local Wi-Fi → **vMix** (OBS deferred). No cloud, no accounts, no backend.

Status: Phase 0 ✅. Phase 1 ✅ — camera preview + manual controls (WB/exposure/focus/zoom/lens/minimal-processing) with runtime capability probe, device-verified on Pixel 8 Pro via applied request ops. Phase 2 implemented and transport-verified — `StreamTransport` contract (`:core:common`), `H264Encoder` input-surface encoder (`:core:encoder`), `RtspTransport` video-only server wrapper (`:transport:rtsp`, TCP+UDP per SETUP), dual-target camera session (`CameraEngine.setStreamingSurface`), STREAM start/stop UI with state pill, 1 Hz `METRICS` logcat, WifiLock LOW_LATENCY, stream stops on background and preview reopens on resume. Evidence: `ffprobe` clean over TCP and UDP (H.264 Baseline 4.0, 1920×1080, 30 fps), 300 s TCP soak at 30 fps with 0 decode errors, 2.5 min client at 30 fps while changing zoom/ISO/focus mid-stream, measured ~8.7 Mb/s received. vMix 28 interop confirmed — video-only H.264 accepted over RTSP/TCP (`clients=1`, `state=LIVE`, ~29.97 fps, ~7.98 Mb/s, 0 drops) and over RTSP/UDP; TCP observed smoother in vMix. This required vendoring RTSP-Server 1.4.1: upstream sent `Content-Base: rtsp://:0/` (empty host/port) on DESCRIBE, which vMix rejects as `Invalid Stream Control URI`; the vendored copy (`thirdparty/rtspserver`) passes the server address to the per-client command manager. Preview aspect fixed after the gate: the TextureView stretched 16:9 into the ~2.39:1 view; `PreviewTransform` now center-crops (fills) and the rotation is read from the live display at draw time, so a transient `ROTATION_0` at launch cannot leave the preview sideways. Manual WB now works through the HAL's learned color-correction matrix: when the camera advertises `MANUAL_POST_PROCESSING` the engine caches the `COLOR_CORRECTION_TRANSFORM` from capture results and the planner emits `AWB_MODE_OFF` + `TRANSFORM_MATRIX` + that matrix + gains (the Pixel ignores gains under `FAST`, while `TRANSFORM_MATRIX` with an unset/identity matrix is black or dark green); the manual control is hidden when the capability is absent, and preview/RTSP frames were measured blue-dominant at 2300 K, neutral at 5500 K and warm-dominant at 9600 K. Preview orientation re-verified after relaunch, background/resume and lens switch. Camera session creation now rejects abandoned surfaces instead of crashing (`Surface was abandoned`). See `docs/adr/` for decision records.

Note: Android Camera2 exposes **no public sharpening key** (verified against API 34 — no `SHARPENING_MODE` in `CameraCharacteristics`/`CaptureRequest`). Minimal-processing mode therefore targets NR/edge/face-detect/scene/stabilization/aberration only; vendor sharpening cannot be disabled via the public API.

## 1. Goals and non-goals (MVP)

**Goals**

- Manual white balance (+ lock), exposure ISO/shutter (+ lock), manual focus (+ lock)
- Smooth digital zoom, physical lens switching (runtime-detected)
- 1080p30 default resolution/frame rate, detected from device capabilities
- Persistent LIVE indicator with connection + dropped-frame feedback
- Minimally processed image mode: disable sharpening/NR/edge/face-detect **where the HAL exposes the keys** (no claim of removing vendor processing)
- Low latency, stable frame rate, low CPU, thermal awareness
- Optional Insta360 Flow zoom-wheel support only via supported Android input APIs; on-screen zoom always available

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
- **Preview geometry**: `TextureView` stretches its buffer to its bounds, so `PreviewTransform` (`:core:camera`, unit-tested) supplies a center-pivot correction scale that *fills* the ~2.39:1 landscape view with the 16:9 buffer (center crop: no letterbox bars, top/bottom of the frame hidden). Preview pixels stay square; the streamed frame is still the full camera frame, so preview and stream crop differently by design. The rotation applied is `sensorOrientation - displayRotation`, read from the *current* display when the transform is applied (not cached at camera open, which could latch a transient `ROTATION_0` and leave the preview sideways). The transform is re-applied on attach, layout-size and display-rotation changes, not only on recomposition.

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

Probed: stream configs for 1080p30, FPS ranges, AE/AWB/AF available modes + ranges, lock availability, zoom ratio range, physical camera IDs + focal lengths, NR/edge/sharpness/face-detect mode lists (minimal-processing support), hardware level.

## 5. Image policy (`:core:imageproc`)

- **Minimal processing mode** (user toggle): request `NOISE_REDUCTION_MODE_OFF`, `SHARPENING_MODE_OFF`, `EDGE_MODE_OFF`, face detect off, scene mode off, video stabilization off, aberration correction off — only keys the device reports as available. Documented as "disables processing the HAL exposes".
- **White balance**: kelvin → RGGB gains (Planckian locus). Manual mode is only offered when the camera reports `MANUAL_POST_PROCESSING` and a real `COLOR_CORRECTION_TRANSFORM` has been learned from capture results; it then sends `AWB_MODE_OFF`, `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, the learned sensor→sRGB matrix and `COLOR_CORRECTION_GAINS`. Gains alone under `FAST` are ignored by the Pixel, and `TRANSFORM_MATRIX` without a real matrix renders black/dark green. Auto mode uses `AWB_LOCK`.
- **Exposure**: manual = `AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` (device ranges probed); auto-lock = `AE_LOCK`.
- **Focus**: `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` (diopters, probed range); auto-lock = AF trigger/lock per capabilities.
- **Zoom**: `SCALER_ZOOM_RATIO` (API 28+) else `SCALER_CROP_REGION`, eased per-frame (Choreographer) for smooth animation.

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
- Metrics (1 Hz sampled): encoder fps/bitrate, dropped frames (transport + camera-vs-encoder delta), Wi-Fi RSSI/link speed, CPU% (`/proc/self/stat`), thermal status (`PowerManager`), battery temperature.
- Failures: camera `onError`/`onDisconnected` → backoff reopen + banner; Wi-Fi loss (NetworkCallback) → stop transport, banner, restart on reconnect; RTSP client loss → stay RUNNING (server waits for next PLAY).
- Lifecycle: `onStop` (background/screen off) → **stop transport + close camera** (decision: clean stop, no foreground service). Screen kept on while the app is streaming. Streaming output is landscape-locked (no GPU rotation pass in MVP).
- Wi-Fi: `WifiLock(FULL_LOW_LATENCY)` held while streaming.

## 8. Toolchain

- JDK 17 (Temurin, user-scope `C:\Users\Client\tools\jdk-17.0.20.1+1`, `JAVA_HOME` set for user)
- Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platforms 33/34, build-tools 34.0.0), `local.properties` points at it
- Gradle wrapper **8.7** (`gradlew`), AGP **8.5.2**, Kotlin **2.3.20** (forced upward by RootEncoder's kotlin-stdlib 2.3.20 — see ADR-0002), compileSdk/targetSdk **34**, minSdk **26**
- Build: `gradlew assembleDebug` · Tests: `gradlew test` · Lint: `gradlew lint`

## 9. Phases and gates

| Phase | Deliverable | Gate |
|---|---|---|
| 0 ✅ | Multi-module skeleton, this doc, ADRs | `assembleDebug` green |
| 1 ✅ | Camera preview + all manual controls (no streaming) | Controls verified on Pixel via adb |
| 2 ✅ | Encoder + RTSP transport, minimal debug UI | `ffprobe` clean ✅ · 300 s soak ✅ · vMix 28 ≥5 min over TCP (~10 min, 0 drops) ✅ · vMix over UDP ✅ |
| 3 | LIVE pill, stats overlay, lens switching, settings | 1080p30 stable with UI live; drops <0.5% |
| 4 | Resilience (background/Wi-Fi/thermal) + Flow input diagnostics | Fault-injection checklist passes |
| 5 | 30-min soak + latency/CPU/thermal numbers | Documented results |
| 6 | README, vMix setup guide, limitations | Docs match measured behavior |

## 10. Risks (top)

1. RTSP library interop/backpressure with vMix → ADR-0002 fallback to custom server behind the same interface.
2. Manual-control availability varies per Pixel → probe + hide, never assume.
3. Physical lens IDs may refuse direct sessions → fallback to logical camera + zoom-ratio lens selection.
4. Wi-Fi power save/jitter → WifiLock, 5 GHz, AP settings documented.
5. Thermal throttle in 30-min soak → bitrate setting + degrade ladder (Phase 4/5).
