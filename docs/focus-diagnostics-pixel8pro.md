# Pixel 8 Pro — point-specific autofocus diagnostic

Date: 2026-11-04
Device: Google Pixel 8 Pro (`husky`), Android 17 (SDK 37)
Build: StreamCam debug + `app/src/androidTest/.../FocusDiagnosticsTest.kt`
Production focus code was unchanged during the investigation; the follow-up fixes are
described under “Fixes applied” and verified through the app's normal tap path.

## Bottom line

**Yes — genuine touch-position autofocus is achievable on camera `0` (the rear logical
camera), through a normal logical-camera session.**

Conditions:

1. `CONTROL_AF_MODE_AUTO` (one-shot semantics), one small `MeteringRectangle` in
   `CONTROL_AF_REGIONS` (weight 1000), and a **single** `AF_TRIGGER_START` capture; the
   repeating request stays on `AF_TRIGGER_IDLE`.
2. The scene has actual depth. With a near/far scene, the measured focus distance tracked
   the chosen region reproducibly (top-left ≈ 2.5 dpt ≈ 39–40 cm vs centre ≈ 3.3 dpt ≈ 30 cm).
3. AF regions are plumbed through the app and cleared correctly. Two bugs blocked this
   (see “Implementation bugs” below); both are fixed and verified through the normal tap
   path.

Physical cameras `2`–`8` also advertise 1 AF region, but **cannot be used for AF regions**:
they cannot be opened directly and the logical session does not expose
`CONTROL_AF_REGIONS`/`CONTROL_AF_MODE` for them.

The front logical camera `1` accepts region requests, but region-dependent focus was **not
reproduced** in the tested scene (two AF timeouts, no stable delta). Unconfirmed.

## Camera inventory

`CameraManager.cameraIdList = [0, 1]` — both are logical multi-camera.

| ID | Facing | Logical | Physical IDs | Max AF regions | AF modes | Min focus (dpt) | Hyperfocal (dpt) | Active array |
|----|--------|---------|--------------|----------------|----------|-----------------|------------------|--------------|
| 0 | BACK | yes | 2, 3, 4, 5, 6 | **1** | OFF, AUTO, MACRO, CONTINUOUS_VIDEO, CONTINUOUS_PICTURE, EDOF | 9.5238 | 0.16938 | 4080×3072 |
| 1 | FRONT | yes | 7, 8 | **1** | same | 10.0 | 0.71501 | 3440×2448 |
| 2 | BACK | no | — | 1 | same | 9.5238 | 0.16938 | 4080×3072 |
| 3 | BACK | no | — | 1 | same | 50.0 | 1.2548 | 4000×3000 |
| 4 | BACK | no | — | 1 | same | 1.25 | 0.02420 | 4032×3024 |
| 5 | BACK | no | — | 1 | same | 9.5238 | 0.08469 | 4080×3072 |
| 6 | BACK | no | — | 1 | same | 1.25 | 0.01210 | 4032×3024 |
| 7 | FRONT | no | — | 1 | same | 10.0 | 0.71501 | 3840×2736 |
| 8 | FRONT | no | — | 1 | same | 10.0 | 0.71501 | 3440×2448 |

Capabilities (`REQUEST_AVAILABLE_CAPABILITIES`) include `LOGICAL_MULTI_CAMERA`,
`MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`, `RAW`, `BURST_CAPTURE`, `READ_SENSOR_SETTINGS`
on both logical cameras (plus `CONSTRAINED_HIGH_SPEED_VIDEO` on the rear).

### Physical camera request keys

Every physical camera advertises exactly seven request keys through the logical session:

```
android.colorCorrection.gains
android.colorCorrection.transform
android.control.aeExposureCompensation
android.control.zoomRatio
android.lens.focusDistance
android.sensor.exposureTime
android.sensor.sensitivity
```

Not present: `android.control.afRegions`, `android.control.afMode`.

Consequences:

