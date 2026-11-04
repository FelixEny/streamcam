# Face detection and FACE_PRIORITY autofocus on the Pixel 8 Pro

Device: Google Pixel 8 Pro (`husky`), Android 17 (SDK 37), build `CP1A.260305.018`.
Test build: StreamCam debug + `:app:debugAndroidTest`, 2026-11-04.
Diagnostics: `app/src/androidTest/java/com/streamcam/FacePriorityTest.kt` (behaviour) and the
extended `FocusDiagnosticsTest.kt` (capability matrix). No production camera code was changed.

## Verdict

| Question | Answer |
|---|---|
| Hardware face detection? | **Yes.** Every camera reports `STATISTICS_INFO_MAX_FACE_COUNT = 10`, and the HAL populates `STATISTICS_FACES` (up to 3 faces observed, scores 12-98). |
| Hardware face-priority autofocus? | **Advertised and accepted, but no measurable AF advantage over AUTO.** The HAL accepts `CONTROL_MODE_USE_SCENE_MODE` + `CONTROL_SCENE_MODE_FACE_PRIORITY` and keeps AF/AE/AWB active, but the A/B/A comparison showed no mode-correlated change in focus distance. |
| Works in a capture session? | **Yes, in a normal Camera2 session** (YUV `ImageReader` target, full capture results, faces reported). Not yet exercised through the app's preview + encoder session, by design. |
| Conflicts with tap-to-focus? | **No.** An explicit AF region wins over face priority: with a face at 39 cm and a region over a far wall, the lock landed at 212-279 cm. |
| Worth shipping? | **No, not as a user-facing feature.** AUTO already reports faces; FACE_PRIORITY adds a scene-mode key that can conflict with vendor modes, with no demonstrated benefit. |

## 1. Capability matrix

Logical cameras `[0, 1]`; physical `[2..6]` (rear) and `[7, 8]` (front). **All nine** report the
same block:

```
STATISTICS_INFO_MAX_FACE_COUNT=10
STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES=[OFF, SIMPLE, FULL] ([0, 1, 2])
face detection supports: OFF=true SIMPLE=true FULL=true
CONTROL_AVAILABLE_SCENE_MODES=[DISABLED, FACE_PRIORITY, NIGHT, PORTRAIT, MODE(100..105)]
CONTROL_SCENE_MODE_FACE_PRIORITY=true
```

`MODE(100..105)` are the Pixel's vendor scene modes (HDR etc.).

**Important contract detail:** per the Camera2 docs, `FACE_PRIORITY` is always listed whenever
`maxFaceCount > 0`. The capability list alone proves only that the camera reports face detection;
it is not evidence that the HAL implements face-prioritised AF. Everything below exists to answer
that separately.

## 2. Face detection in a real session

`FacePriorityTest` opens camera 0, creates a `TEMPLATE_PREVIEW` session with a 1280x720 YUV
`ImageReader`, and requests `STATISTICS_FACE_DETECT_MODE_FULL` in every block.

| Block | Face frames / total | Max faces | AF state | Focus distance (median) |
|---|---|---|---|---|
| baseline, no face | 0 / 192 | 0 | PASSIVE_FOCUSED | 6.94 dpt (14 cm) |
| FACE_PRIORITY, no face | 0 / 196 | 0 | PASSIVE_FOCUSED | 6.94 dpt (14 cm) |
| baseline, one face | 124 / 201 | 1 | PASSIVE_FOCUSED / SCAN | 2.18 dpt (46 cm) |
| FACE_PRIORITY, one face | 99 / 99 | 1 | PASSIVE_FOCUSED / SCAN | 2.18 dpt (46 cm) |
| baseline, second block | 174 / 174 | 1 | PASSIVE_FOCUSED / SCAN | 2.84 dpt (35 cm) |
| FACE_PRIORITY, second block | 182 / 182 | 1 | PASSIVE_FOCUSED / SCAN | 2.18 dpt (46 cm) |
| baseline, multiple faces | 624 / 826 | **3** | PASSIVE_FOCUSED | 2.36 dpt (42 cm), range 0.66-6.49 |
| FACE_PRIORITY, multiple faces | 242 / 242 | **3** | PASSIVE_FOCUSED | 2.07 dpt (48 cm), range 2.00-2.25 |

Echoes in every FACE_PRIORITY block: `controlMode=[2]` (`USE_SCENE_MODE`), `sceneMode=[1]`
(`FACE_PRIORITY`), `afMode=[3]` (`CONTINUOUS_VIDEO`), `faceDetectMode=[2]` (`FULL`). The HAL
therefore accepted the scene mode and did **not** disable face statistics, AF, AE or AWB - which
is the documented exception for FACE_PRIORITY among scene modes.

