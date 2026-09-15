# RoadFrame 0.1.0-beta validation

Validated on 2026-09-13:

- `testDebugUnitTest`: 9 tests passed, 0 failed.
- `assembleDebug`: arm64-v8a, x86_64 and universal APKs built successfully.
- `lintDebug`: 0 errors. Five dependency-update notices remain because the latest AppCompat and
  CameraX releases were not adopted in this SDK 35 beta.
- APK signature: Android APK Signature Scheme v2 verified with one debug signer.
- Packaged ABIs: the delivered APK contains arm64-v8a native libraries and no x86_64 libraries.
- Packaged model: `assets/efficientdet_lite0.tflite` is present.
- Effective permissions: camera, vibration and Android's generated unexported-receiver permission;
  no Internet or network-state permission.

## Remaining device check

The build environment has no KVM hardware virtualization, so an Android camera emulator could not
boot. The live CameraX feed, Samsung zoom-ratio mapping and detector overlay therefore still need a
short test on Nicolas's Galaxy S25 Ultra. This is an installable debug beta, not a Play Store release.
