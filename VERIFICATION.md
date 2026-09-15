# Build Verification

Verified on 2026-09-15 in a clean Linux Android build environment.

## Automated results

- `clean test lintDebug assembleDebug`: passed
- Risk-engine unit tests: 6 passed, 0 failed, 0 skipped
- Android lint: 0 errors (15 non-blocking style/compatibility warnings)
- APK signature: valid Android APK Signature Scheme v2 debug signature
- Package: `com.ankitkupanda.privacyguard`
- Version: `1.0.0` (`versionCode` 1)
- Minimum Android: 8.0 / API 26
- Target Android SDK: API 35
- APK SHA-256: `bb3072776d95d8c1cf20cf01335f6b154120f466ad3cc17c1f966fb191517056`

## Package inspection

The final merged APK was inspected with Android Build Tools 35:

- `INTERNET` permission: absent
- `ACCESS_NETWORK_STATE` permission: absent
- Camera foreground service type: present
- Front-camera feature: required
- Overlay permission declaration: present
- Bundled face detector: present for ARM64, ARMv7, x86, and x86_64
- Main activity and protection service: not exported except for the launcher activity

## What still requires a phone

This build environment has no physical front camera. Camera sensitivity, manufacturer-specific battery management, and the system overlay must therefore be checked once on the target phone. Follow the README installation steps, then test with one face, two faces, no face, Pause, and Stop before relying on the app around sensitive information.

The APK is debug-signed for direct testing. It must be release-signed with the owner's private key before Play Store distribution.
