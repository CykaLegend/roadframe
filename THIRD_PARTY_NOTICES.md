# Third-party notices

RoadFrame bundles two variants of EfficientDet-Lite0, downloaded from Google's official MediaPipe
model storage:

- `efficientdet_lite0.tflite` (int8, CPU):
  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite
- `efficientdet_lite0_fp16.tflite` (float16, GPU delegate):
  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite

The application uses AndroidX CameraX and Google MediaPipe Tasks Vision through their published
Android libraries. Consult the upstream projects for their current notices and licenses:

- https://developer.android.com/media/camera/camerax
- https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android
