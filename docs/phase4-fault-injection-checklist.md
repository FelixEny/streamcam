# Phase 4 fault-injection checklist

Gate for Phase 4 (ARCHITECTURE.md §9): **every entry must pass on the Pixel 8 Pro before the
phase is marked done.** Implementation landed 2026-11-02; device verification is pending — nothing
in this document may be reported as passing until the Observed column is filled from real runs.

## Preconditions

- Pixel 8 Pro over USB (`adb -s 39081FDJG00F81`), PC on the same 5 GHz LAN as the phone.
- Log capture: `adb logcat -s StreamMetrics StreamCamera H264Encoder InputDiag RtspServer`.
- RTSP client: `ffmpeg -rtsp_transport tcp -timeout 5000000 -i rtsp://<phone-ip>:8554/stream -frames:v 1 out.png`
  or vMix (Stream/SRT → RTSP over TCP).
- Never leave `adb reverse tcp:8554 tcp:8554` active while starting the stream — it squats the
  device port and makes the server bind fail with `EADDRINUSE` (see F8).
- Start each scenario from a known state: app foregrounded, preview open, no stream running.

## Checklist

| ID | Scenario | Injection | Expected behavior | Evidence to capture | Observed |
|---|---|---|---|---|---|
| F1 | Camera stolen mid-stream | Start stream, then open the stock Google Camera app | Stream stops cleanly (no frozen video), banner shows a camera error, camera reopens with backoff once the stock app releases it; stream does **not** auto-restart | `StreamMetrics` camera error + `camera reopen attempt=` lines; ffmpeg client sees stream end, not corruption | _pending_ |
| F2 | Camera disconnect (HAL) | `adb shell cmd media.camera ...` if supported, otherwise rotate through F1; note: killing `cameraserver` needs root and is not expected to be available on a user build | `onDisconnected` → banner, ≤5 reopen attempts, then "Camera unavailable — switch lens or reopen the app" | `camera error: Disconnected`, retry lines with delays 500 ms → 8 s | _pending_ |
| F3 | Background while streaming | Press HOME during a live stream | Transport stops, camera closes, no restart in background; returning to the app restores preview (stream stays stopped) | `onAppBackgrounded` → stop; on relaunch `open camera=` + transform log; client disconnected | _pending_ |
| F4 | Screen off while streaming | Power button during a live stream | Same as F3 (clean stop, no foreground service) | Same as F3 | _pending_ |
| F5 | Wi-Fi loss | `adb shell svc wifi disable` mid-stream | Stream halts, banner "Wi-Fi lost — stream stopped; will restart when it returns", encoder/transport released, no crash | `network lost; halting stream`; `METRICS` stops; ffmpeg client disconnects | _pending_ |
| F6 | Wi-Fi recovery | `adb shell svc wifi enable` after F5 (same session) | Stream restarts automatically when Wi-Fi is back, app foregrounded and camera ready; banner clears; new client can pull a frame | `network restored; restarting stream`, `stream started`, fresh `RTSP` `Client connected` | _pending_ |
| F7 | Wi-Fi → cellular fallback | Disable Wi-Fi while cellular data is up (default network changes) | Treated as Wi-Fi loss: stream halts (LAN clients cannot reach the phone), intent kept, auto-restart only when Wi-Fi returns | `default network is not Wi-Fi; halting LAN stream` | _pending_ |
| F8 | Port conflict | `adb reverse tcp:8554 tcp:8554`, then start stream | Clean failure path: banner "Stream start failed", app stays usable, no crash; removing the reverse and retrying works | `EADDRINUSE` in log, then a successful start after `adb reverse --remove-all` | _pending_ |
| F9 | Client storm | Connect/disconnect ffmpeg 10× in 60 s while streaming | No crash, fps stays ~30, drops <0.5%, server stays RUNNING between clients, a new client always gets frames | `clients=` transitions in METRICS, ffprobe/frame pull on the last connect | _pending_ |
| F10 | Thermal degrade | Sustained 1080p30 stream + charging until `thermal=` ≥ 2 (MODERATE); if `adb shell cmd thermalservice override-status N` is unavailable on the user build, use real heating | `THERMAL action=REDUCE` at MODERATE (75% bitrate), further REDUCE at SEVERE (50%), IDR requested; after 5 cool samples `THERMAL action=RESTORE`; stream never drops | `METRICS thermal=` rising, `THERMAL` lines, kbps drop in METRICS, client stays connected | _pending_ |
| F11 | Rejected bitrate change | Any device where `setParameters` rejects the bitrate | `THERMAL ... applied=false` logged, stream continues at the old bitrate, no crash | `setBitrate(...) failed` warning | _pending_ |
| F12 | Flow input diagnostics | Connect the insta360 Flow, spin the zoom wheel, press its buttons | `InputDiag` logs the device and the events it emits (or proves it emits none), no event is consumed, camera zoom unchanged by the wheel | `InputDiag device id=... name=... sources=...`, `key down code=...`, `motion ... axes=[...]` | _pending_ |
| F13 | Flaky-Wi-Fi socket teardown (known environmental) | Reproduce the 2026-10-23 observation: stream start on a degraded Wi-Fi link (ping 50–105 ms) | Documented, not "fixed": Android may destroy the app's TCP sockets ~3 s after bind (`InetDiagMessage: Destroyed live tcp sockets`); a client connecting within ~2 s succeeds. Record whether it still reproduces; no code change expected | logcat `InetDiagMessage`, server bind/accept timeline | _pending_ |

## Pass criteria

- F1–F9, F11, F12 pass as specified; F10 passes if the ladder steps and restores without dropping
  the client (a device that cannot be heated that far is recorded as such, not as a pass).
- F13 is recorded as an environmental observation with reproduction notes.
- Every Observed cell cites log lines or command output from the run; no entry may be marked pass
  from code inspection alone.
