# RoadFrame — on-device car and motorcycle photo coach

RoadFrame is a native Android camera app that watches the live preview, understands where the
car is and how the phone is held, and tells you one physical move at a time: **WALK LEFT 30°**,
**BACK**, **LOWER**, **LEVEL**, **PERFECT**, **SHOOT**. Everything runs on the phone. It is designed
for the Samsung Galaxy S25 Ultra but uses standard CameraX and sensor APIs.

## How it works: two clocks

```
CameraX Preview ──────────────────────────────────────────► screen (smooth, untouched)
CameraX ImageAnalysis (640×480, KEEP_ONLY_LATEST)
   └─► VehicleAnalyzer: EfficientDet-Lite0, ~10 Hz, own thread ─► car box + capture time
Gravity + game-rotation-vector sensors, up to 200 Hz ─► roll, pitch, heading
   └─► SubjectTracker: predicts the box from the heading and pitch change since the
        last detection (pixel shift = focal length × tan Δ), so the box moves with you
        between detections and the detector's own delay is cancelled out
Every screen frame (~60 Hz): CoachEngine (pure Kotlin, hysteresis) ─► one instruction + arrows
```

The detector only *re-anchors* the box. The instruction reacts as fast as the gyro, not as
fast as the neural network.

## Shots, not modes

Pick a shot with the **SHOT** chip. Each shot has a target viewing angle, a fill fraction, a
centre, a "get low" flag and a written brief (**BRIEF** chip: where to park, where to stand,
distance, height, lens, checklist, and how to read the arrows).

| # | Shot | What the coach measures |
|---|------|-------------------------|
| 1 | Front three-quarter | angle ±45°, fill 72 %, low camera, room in front of the nose |
| 2 | Rear three-quarter | angle ±135°, fill 72 %, low camera, room in front of the nose |
| 3 | Side profile | angle ±90° (±6°), fill 84 %, low camera |
| 4 | Front straight-on | angle 0° (±6°), fill 62 %, low camera |
| 5 | Rear straight-on | angle 180° (±6°), fill 62 %, low camera |
| 6–8 | Wheel, headlight, interior | drag a box around the detail; margins, size, level, light |
| 9 | Car in a place | angle ±45° (±15°), fill 24 %, car on a thirds point |
| 10 | Rolling shot | brief only: the phone is in a moving car |
| 11 | Blue hour | as the hero shot, tripod and timer in the brief |

**Viewing angle, offline.** The generic detector cannot tell the nose from the tail. Stand in
front of the car, face the nose head-on, tap **NOSE 0°**. From then on the angle is the change in
camera heading since that tap, taken from the gyro-based rotation vector (no compass, so the
steel of the car cannot bend it). The coach then says "WALK LEFT 30°" with a curved arrow.
Without the tap it falls back to the shape of the box (wide = side, tall = end) and just says
"WALK AROUND".

## One instruction at a time

The engine evaluates every rule every frame, but speaks only the first active one in the
chosen **coach structure** (the `?` button):

- **RoadFrame** (default): cut-off car, level if far off, angle, size, aim left/right, aim
  up/down, get lower, fine level, light, background. Big moves first; each later step survives
  the earlier ones.
- **Handoff**: the order from the build brief. Aim, size, height, angle, level, light,
  background.
- **Prototype**: the order of the browser prototype. Angle, height, size, aim, level, light.

Every rule has an enter threshold and a smaller exit threshold (hysteresis), so an instruction
appears as soon as the error is clearly there and only disappears once it is clearly fixed. When
a step is fixed the coach says **GOOD** for half a second, then the next word. When everything is
inside tolerance the ring around the shutter goes green, the phone ticks, and the headline says
**PERFECT**, then **SHOOT**.

## Arrows

- Chevron at the left or right edge: turn the phone that way.
- Chevron at the top or bottom: tilt the phone that way.
- Arrows beside the car pointing in: closer or zoom in ("ZOOM 3×" when the brief wants a
  longer lens than you have selected). Pointing out: back up.
- Curved arrow with degrees: walk around the car that way, camera on the car.
- Double chevron on the right: the phone points down, crouch and aim level.
- Line in the middle: the horizon, rotating with the phone. Green means level.
- Ring around the shutter: fills as the errors shrink.

## Stats line

Switch it on under `?`. `DET 9/s 41ms GPU · LAG 78ms · BOX 60ms · UI 60/s · ROLL 1° PITCH 4° · ANGLE -38°`

- DET: detections per second and inference time, and which delegate runs (CPU int8 model or
  GPU float16 model, chosen under `?`).
- LAG: capture to coach for the last detection.
- BOX: age of the last detector anchor. The box on screen is predicted forward from it.
- UI: screen updates per second.

These are measured on the phone, not estimated.

## Honest boundary

This build measures where the car is and how the phone is held. It does **not** see wheel
direction, front versus rear by itself, keypoints, or judge reflections and clutter beyond edge
density. Those need a vehicle-specific model; the detector is replaceable so one can be added
without rewriting the camera, sensors or coach. There is no cloud, no account, no telemetry and
no `INTERNET` permission.

## Build from source

Requirements: JDK 17 or newer and Android SDK 35.

```bash
./gradlew test assembleDebug
```

The S25-compatible debug APK is at `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
`CoachEngine`, `PoseMath`, `SubjectTracker` and the shot data are pure Kotlin with unit tests
in `app/src/test`.

## Included models

`app/src/main/assets/efficientdet_lite0.tflite` (int8, for the CPU) and
`efficientdet_lite0_fp16.tflite` (float16, for the GPU delegate) are Google's EfficientDet-Lite0
detectors distributed for MediaPipe Tasks. Source URLs are in `THIRD_PARTY_NOTICES.md`.
