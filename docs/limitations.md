# Limitations

## Product scope (MVP)

- **Video only.** No audio, no cloud, no accounts, no backend.
- **RTSP only.** NDI is parked for a later release (see `docs/adr/0001-rtsp-transport-for-mvp.md`).
- **No foreground service.** Backgrounding or turning the screen off stops the stream and closes the camera; the preview reopens on return. The stream is not meant to run unattended.
- **No RAW video, no custom Windows receiver.**

## Image processing

- Android Camera2 exposes **no public sharpening key** (verified against API 34). Vendor ISP sharpening/NR cannot be disabled via the public API.
- A "minimal processing" toggle shipped in Phase 1 and was removed on 2026-11-04: its effect was imperceptible on the Pixel 8 Pro, and keeping it implied control the API does not provide. The app now leaves image tuning to the camera HAL.
- Manual white balance uses native CCT when the HAL advertises it; otherwise a learned-transform fallback. Pixel 8 Pro does not advertise CCT (`cctRange=null`), so the fallback is used.

## Focus

- **Tap-to-focus requires `CONTROL_MAX_REGIONS_AF > 0`.** Both Pixel 8 Pro logical cameras report 1 region; physical cameras cannot be targeted (not directly openable, and the logical session exposes no AF region/mode keys for them).
- **Front camera (ID 1) region AF is unconfirmed** — accepted by the HAL but no stable distance delta was measured. Tap-to-focus on the front camera is not claimed as verified.
- **Manual focus owns the lens.** Tap-to-focus is disabled while manual focus is active; the Focus tab explains why.
- `CONTROL_AF_LOCK` was removed in API 36; lock is implemented as `AF_MODE_OFF` + measured `LENS_FOCUS_DISTANCE`. A re-hunting HAL invalidates the hold.

## Latency

- The 122 ms p50 is **capture-to-encode** (sensor → encoded NAL), not glass-to-glass. Network transit, client decode and display are excluded.
- Glass-to-glass would need a synchronised clock visible to both the camera and the decoded client output.

## Device and environment

- Verified on a **Pixel 8 Pro** (Android 17, SDK 37). Other devices are probed at runtime; unsupported controls are hidden.
- The 30-minute soak ran on **USB power**. Battery temperature and thermal behaviour on battery may differ; not measured.
- The 30-minute soak is a single sample, not a statistical guarantee. The harness (`scripts/phase5-soak.ps1`) is repeatable.
- Wi-Fi client isolation / a single-subnet-with-two-interfaces setup can break LAN reachability (seen during testing); the phone's DHCP lease can change.

## Resilience

- No foreground service: backgrounding or screen-off stops the stream. Not suitable for unattended streaming.
- F1/F2 (camera stolen / HAL disconnect) and F11 (rejected bitrate change) could not be injected on a user build without root; their code paths are unit-tested (`BackoffPolicy`, `ThermalLadder`).
- F13 (flaky-Wi-Fi socket teardown) is an environmental observation from 2026-10-23 that never reproduced on a healthy link; no code change attached.

## Thermal

- `ThermalLadder` degrades via `PARAMETER_KEY_VIDEO_BITRATE` (MODERATE → 75 %, SEVERE+ → 50 %, restore after 5 cool samples). A rejected bitrate change is logged and never stops the stream.
- No thermal throttle was observed in the 30-minute soak (battery 40.4 → 44.5 °C, thermal status NONE throughout).

## Focus diagnostics

- `CONTROL_AF_REGIONS` echo from the Pixel HAL is unreliable (it often reports a centre window). The app trusts the **measured** `LENS_FOCUS_DISTANCE`, not the echoed region.