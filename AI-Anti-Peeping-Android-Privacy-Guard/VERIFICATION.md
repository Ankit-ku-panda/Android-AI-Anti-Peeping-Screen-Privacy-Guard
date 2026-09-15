# Build Verification

Verified on 2026-09-15 in a Linux Android build environment.

## Automated results

- `test lintDebug assembleDebug`: passed
- Unique unit tests: 17 passed, 0 failed, 0 errors, 0 skipped
- The same test set passed for both debug and release variants
- Android lint: 0 errors (25 non-blocking localization/style/compatibility warnings)
- APK signature: valid Android APK Signature Scheme v2 debug signature
- Package: `com.ankitkupanda.privacyguard`
- Version: `1.2.0` (`versionCode` 3)
- Minimum Android: 8.0 / API 26
- Target Android SDK: Android 15 / API 35
- APK size: 41,108,165 bytes
- APK SHA-256: `373286b12facfb548a8f0ded25d6d38b55a3bfab429da087f8c8accf62ad1ca5`

## Logic covered by tests

- Exactly one authorized face stays clear.
- Exactly one unknown face triggers the shield after 0.8 seconds.
- An additional face triggers the shield after 0.8 seconds.
- Strict no-face mode uses the 1.5-second delay.
- Disabling strict mode treats no face as safe.
- The shield requires a stable safe view before release.
- Emergency pause immediately clears protection for its configured period.
- Face signatures have the expected dimension and normalization.
- Moderate brightness changes remain stable in the synthetic signature test.
- Eye alignment remains stable under synthetic translation, scale, and roll changes.
- Exact owner templates authorize; unrelated orthogonal templates reject.
- Enrollment calibration cannot lower the requirement below its 60% safety floor.
- The 0.9-second grace survives a brief single-face score drop.
- Multiple faces cancel authorization grace immediately.

## Package inspection

The merged APK was inspected with Android Build Tools 35:

- `INTERNET` permission: absent
- `ACCESS_NETWORK_STATE` permission: absent
- Camera permission and camera foreground-service permission: present
- Camera foreground-service type: present
- Front-camera feature: required
- Overlay permission declaration: present
- Main activity exported only as the required launcher entry
- Protection service not exported
- Bundled detector supports ARM64, ARMv7, x86, and x86_64

## What still requires a physical phone

No Android device was connected to this build environment. Therefore installation, real front-camera enrollment, biometric false-accept/false-reject rates, overlay display, device rotation, thermal/battery behavior, and Samsung/iQOO background-management behavior could not be physically validated here.

Before relying on the app, test it on each target phone with:

1. Owner in bright and dim light.
2. Unknown person alone.
3. Owner plus another person.
4. No face.
5. Glasses, different angles, and normal working distance.
6. A printed/displayed owner photo to understand the no-liveness limitation.
7. Pause, Stop, rotation, screen off/on, and battery saver.

The app is a privacy aid, not secure lock-screen biometric authentication. The APK is debug-signed for direct testing and must be release-signed with the owner's private key before Play Store distribution.