Face statistics are real: scores spanned 12-98 and included a face photo held next to the
operator (hence three faces in the multi-face block). The HAL detects faces, including from a
printed/screen image.

### Did FACE_PRIORITY change AF?

No reproducible change. The A/B/A sequence (baseline -> FACE_PRIORITY -> baseline ->
FACE_PRIORITY, same face position) produced medians of 2.18, 2.18, 2.84, 2.18 dpt. The
second baseline differs by 0.66 dpt; the mode switch does not.

The multi-face block is *consistent with* face-biased AF - FACE_PRIORITY held 2.00-2.25 dpt
(a tight 44-50 cm) while baseline wandered 0.66-6.49 dpt (15-152 cm) - but the baseline loosely
covers its own face-detection warm-up (624 of 826 frames), so this is suggestive, not proof.
A single block cannot separate "face priority" from "the lens happened to settle".

Practical reading: on this HAL, `AF_MODE_CONTINUOUS_VIDEO` in AUTO mode already finds and reports
faces; the scene mode appears to add no AF behaviour we can measure.

## 3. Tap-to-focus vs FACE_PRIORITY

Face held centred (reference lock at **2.58 dpt, 39 cm**), then a one-shot AF region over the
**top-left quadrant** (far wall):

| Block | Scene mode | AF state | Focus distance (median) |
|---|---|---|---|
| region, baseline | `DISABLED` | FOCUSED_LOCKED 235/272 | **0.47 dpt (212 cm)**, range 0.21-2.33 |
| region, FACE_PRIORITY | `FACE_PRIORITY` | FOCUSED_LOCKED 250/272 | **0.36 dpt (279 cm)**, range 0.17-0.85 |

Both locks landed on the region target (212-279 cm), not the face (39 cm). **An explicit
`CONTROL_AF_REGIONS` request overrides face priority.** Region targeting still works with
FACE_PRIORITY active, so the two can coexist.

This is also the cleanest evidence that FACE_PRIORITY is not steering AF to the face: if it were,
the region lock would have landed near 39 cm instead of 2-3 m.

## 4. What this does not establish

- **Production session.** The tests use an `ImageReader` session. The request keys are
  session-independent, but "works in preview + streaming" needs a debug-gated run through
  `CameraEngine` before any adoption claim.
- **Front camera behaviour.** Camera 1 advertises identical capabilities; only camera 0 was
  exercised.
- **Low light, backlight, moving faces, glasses, masks.** Not tested.
- **Which face wins** when several are present (nearest? largest? highest score?). Scene-mode
  semantics are HAL-defined and not documented for this device.
- **Cost.** Whether FACE_PRIORITY increases ISP load or changes latency was not measured.
- **Scene-mode exclusivity.** `CONTROL_MODE_USE_SCENE_MODE` is mutually exclusive with other
  scene modes; vendor modes 100-105 cannot be combined with FACE_PRIORITY.

## 5. Recommendation

Do not add FACE_PRIORITY to production. Face detection is real, but on this device the default
continuous AF already reports faces and the scene mode produced no measurable AF improvement,
while costing a request key that can conflict with vendor processing. Tap-to-focus remains the
deterministic way to tell the camera what to focus on, and it keeps working with the mode enabled.

If a follow-up is wanted, the honest next step is a debug-gated trial in the real preview +
encoder session measuring focus distance against a controlled two-depth target - not a
user-facing toggle.

## Reproduce

```powershell
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# capability matrix only (no face needed)
adb shell am instrument -w -e class com.streamcam.FacePriorityTest -e phase capability `
  com.streamcam.test/androidx.test.runner.AndroidJUnitRunner

# behaviour phases: noface | faces | alternate | regions | many | probe | all
adb shell am instrument -w -e class com.streamcam.FacePriorityTest -e phase alternate -e seconds 6 `
  com.streamcam.test/androidx.test.runner.AndroidJUnitRunner
```

Reports and frames on device:

```
/sdcard/Android/data/com.streamcam/files/face_priority_diagnostics.txt
/sdcard/Android/data/com.streamcam/files/face_frames/*.jpg
/sdcard/Android/data/com.streamcam/files/focus_diagnostics.txt   (extended matrix)
```

The `alternate` and `many` phases wait for the HAL to report a face before switching modes, so a
timing miss aborts the comparison instead of producing a false negative. Two earlier runs were
discarded for exactly that reason (the face arrived after the block had finished).
