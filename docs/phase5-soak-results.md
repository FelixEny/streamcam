# Phase 5 - 30-minute soak results

**Status: PASS** (2026-11-04).

Device: Google Pixel 8 Pro (`husky`), Android 17 (SDK 37).
Build: StreamCam debug, 1920x1080 @ 30 fps, 8 Mb/s, hardware H.264 Baseline 4.0, 1 s GOP.
Transport: RTSP over TCP, video only.
Client: ffmpeg 9.x on the PC (`192.168.0.100`), TCP decode to `-f null` for the full 30 minutes.

## Objective

Phase 5 gate (ARCHITECTURE.md section 9): run a 30-minute live stream and document
throughput, pacing, device-side latency, CPU, thermal, battery and Wi-Fi numbers.

## Method

- `scripts/phase5-soak.ps1 -DurationMin 30` force-stops camera apps, wakes/unlocks the phone,
  launches StreamCam, taps **Start stream** through uiautomator, and reads the RTSP URL from
  the UI.
- Raises the logcat ring buffer to 16 MB and clears it, then starts an ffmpeg RTSP/TCP client
  that decodes for exactly 30 minutes (`-progress` records frames, fps, drops and bitrate).
- At the end taps **Stop**, dumps the on-device logcat buffer, and parses the 1 Hz `METRICS`
  lines into the tables below.
- The phone stayed on USB power, the screen stayed awake (app flag), and no thermal override
  was applied - natural thermal behavior is the measurement.

## Throughput and pacing

| Metric | Value |
|---|---|
| Metric ticks | 1798 (1 Hz) |
| Stream duration | 30:00 |
| `enc_fps` | avg 30.1 / min 17.0 / max 31.0 |
| `sent_fps` | avg 30.0 / min 0.0 / max 31.0 |
| `dropped_fps` | avg 0.0 / min 0.0 / max 0.0 |
| Bitrate | avg 7,953.9 kb/s / max 9,692.0 kb/s |
| Frames submitted (`sent_total`) | 54,146 |
| Frames dropped (`dropped_total`) | **0** |
| Client connected (`LIVE`) | 1795 / 1798 ticks |

The first tick is warm-up (`enc_fps=17`, `sent_fps=0`, bitrate 0, CPU not primed). Steady state
is 30 fps at ~7.95 Mb/s average; the 9.69 Mb/s peak is keyframe/IDR overhead.

## Client receive/decode

| Metric | Value |
|---|---|
| Frames decoded | 53,953 |
| Decode fps | 29.97 |
| Output time | 00:30:00.000 |
| Drop frames | **0** |
| Duplicate frames | 0 |
| Speed | 1x |
| ffmpeg exit | 0 |

54,146 submitted vs 53,953 decoded is a 193-frame (0.36%) difference explained by the client
connecting ~3 s after the server (1795 LIVE ticks vs 1798), not loss: 53,953 / 1795 s =
30.06 fps.

## Capture-to-encoded-frame latency

| Metric | Value |
|---|---|
| p50 per 1 Hz window | avg 121.8 ms / min 108 ms / max 127 ms |
| p95 per 1 Hz window | avg 129.7 ms / min 119 ms / max 136 ms |
| Window max | avg 142.8 ms / min 119 ms / max 158 ms |
| Samples in final window | 600 |

Definition: time from the input Surface frame timestamp (camera capture) to the encoded H.264
access unit being dequeued by `MediaCodec`. This covers sensor readout, ISP and the encoder
pipeline; **it is not glass-to-glass** (network transit, client decode and display are excluded).

At 30 fps the p50 of ~122 ms is ~3.7 frame intervals, consistent with the encoder's internal
buffering. The p50 stayed in a 108-127 ms band for the whole run, so latency did not drift with
heat or battery state.

Clock note: the encoder PTS on this device is on `CLOCK_MONOTONIC`, while Camera2 reports a
REALTIME timestamp source; the tracker accepts whichever of `elapsedRealtimeNanos()` or
`nanoTime()` lands in the plausible 0..5 s window. This keeps the number valid across HAL
behavior instead of reporting a bogus ~20 h age after a long uptime.

## Device

| Metric | Value |
|---|---|
| CPU (one core = 100%) | avg 50.2% / min 33.8% / max 64.2% |
| Battery temperature | avg 43.3 C / min 40.4 C / max 44.5 C |
| Wi-Fi RSSI | avg -31.3 dBm / min -36 dBm / max -28 dBm |
| Wi-Fi link | 65 Mbps throughout |
| Thermal status | **NONE (0) for all 1798 ticks** |

Battery temperature rose from 40.4 C to 44.5 C and plateaued around 43.3 C. The platform never
reported a thermal status above NONE, so `ThermalLadder` made no bitrate changes. CPU averaged
about half a core (max 64.2%), despite camera, ISP, H.264 encode and RTSP packetization running
simultaneously - the zero-copy Surface input path is doing its job.

## Stability and health

- Camera/session/transport error lines: **0**
- Camera reopen triggers: **0**
- `state=ERROR` / `state=STOPPING` ticks: **0**
- Stream stayed `LIVE` with one client for the full run; the RTSP server never disconnected.
- No dropped frames in-app and no dropped/duplicated frames at the client.

## Observations

- 30 minutes at 1080p30 / 8 Mb/s is comfortably stable on this Pixel 8 Pro: 0 drops, flat
  latency, no thermal throttle, no reconnects.
- The bottleneck is Wi-Fi/PoE-class LAN, not the phone: the 65 Mbps link has ~8x headroom over
  the 7.95 Mb/s stream.
- Device-side latency (~122 ms p50) is dominated by the camera + encoder pipeline, not the
  network. Reducing it would mean lowering encoder buffering or capturing at a lower
  resolution/fps; neither is required for vMix operation.
- The 30-minute run does not prove behavior on battery or in a hot ambient environment; those
  remain open risks for longer field shoots.

## Limitations

- Latency is device-side capture-to-encode, not glass-to-glass. A glass-to-glass number would
  need a synchronized clock visible both to the camera and in the decoded client output.
- Client numbers come from ffmpeg on a Windows PC over Wi-Fi; they characterize this LAN, not
  every deployment.
- The phone was on USB power. Battery temperature and thermal behavior on battery may differ.
- One 30-minute run is a sample, not a statistical guarantee; the harness is repeatable.

## Artifacts

Raw log and per-run summary are written to `docs/soak/` (git-ignored):
`soak-20261104-075609.log`, `summary-20261104-075609.md`, `client-20261104-075609.progress`.

## Reproduce

```powershell
gradlew.bat :app:assembleDebug
powershell -ExecutionPolicy Bypass -File scripts/phase5-soak.ps1 -DurationMin 30
```