- Direct `openCamera("2"…)` → `CameraAccessException: CAMERA_DISCONNECTED`,
  “No camera device with ID "2" available” (same for 3–8).
- Physical-targeted AF is therefore impossible. `LENS_FOCUS_DISTANCE` *is* available, so
  manual focus on a specific physical camera is possible in principle, but not AF regions.
- `getAvailablePhysicalCameraRequestKeys()` confirms the limitation; the test skipped the
  physical AF capture for every physical camera for this reason.

## Region test — camera 0 (rear logical)

Method: `TEMPLATE_PREVIEW`, `AF_MODE_AUTO`, region 12 % of the active array, weight 1000.
Each probe: cancel → wait for the HAL to leave the previous lock → repeating request with
`AF_TRIGGER_IDLE` → one capture with `AF_TRIGGER_START` → wait for
`FOCUSED_LOCKED`/`NOT_FOCUSED_LOCKED` → record `LENS_FOCUS_DISTANCE`.

Regions were interleaved (centre, top-left, centre, bottom-right, centre) so scene/lens
drift could not fake a region effect. The sequence was run twice.

| Probe | Focus distance (dpt) | Approx. distance |
|-------|----------------------|------------------|
| centre #1 | 3.4895 | 28.7 cm |
| corner top-left | 2.5826 | 38.7 cm |
| centre #2 | 3.2368 | 30.9 cm |
| corner bottom-right | 3.4174 | 29.3 cm |
| centre #3 | 3.3091 | 30.2 cm |

- Centre mean 3.3451 dpt; centre re-test spread **0.2528 dpt**.
- Top-left 2.5826 dpt → **≈ 0.9 dpt farther than centre**, repeated at 2.4730 dpt in the
  previous run.
- AF state traces show `INACTIVE → ACTIVE_SCAN → FOCUSED_LOCKED`, so the one-shot trigger
  really ran each time.

**Verdict: region position changes the focus result on camera 0, reproducibly.**

Note: `CaptureResult.CONTROL_AF_REGIONS` on this HAL reports a ~934×706 window at the
sensor centre even when a corner region was requested. The echo is **not** a faithful
record of the request. The measured focus distance is the reliable evidence.

## Region test — camera 1 (front logical)

| Probe | Focus distance (dpt) | Result |
|-------|----------------------|--------|
| centre #1 | — | timeout, `INACTIVE → ACTIVE_SCAN` for 7 s |
| corner top-left | — | timeout, `INACTIVE → ACTIVE_SCAN` for 7 s |
| centre #2 | 1.8507 | FOCUSED_LOCKED |
| corner bottom-right | 1.4598 | FOCUSED_LOCKED |
| centre #3 | 1.4598 | FOCUSED_LOCKED |

Centre re-test spread 0.3909 dpt > corner delta 0.1955 dpt → **no reproducible region
effect** in this scene. The scene was a dim, flat wall; two probes never settled. Front
region AF is therefore unconfirmed, not disproven.

## Implementation bugs found (not hardware limitations)

`core/camera/src/main/java/com/streamcam/camera/CapabilityProbe.kt`

```kotlin
val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0  // line 58
...
return RawCameraCharacteristics(
    lensFacing = ...,
    // ...
    afModes = ...,
    minFocusDistance = ...,
    // maxAfRegions is NEVER passed
)
```

`RawCameraCharacteristics.maxAfRegions` defaults to `0`, so the value read from the HAL is
discarded. In the same process:

```
production probe camera 0: maxAfRegions=0 supportsTapToFocus=false
raw CameraCharacteristics:  CONTROL_MAX_REGIONS_AF=1
production probe camera 1: maxAfRegions=0 supportsTapToFocus=false
raw CameraCharacteristics:  CONTROL_MAX_REGIONS_AF=1
```

Downstream effects:

