# ADR-0001: RTSP transport for the MVP; NDI parked

- Status: Accepted (2026-10-18)
- Deciders: project owner

## Context

The original brief required NDI output to OBS and vMix. Research (NDI docs, license, trial behavior) found:

1. **Free NDI SDK accepts raw frames only** — NDI compresses internally in software (NEON). Sending pre-encoded H.264 from `MediaCodec` is an **Advanced SDK** feature (commercial).
2. **Advanced SDK trials on Android have a 5-minute runtime limit**, which makes the required 30-minute soak test impossible without a commercial license.
3. The free SDK's license states it is "free to use for non-commercial purposes", requires NDI attribution/linking, and its definition of redistributable "Products" **excludes Android** — bundling `libndi.so` in an APK would need explicit confirmation from NDI before shipping.
4. NDI's ARM software encode cost and thermal impact were unknown until measured.

## Decision

- MVP streams **Camera2 → MediaCodec (hardware H.264) → RTSP/RTP over local Wi-Fi**.
- The transport lives behind `StreamTransport` (`:core:common`) so NDI (or RTMP/SRT) can be added later without touching camera, encoder, or UI.
- **vMix is the primary PC target** (native RTSP input: *Add Input → Stream/SRT → RTSP over TCP/UDP*, expects H.264). OBS integration is deferred per current direction; the same stream remains OBS-compatible via *Media Source* (`rtsp://…`) if revisited.
- NDI is revisited only if native source discovery becomes a critical requirement; before then: obtain/verify license terms for Android redistribution, runtime limits, and raw-vs-compressed send paths.

## Consequences

- No third-party licensing risk in this release; no PC-side receiver software required for vMix.
- Latency/interop depend on our RTSP implementation and vMix's RTSP client — validated in Phase 2 (`ffprobe` → vMix).
- NDI re-entry cost is contained to one new `StreamTransport` implementation module.
