# ADR-0002: RTSP server via pedroSG94 RTSP-Server, with custom-server fallback

- Status: Accepted (2026-10-18)
- Deciders: project owner

## Context

The phone must run an RTSP **server** (vMix/OBS pull from the phone; no PC-side middleware allowed). Options evaluated:

| Option | Pros | Cons |
|---|---|---|
| **A. `pedroSG94/RTSP-Server` + RootEncoder** | Maintained Kotlin lib; proven `setVideoInfo(sps,pps)` + `sendVideo(buffer, info)` seam works with an external Camera2/MediaCodec pipeline; RTP over TCP + UDP; community usage | Brings RootEncoder/ktor dependencies; per-client backpressure and drop policy are opaque; interop with vMix unverified |
| **B. Custom minimal RTSP server** (RFC 6184 packetizer, interleaved TCP) | Full control of congestion/drop policy and stats; no deps; small protocol surface | More code; interop bugs only found via testing |
| **C. `fyhertz/libstreaming`** | Battle-tested packetizers | Camera1-era, unmaintained since ~2017 — rejected |
| **D. live555 via NDK** | Mature C++ RTSP | C++ toolchain + larger surface for an MVP — rejected |

## Decision

- Implement Phase 2 as **Option A** behind the `StreamTransport` interface (package `com.streamcam.transport.rtsp`).
- **Fallback trigger**: if during Phase 2 the library (a) does not interoperate with vMix, or (b) queues unboundedly under a slow client (latency creep instead of counted drops), replace the internals with **Option B** in the same module. The interface, stats contract, and UI do not change.

## Version pinning (discovered during Phase 0)

- `RTSP-Server 1.4.2` depends on `RootEncoder 2.8.0`, whose artifacts declare **`minCompileSdk=37`** → build fails with compileSdk 34. Pinned: **`RTSP-Server 1.4.1` + `RootEncoder 2.7.2`** (pair from the lib README; `minCompileSdk=1`).
- RootEncoder's kotlin-stdlib resolves to **2.3.20**, which dragged the Kotlin compiler past 2.0.21 (K2 ICE). Pinned: **Kotlin 2.3.20** (compiler + compose plugin), Gradle 8.7, AGP 8.5.2.
- Do not bump these independently; re-check AAR metadata (`minCompileSdk`) on any upgrade.

## Implementation note: vendored source with one fix (2026-10-21)

- vMix 28 rejected the upstream 1.4.1 server: `Invalid Stream Control URI: rsp://0/streamid=0`. Cause: `ServerClient` builds a per-client `ServerCommandManager` copy but only the shared instance received `setServerInfo(serverIp, port)`, so DESCRIBE emitted `Content-Base: rtsp://:0/` and `o=- 0 0 IN IP4 `. ffmpeg/VLC tolerate the relative base; vMix does not.
- Upstream `master` fixes this by setting the server info on the per-client copy, but `master` also requires compileSdk 37+ (same metadata block as 1.4.2).
- Resolution: **vendored** the `server`/`socket`/`util` sources of tag 1.4.1 into `:thirdparty:rtspserver` (Apache-2.0, see `thirdparty/rtspserver/NOTICE.txt`) and added the missing `setServerInfo(serverIp, serverPort)` call. Toolchain stays on compileSdk 34; `RtspTransport` API unchanged.
- Verified after the fix: `Content-Base: rtsp://192.168.0.25:8554/`, vMix plays over RTSP/TCP with `clients=1`, ~29.97 fps, ~7.98 Mb/s, 0 drops.
- Consequence: `com.github.pedroSG94:RTSP-Server` is no longer a dependency; RootEncoder 2.7.2 is still required by the vendored code.