- `CameraCapabilities.supportsTapToFocus` is always false.
- `FocusMapper.toRegion` returns `null` (its `maxAfRegions <= 0` guard).
- `RequestPlanner` omits `CONTROL_AF_REGIONS`; taps degrade to full-frame one-shot.

This also invalidates the earlier note that “the Pixel logical camera reports
`CONTROL_MAX_REGIONS_AF = 0`”. That was this bug, not the hardware.

Fix (applied): pass `maxAfRegions = maxAfRegions` into `RawCameraCharacteristics(...)`.
One line. The existing mapper/planner path now engages region targeting on camera 0.

### Bug 2 — clearing AF regions used an invalid empty array

`RequestApplier.ClearAfRegions` set `CONTROL_AF_REGIONS` to `emptyArray()`. Once regions
were actually being sent (after bug 1 was fixed), the Pixel 8 Pro HAL rejected the lock
request with `IllegalArgumentException` from `setRepeatingRequest`, which the engine
surfaced as `SessionFailed` and turned into a camera reconnect loop.

Fix (applied): unset the key (`builder.set(CONTROL_AF_REGIONS, null)`) instead of sending
an empty `MeteringRectangle[]`. The lock then holds without disturbing the session.

## Errors / unsupported operations

- Direct physical camera open: `CAMERA_DISCONNECTED` — no such camera ID.
- Physical AF through the logical session: not advertised in
  `availablePhysicalCameraRequestKeys`.
- First diagnostic rerun failed with `CAMERA_IN_USE` because another client held the
  camera; resolved by force-stopping the camera apps before the run.
- Front-camera AF timed out twice on a low-contrast, dim scene.
- `CONTROL_AF_REGIONS` result echo is unreliable on camera 0 (always centre-ish).

## Post-fix verification through the app's normal tap path

After both fixes, tapping the preview produced:

```
tapFocus tap=(1569,410) region=FocusRegion(left=2567, top=1466, 816x614) regionsSupported=true
  af transition state=4 intent=LOCKED(distanceDiopters=4.028129) ui=LOCKED
request ops intent=LOCKED ... SetAfMode(mode=0), SetLensFocusDistance(4.028129), ClearAfRegions

tapFocus tap=(747,244) region=FocusRegion(left=3264, top=151, 816x614) regionsSupported=true
  af transition state=4 intent=LOCKED(distanceDiopters=5.269071) ui=LOCKED
```

- Two different preview taps → two different sensor regions → **repeatable** distance
  clusters: object 3.99–4.17 dpt (≈24–25 cm), cushion 5.13–5.27 dpt (≈19 cm).
- No `SessionFailed`, no reconnect loop.
- UI showed `FOCUS LOCKED` + `Unlock Focus` and a green reticle at the tap point.
- `Unlock Focus` restored continuous AF (`intent=IDLE`, `AF_MODE=3`, `AF ACTIVE`).
- While streaming: the same two taps locked at 3.99 and 5.27 dpt; metrics stayed at
  **30 fps / 0 dropped frames** and an RTSP frame was pulled successfully from
  `rtsp://192.168.0.101:8554/stream`.

## Conclusion

- **Camera 0 (rear logical):** genuine region-based AF works. Use `AF_MODE_AUTO` +
  one region + one-shot `AF_TRIGGER_START`, with a depth-varying scene.
- **Cameras 2–8 (physical):** region AF is inaccessible from the app.
- **Camera 1 (front logical):** region requests are accepted but region-dependent focus was
  not demonstrated in the tested scene.
- **StreamCam after the fixes:** touch-position AF works on camera 0 through the normal
  tap path, without breaking the RTSP/encoder pipeline.

## Artifacts

- Diagnostic test: `app/src/androidTest/java/com/streamcam/FocusDiagnosticsTest.kt`
- Raw report pulled from the device: `focus_diagnostics.txt`
  (app external files dir, also dumped under logcat tag `FocusDiag`)
- Per-probe frames: `focus_frames/af_{camera}_{region}.jpg`
