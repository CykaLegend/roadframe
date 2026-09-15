# RoadFrame: instructions for Claude Code

RoadFrame is a native Android car photography coach (Kotlin, CameraX, MediaPipe Tasks Vision,
EfficientDet-Lite0 bundled) that runs fully on the phone. Owner: Nicolas. Target device: Samsung
Galaxy S25 Ultra. Version 0.1.0-beta was generated on 2026-09-13; this file records the review of
2026-09-15 and the agreed direction.

## Start here
1. If `HANDOUT.md` exists, read it completely first. It is the master specification and outranks
   everything else in this file.
2. Read `README.md`, then every file under `app/src/main/java/be/roadframe/coach/`.
   `CoachEngine`, `PoseMath`, `SubjectTracker` and `ShotCategory` are pure Kotlin: change them
   with their tests in `app/src/test`.
3. Build the task list from the handout and confirm it with Nicolas before editing.

## Build and test
- JDK 17, Android SDK 35. `./gradlew test assembleDebug`; the debug APK lands in
  `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- `CoachEngine` stays pure Kotlin and unit-tested. Every new rule gets a test in `app/src/test`.
- Do not add the INTERNET permission unless the handout asks for the optional online coach; the
  manifest strips it on purpose.

## Known issues found in review (all addressed on 2026-09-15, keep for history)
1. `LevelSensor` computed roll from raw device axes, so landscape read 90° forever. Now
   `PoseSensor` + `PoseMath.rollDegrees` add the display rotation and expose pitch; unit-tested
   for portrait and both landscape rotations.
2. `VehicleAnalyzer.analyze` ignored the RGBA row stride. Now `imageProxy.toBitmap()`, and the
   bitmap is rotated upright before inference so boxes come back in screen orientation.
3. Detection ran on the CPU only. Now a GPU switch under `?` loads the float16 model with
   `Delegate.GPU` (falls back to CPU). Measure both on the phone; the stats line shows the ms.

## Current architecture (0.2.0)
- `PoseSensor` (gravity + game rotation vector, 200 Hz, own thread) → `Pose`.
- `VehicleAnalyzer` (about 10 Hz) → `SubjectTracker.onDetection` with the pose at capture time.
- `Choreographer` tick (about 60 Hz) → `SubjectTracker.current` predicts the box from the
  heading and pitch change → `CoachEngine.evaluate` → `CoachOverlayView`.
- `ShotCategory` holds the per-shot geometry and the brief. `CoachStructure` is the selectable
  order of rules. `CoachPrefs` persists the choices.
- ARCore is not used: it takes the camera and its shared-camera mode needs Camera2, not CameraX.
  Camera height therefore comes from pitch only ("LOWER" when the phone points down).

## Agreed direction
- Two clocks. Sensors (gravity plus rotation vector, up to 200 Hz) drive level, pitch and viewing
  angle on every frame; the detector (about 10 Hz) only re-anchors the car box. Between two
  detections, predict the box from gyro yaw and pitch deltas (pixel shift = focal length in pixels
  times tan(delta)).
- Shot categories, not only style modes: front three-quarter, rear three-quarter, side profile,
  front, rear, wheel detail, headlight detail, interior, car in a scene, blue hour. Each has a
  target viewing angle, a fill fraction (car width over frame width), a centre and a "low camera"
  flag. Three-quarter shots keep more room in front of the nose.
- Viewing angle fully offline: a one-tap "nose" calibration (the user taps when facing the nose
  head-on, angle 0), then angle = camera heading change since calibration, with the heading taken
  from the rotation-vector sensor projected on the camera axis. Negative angle = the car's left
  flank is visible.
- Live arrows: edge chevrons for pan and tilt, in/out arrows for distance, a curved arrow with
  degrees to go for walking around the car, a level line that rotates with roll, and a "get
  lower" arrow when pitch says the phone points down. A ring around the shutter fills as errors
  shrink and turns green with a haptic tick when everything is inside tolerance.
- Optional online coach behind a toggle for what geometry cannot judge: rim versus tread, the
  photographer's reflection, background clutter, light on the body. Never required for the arrows.
- Teaching layer: a "brief" per shot category (where to park, where to stand, phone settings for
  Samsung Pro mode, checklist) and short lessons. Every arrow should be explainable in one line.

## Reference
`reference/shotlist-rt.html` is a working browser prototype of this guidance engine: per-category
targets, heading-based angle propagation, and the arrow logic in `computeGuidance`. Port the
logic and the category data into Kotlin; do not port its UI or its web-only workarounds.
