# RoadFrame: instructions for Claude Code

RoadFrame is a native Android car photography coach (Kotlin, CameraX, MediaPipe Tasks Vision,
EfficientDet-Lite0 bundled) that runs fully on the phone. Owner: Nicolas. Target device: Samsung
Galaxy S25 Ultra. Version 0.1.0-beta was generated on 2026-09-13; this file records the review of
2026-09-15 and the agreed direction.

## Start here
1. If `HANDOUT.md` exists, read it completely first. It is the master specification and outranks
   everything else in this file.
2. Read `README.md`, then every file under `app/src/main/java/be/roadframe/coach/`.
3. Build the task list from the handout and confirm it with Nicolas before editing.

## Build and test
- JDK 17, Android SDK 35. `./gradlew test assembleDebug`; the debug APK lands in
  `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- `CoachEngine` stays pure Kotlin and unit-tested. Every new rule gets a test in `app/src/test`.
- Do not add the INTERNET permission unless the handout asks for the optional online coach; the
  manifest strips it on purpose.

## Known issues found in review
1. `LevelSensor` computes roll from raw device axes, `atan2(x, y)`. In landscape roll reads about
   90°, so `CoachEngine` returns ROTATE_LEFT/RIGHT forever. Fix: subtract the display rotation
   (Surface.ROTATION_90 = 90°, ROTATION_270 = 270°) before reporting, and expose pitch as well
   (camera pointing down = positive) from the same gravity vector.
2. `VehicleAnalyzer.analyze` copies `planes[0].buffer` into a bitmap assuming no row padding.
   When `rowStride != width * 4` every frame is skewed and the detector sees garbage. Fix: use
   `imageProxy.toBitmap()` (CameraX 1.5) or honour rowStride/pixelStride.
3. Detection runs on the CPU (default delegate, about 93 ms per frame on the S25 Ultra). Try
   `BaseOptions.setDelegate(Delegate.GPU)` and measure.

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
