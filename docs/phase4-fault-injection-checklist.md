# Phase 4 fault-injection checklist

Gate for Phase 4 (ARCHITECTURE.md §9): **every entry must pass on the Pixel 8 Pro before the
phase is marked done.** First device run 2026-11-03; results below are from real runs. Entries that
cannot be injected on a user build are recorded as such (not as passes).

## Preconditions

- Pixel 8 Pro over USB (`adb -s 39081FDJG00F81`), PC on the same LAN as the phone
  (phone `192.168.0.101`, PC `192.168.0.100` during this run).
- Log capture: `adb logcat -s StreamMetrics StreamCamera H264Encoder InputDiag RtspServer`.
- RTSP client: `ffmpeg -rtsp_transport tcp -timeout 5000000 -i rtsp://<phone-ip>:8554/stream -update 1 -frames:v 1 out.png`.
- Never leave `adb reverse tcp:8554 tcp:8554` active while starting the stream — it squats the
  device port and makes the server bind fail with `EADDRINUSE` (see F8).
- Start each scenario from a known state: app foregrounded, preview open, stream running unless stated.

## Checklist

| ID | Scenario | Injection | Expected behavior | Evidence to capture | Observed |
|---|---|---|---|---|---|
| F1 | Camera stolen mid-stream | Start stream, then open the stock Google Camera app | Stream stops cleanly (no frozen video), banner shows a camera error, camera reopens with backoff once the stock app releases it; stream does **not** auto-restart | `StreamMetrics` camera error + `camera reopen attempt=` lines; client sees stream end | **NOT INJECTABLE**: Launching Google Camera while StreamCam is foreground and holding the camera did not steal it (`METRICS` stayed RUNNING, ~30 fps, 0 drops, no camera error). A user build exposes no shell camera-disconnect tool (only privileged binder services), so this scenario needs root. |
| F2 | Camera disconnect (HAL) | `adb shell cmd media.camera ...` (not present on this build; cameraserver kill needs root) | `onDisconnected` → banner, ≤5 reopen attempts, then "Camera unavailable — switch lens or reopen the app" | `camera error: Disconnected`, retry delays 500 ms → 8 s | **NOT INJECTABLE** (same reason). Indirect observation from earlier runs: `camera error: OpenFailed(code=-1)` / `OpenFailed(code=3)` on resume, with `camera reopen skipped:` diagnostics after the logging fix. Backoff policy is unit-tested (`BackoffPolicyTest`). |
| F3 | Background while streaming | Press HOME while streaming | Transport stops, camera closes, no restart in background; return restores preview, stream stays stopped | Stop on background; `open camera=` on return | **PASS**: HOME mid-stream; on return `resume: reopening camera=0` / `open camera=` and preview restored; stream not auto-restarted. |
| F4 | Screen off while streaming | Power button during live stream | Same as F3 (clean stop, no foreground service) | Same as F3 | **PASS**: power off/on; on wake camera reopened, stream not auto-started. |
| F5 | Wi-Fi loss | `adb shell svc wifi disable` mid-stream | Stream halts, banner "Wi-Fi lost — stream stopped; will restart when it returns", encoder/transport released, no crash, **no restart while offline** | `network lost; halting stream`; METRICS stops; client disconnects | **PASS** (after fix, see note): `W StreamMetrics: network lost; halting stream`, `RtspServer: Server finished`, METRICS stopped, no `stream started` while Wi-Fi was down. An earlier run restarted over cellular 0.7 s after halt — fixed by gating the `onOpened` restart hook on Wi-Fi (`isOnWifi()`); retested. |
| F6 | Wi-Fi recovery | `adb shell svc wifi enable` after F5, same session | Stream restarts automatically when Wi-Fi is back, app foregrounded and camera ready; banner clears; new client can pull a frame | `network restored; restarting stream`, `stream started`, fresh `Client connected` | **PASS**: `network restored; restarting stream` → `stream started 1920x1080@30` → `Server started: 192.168.0.101:8554`; METRICS resumed at ~30 fps, 0 drops. |
| F7 | Wi-Fi → cellular fallback | Default network changes while cellular data is up | Treated as Wi-Fi loss: stream halts (LAN clients cannot reach the phone), intent kept, auto-restart only when Wi-Fi returns | `halting stream` on leaving Wi-Fi; no restart over cellular | **PASS (as observed)**: with mobile data present, disabling Wi-Fi halted the stream and nothing restarted on cellular; restart only occurred on Wi-Fi return (F6). |
| F8 | Port conflict | `adb reverse tcp:8554 tcp:8554`, then start stream | Clean failure: banner, app stays usable, no crash; works after removing the reverse | `EADDRINUSE`, then successful start after `adb reverse --remove-all` | **PASS**: `RtspServer: EADDRINUSE (Address already in use)` (bind stack trace only, no crash; process alive, UI back to "Start stream"); after `adb reverse --remove-all`, start succeeded (`METRICS state=RUNNING`). |
| F9 | Client storm | 10× ffmpeg connect/disconnect while streaming | No crash, ~30 fps, drops <0.5%, server stays RUNNING between clients, every client gets frames | `clients=` transitions, frame pulls | **PASS**: 10/10 pulls succeeded; each connect showed `Client connected: 192.168.0.100`, `state=LIVE clients=1`; disconnects returned to `RUNNING clients=0`; `dropped_fps=0.0` throughout, no crash. |
| F10 | Thermal degrade | Sustained stream + `adb shell cmd thermalservice override-status 3` (accepted on this build) | REDUCE at MODERATE (75%), further REDUCE at SEVERE (50%), IDR requested; RESTORE after 5 cool samples; stream never drops | `THERMAL` lines, kbps drop in METRICS, client stays connected | **PASS**: `THERMAL action=REDUCE level=MODERATE bitrate=6000000 applied=true` (kbps 8000→~6000); `THERMAL action=REDUCE level=SEVERE bitrate=4000000 applied=true` (kbps ~4000); back to `override-status 0` → `THERMAL action=RESTORE level=NONE bitrate=8000000 applied=true` (kbps ~8000). 30 fps, 0 drops throughout. |
| F11 | Rejected bitrate change | Force a platform rejection of `PARAMETER_KEY_VIDEO_BITRATE` | `THERMAL ... applied=false` logged, stream continues, no crash | `setBitrate(...) failed` warning | **NOT TRIGGERED**: the Pixel's encoder accepted every bitrate change (`applied=true` in all F10 steps). The rejection path is a `runCatching` + `Log.w` + `return false`, never fatal; not forceable on this device. |
| F12 | Flow input diagnostics | Connect the insta360 Flow, spin the zoom wheel, press its buttons | `InputDiag` logs the device and the events it emits, nothing consumed, camera zoom unchanged | `InputDiag device ...`, `key down code=...`, `motion axes=[...]` | **PASS (diagnostics only)**: Flow enumerated as `device id=9 name=Flow 4VW9SW vendor=257 product=257 sources=0x101 keyboard=true`. Wheel emits public keycodes: `key down code=168 (KEYCODE_ZOOM_IN) scan=418 device=9` (repeats under continuous spin) and `key down code=169 (KEYCODE_ZOOM_OUT) scan=419 device=9`. Shutter emitted `KEYCODE_VOLUME_UP`; mode button emitted `KEYCODE_UNKNOWN` (scan 241, unusable via public API); trigger button emitted nothing observed. No zoom side effect: `SetZoomRatio(ratio=1.0)` unchanged before/after; no motion axes. Follow-up (not part of the gate): handle `KEYCODE_ZOOM_IN/OUT` in `MainActivity.dispatchKeyEvent` to drive `zoomRatio`; beware the shutter's VOLUME_UP currently reaches the system (media volume changes). **Implemented 2026-11-03**: wheel keys now start a rate-based `ZoomRamp` (adjustable 0.02–0.6 ln-units/s via the persisted "Wheel speed" slider; default 0.2 → ~12 s, slowest 0.02 → ~115 s for 1x→10x; 120 ms release) capped at 10x and consumed; shutter left unhandled. |
| F13 | Flaky-Wi-Fi socket teardown (known environmental) | Reproduce the 2026-10-23 observation: degraded Wi-Fi link (ping 50–105 ms) | Record whether it still reproduces; no code change expected | logcat `InetDiagMessage`, bind/accept timeline | _pending_ (requires a degraded link; not reproduced this run — link RSSI −27 dBm, 65 Mbps, 0 drops). |

## Notes from this run

- F5 exposed a real defect: the deferred-restart hook in `engine.onOpened` restarted the stream
  immediately after the halt (session rebuild fires `onOpened`), even with Wi-Fi down, binding the
  server on the cellular interface. Fixed by requiring `isOnWifi()` before an automatic restart;
  retested under F5/F6.
- Added diagnostics while running: `network monitor registered`, and explicit
  `camera reopen skipped: ...` reasons (no valid surface / no camera / backgrounded).
- Device thermal status reached MODERATE naturally under sustained 1080p30 streaming once, so F10's
  MODERATE step was observed both naturally and via override.

## Pass criteria

- F1–F12 pass or are documented as non-injectable with the constraint named; F10 and F12 passed.
- F13 pending a degraded link.
- Every Observed cell cites the log line or command output from the run.
