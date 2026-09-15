# AI Anti-Peeping Android Privacy Guard

PrivacyGuard AI is an Android privacy aid that uses the front camera to authorize an enrolled owner and detect shoulder surfing. One recognized owner keeps the screen clear. One unknown face or more than one visible face activates a full-screen privacy shield after a short confirmation delay.

Everything runs locally. The APK has no Android internet permission, saves no camera image, and encrypts its numeric owner profile with a key held by Android Keystore.

## Version 1.2 features

- Guided enrollment of 12 owner-face samples: front, slight turn, then front again.
- Eye-landmark alignment for changes in face position, distance, scale, and head roll.
- A per-owner threshold calibrated from the 12 enrollment samples.
- A 0.9-second single-owner grace window for brief camera-score drops; multiple faces cancel it immediately.
- Local owner matching using an illumination-normalized visual signature.
- Encrypted numeric owner profile; no owner photo is stored.
- Shield for one unrecognized face after 0.8 seconds.
- Shield for any additional face after 0.8 seconds.
- Optional shield when no face is visible after 1.5 seconds.
- Stable 1.5-second safe period before the shield disappears.
- Re-enroll and securely delete the owner profile from the main screen.
- Pause for 10 seconds and Stop controls on the shield and notification.
- Bundled ML Kit face detector; no model download is required.

## Install and enroll

1. Copy `PrivacyGuardAI-Android-v1.2.0-debug.apk` to the phone.
2. Open it from Files or Downloads.
3. If Android asks, allow **Install unknown apps** for the app that opened the APK.
4. Tap **Install**, then **Open**.
5. Tap **Enroll owner face**.
6. Allow Camera and Notifications when asked.
7. On the Android settings page, enable **Display over other apps** for PrivacyGuard AI, then return to the app.
8. Enroll in private, in even light, with only the owner's face visible. Follow the front/turn/front instructions until all 12 samples are captured.
9. When the screen says **Owner face enrolled and authorized**, protection is already active. Later, use **Start protection** to start it again.

Version 1.2 uses a corrected face-profile format. After updating from version 1.1, the old profile is removed automatically and **you must enroll again**.

Keep the front camera unobstructed. On Samsung or iQOO phones, allow background activity or exclude PrivacyGuard AI from battery optimization if the system stops it.

## Expected behavior

| Camera view | Result |
| --- | --- |
| Exactly one enrolled owner | Screen stays clear and shows **Owner authorized**. |
| Exactly one unknown face | Privacy shield appears after 0.8 seconds. |
| Owner plus another face | Privacy shield appears after 0.8 seconds. |
| Two or more faces without the owner | Privacy shield appears after 0.8 seconds. |
| No face, strict setting on | Privacy shield appears after 1.5 seconds. |
| Safe view returns | Shield disappears after a stable 1.5 seconds. |

## Important accuracy and security limits

This feature is local visual face matching, not Android's hardware-backed lock-screen Face Unlock. No camera-based recognizer can promise zero false matches or zero missed matches. Lighting, camera angle, face size, masks, glasses, and phone movement affect accuracy. A photo or video may spoof this version because it does not perform depth or liveness verification.

Use the app as an extra privacy layer, not as the only protection for banking, passwords, medical records, or other high-risk information. Keep a PIN/password and Android's lock screen enabled. Test the APK on the actual phone before depending on it.

If the owner is rejected too often, read the live percentage shown on the main screen and re-enroll in the same lighting and distance normally used. Version 1.2 starts at a 72% requirement and can calibrate as low as 60% from the owner's own enrollment variation. Advanced users can tune the maximum in `AppConfig.java`, but lowering the safety floor increases the risk of authorizing another person.

## Project structure and safe changes

| Path | Purpose | Safe changes without touching the rest |
| --- | --- | --- |
| `app/src/main/java/.../AppConfig.java` | All main timing and matching constants | Change delays, sample count, or match threshold here. |
| `app/src/main/java/.../FaceMonitor.java` | Camera2 capture, rotation, ML Kit detection, and face cropping | Change analysis resolution, minimum face size, or detector settings here. |
| `app/src/main/java/.../FaceSignature.java` | Turns a cropped grayscale face into a numeric signature | Change the local feature algorithm only if you also update its tests and stored-profile format. |
| `app/src/main/java/.../OwnerMatcher.java` | Compares live signatures with enrolled samples | Change comparison and threshold behavior here. |
| `app/src/main/java/.../OwnerAuthorizationGate.java` | Smooths very short single-face score drops | Change the grace behavior here without weakening multiple-face detection. |
| `app/src/main/java/.../FaceProfileStore.java` | Encrypts and stores the numeric owner profile | Change storage only here; never save raw images. |
| `app/src/main/java/.../RiskEngine.java` | Delayed shield/release state machine | Change the privacy decision rules here without touching camera code. |
| `app/src/main/java/.../ProtectionService.java` | Connects camera, authorization, risk, overlay, and notification | Change service actions and status text here. |
| `app/src/main/java/.../OverlayController.java` | Full-screen shield UI | Change shield text, colors, and buttons here. |
| `app/src/main/java/.../MainActivity.java` | Dashboard, permissions, enrollment controls | Change the main screen here. |
| `app/src/main/res/drawable/` | Panels, buttons, shield, and icon | Change visual appearance without editing Java logic. |
| `app/src/main/res/values/` | App name, theme, and colors | Change shared visual values here. |
| `app/src/test/...` | Pure-Java tests for matching and decisions | Add or update tests whenever logic changes. |
| `app/build.gradle` | App version, Android SDK, and dependencies | Update versions and dependencies here. |
| `app/src/main/AndroidManifest.xml` | Permissions, launcher activity, service | Change only when adding an Android platform capability. |

The Java package is `com.ankitkupanda.privacyguard`. The minimum version is Android 8.0 (API 26), and the target SDK is Android 15 (API 35).

Read [BEGINNER_GUIDE.md](BEGINNER_GUIDE.md) for a from-scratch explanation of every important folder, file, data flow, and modification point.

## Build from source

Install Android Studio with JDK 17 and Android SDK 35. Open the project folder, wait for Gradle sync, and run the `app` configuration. From a terminal:

```bash
./gradlew clean test lintDebug assembleDebug
```

On Windows, run `build_windows.bat` after Android Studio installs the SDK and `ANDROID_HOME` is set. The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release signing

The supplied APK is debug-signed for direct testing. Before distributing through Google Play, create a private release keystore, keep it outside the repository, configure release signing, and build an Android App Bundle with `./gradlew bundleRelease`.

## Privacy and verification

See [PRIVACY.md](PRIVACY.md) for the data policy and [VERIFICATION.md](VERIFICATION.md) for automated build evidence.

## License

MIT License. See [LICENSE](LICENSE).
