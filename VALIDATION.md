# RoadFrame validation

## 0.2.0 real-time coach, 2026-09-15 (this branch)

Validated in the build container:

- `testDebugUnitTest`: 50 tests passed, 0 failed (coach rules and hysteresis, pose maths, box
  prediction, tracker, shot data).
- `assembleDebug`: arm64-v8a, x86_64 and universal APKs built.
- `lintDebug`: 0 errors. Warnings are dependency-update notices and programmatic chip text.
- Packaged models: `efficientdet_lite0.tflite` (int8) and `efficientdet_lite0_fp16.tflite`.
- Effective permissions unchanged: camera and vibration; no Internet or network-state permission.

## Still to measure on the Galaxy S25 Ultra

The container has no camera and no KVM, so these need the phone. Switch on the stats line under
`?` and note:

1. DET rate and ms on CPU, then flip the GPU switch and compare.
2. LAG (capture to coach) and BOX age with the phone still and while panning.
3. That the box stays on the car while panning fast between detections (gyro prediction), and
   that turning right moves the box left. If it moves the wrong way, the field-of-view sign or
   the sensor characteristics are wrong for this camera.
4. Level in portrait and in both landscape orientations reads 0° with the horizon flat.
5. NOSE calibration: tap facing the nose, walk to the front-left corner, the angle should read
   about -45°.
6. Thermal behaviour after ten minutes of coaching.

## 0.1.0-beta, 2026-09-13

- `testDebugUnitTest`: 9 tests passed. `assembleDebug` and `lintDebug` clean. APK Signature
  Scheme v2 verified with one debug signer. Live camera never tested (no KVM).
