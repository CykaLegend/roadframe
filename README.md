# RoadFrame — on-device car and motorcycle photo coach

RoadFrame is a native Android camera prototype that detects cars and motorcycles locally and
turns measurable composition problems into one live instruction at a time. It is designed for
the Samsung Galaxy S25 Ultra but uses standard CameraX APIs and should run on most modern Android
phones.

## What works in this beta

- Live CameraX preview and full-resolution photo capture.
- Bundled EfficientDet-Lite0 neural model; no network connection is required.
- Automatic car, van/truck, bus and motorcycle recognition.
- Explainable coaching for level, safe margins, subject size, frame position, exposure and
  coarse background clutter.
- Separate Balanced, Sale and Cinematic targets.
- Detail mode: drag a box around a wheel, badge, repair or cleaned panel when the whole vehicle
  is no longer visible to the detector.
- Camera tap-to-focus, zoom shortcuts and photos saved to `Pictures/RoadFrame`.
- No account, telemetry, upload or `INTERNET` permission.

## Install the APK

1. Copy `RoadFrame-0.1.0-beta-arm64.apk` to the Android phone.
2. Open it and allow installation from the file manager when Android asks.
3. Grant camera access.
4. Point the camera at one vehicle. Use the **SUBJECT** button if Auto chooses the wrong type.
5. Follow one instruction until it changes. A green box and vibration mean the measurable
   composition target is satisfied.

The `.6×` shortcut is enabled only when CameraX reports that the phone exposes an ultra-wide
zoom ratio. Samsung firmware can expose physical lenses differently, so a shortcut may select a
logical-camera zoom rather than promising a particular sensor.

## Modes

- **Balanced:** neutral training composition with comfortable margins.
- **Sale:** centered and slightly wider so the complete vehicle is documented clearly.
- **Cinematic:** smaller subject placed left, with deliberate negative space on the right.
- **Detail:** manually mark a close-up subject, then receive framing and exposure coaching.

## Honest beta boundary

This build understands *where the detected subject is* and can measure the frame around it. It
does not yet understand wheel direction, identify the front versus rear of a vehicle, or judge a
specific three-quarter angle like an experienced photographer. Those require a vehicle-keypoint
model trained on labelled car and motorcycle photographs. The architecture keeps that detector
replaceable so a future model can add those capabilities without rewriting the camera or coach.

## Build from source

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew test assembleDebug
```

The S25-compatible debug APK is generated at
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

## Included model

`app/src/main/assets/efficientdet_lite0.tflite` is Google's EfficientDet-Lite0 object detector
distributed for MediaPipe Tasks. The source URL is documented in `THIRD_PARTY_NOTICES.md`.
