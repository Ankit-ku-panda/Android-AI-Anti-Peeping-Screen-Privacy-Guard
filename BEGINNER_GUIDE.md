# PrivacyGuard AI: Beginner Guide

This guide explains the project from the first button tap to the privacy shield. You do not need to understand every Android API before making small, safe changes.

## 1. Tools you need

Install:

1. Android Studio.
2. Android SDK 35 from Android Studio's SDK Manager.
3. JDK 17. Android Studio normally includes a compatible JDK.
4. An Android phone with a front camera, Android 8.0 or newer, and a USB cable for device testing.

Open the folder containing `settings.gradle` in Android Studio. Let Gradle download dependencies, then select the `app` run configuration.

## 2. Folder map

```text
AI-Anti-Peeping-Android-Privacy-Guard/
├── app/                         Android application module
│   ├── build.gradle             Module versions and dependencies
│   ├── proguard-rules.pro       Release-code shrinking rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/.../        Java behavior
│       │   └── res/             Colors, styles, icons, backgrounds
│       └── test/java/.../       Unit tests
├── gradle/                      Gradle wrapper files
├── .github/workflows/           Automatic GitHub build
├── build.gradle                 Root Android plugin configuration
├── settings.gradle              Declares the app module
├── gradle.properties            Gradle performance/settings
├── gradlew / gradlew.bat        Build launchers
├── build_windows.bat            Simple Windows build command
├── README.md                    Install and overview
├── PRIVACY.md                   Exact data handling
└── VERIFICATION.md              Build/test evidence
```

Folders named `build` and `.gradle` are generated. Do not edit them because Gradle recreates them.

## 3. What happens when the app runs

1. `MainActivity` displays controls and asks for Camera, Notifications, and Display-over-other-apps access.
2. Tapping **Enroll owner face** sends the `ENROLL` action to `ProtectionService`.
3. `ProtectionService` starts a required foreground notification and opens `FaceMonitor`.
4. `FaceMonitor` reads the front camera, rotates each grayscale frame correctly, and asks bundled ML Kit to find faces.
5. For one clear face, the eye landmarks align position, scale, and roll. `FaceSignature` then converts the aligned crop into a normalized numeric array. The raw frame is discarded.
6. Enrollment collects 12 arrays. `FaceProfileStore` encrypts them with Android Keystore and saves only the encrypted numbers.
7. During protection, `OwnerMatcher` compares a live signature with the 12 enrolled samples and uses an enrollment-calibrated requirement.
8. `OwnerAuthorizationGate` keeps a confirmed single owner authorized across a very short score drop, but cancels immediately for multiple faces.
9. `RiskEngine` waits long enough to ignore brief camera mistakes, then decides whether protection is required.
10. `OverlayController` displays or removes the privacy shield over other apps.

## 4. Each Java file

### `AppConfig.java`

This is the first file to edit for behavior tuning.

- `PEEPING_TRIGGER_MS`: how long an unknown/additional face must remain before shielding.
- `NO_FACE_TRIGGER_MS`: no-face delay when strict mode is on.
- `SAFE_RELEASE_MS`: how long a safe view must remain before clearing the shield.
- `EMERGENCY_PAUSE_MS`: notification/shield pause length.
- `ENROLLMENT_SAMPLE_COUNT`: number of owner samples.
- `ENROLLMENT_SAMPLE_INTERVAL_MS`: minimum time between samples.
- `OWNER_MATCH_THRESHOLD`: required similarity from 0 to 1. Higher is stricter.
- `OWNER_AUTHORIZATION_GRACE_MS`: temporary tolerance after a confirmed owner match.

Change one constant, run tests, rebuild, and test on the phone. Do not lower the match threshold just to make acceptance easier; that can authorize the wrong person.

### `MainActivity.java`

This creates the dashboard entirely in Java. It:

- shows live status;
- requests permissions;
- starts enrollment or protection;
- stops the service;
- confirms before deleting the profile.

Change headings, instructions, and button layout here. Permission logic is near `beginPermissionFlow`.

### `FaceMonitor.java`

This is the camera layer. It selects the front Camera2 device and a frame size near 640×480. The inner `GrayFrame` class copies and rotates the camera's Y/luminance plane. ML Kit returns face boxes and head angles. Only a face at least 96 pixels wide/tall and close to frontal receives a signature.

Change `ANALYSIS_INTERVAL_MS` for analysis frequency or `MIN_FACE_PIXELS` for required face size. Faster analysis consumes more battery. Smaller faces make recognition less reliable.

### `FaceSignature.java`

This first maps both detected eyes to fixed coordinates, correcting translation, scale, and head roll. It then converts the aligned face to a 1,472-value array using three types of information:

- LBP: small local light/dark texture patterns;
- HOG: edge direction patterns;
- appearance: a low-resolution, normalized view.

Histogram equalization reduces lighting changes. L2 normalization makes cosine comparison possible. This file never writes an image.

Changing its array layout invalidates profiles already enrolled. If you change `DIMENSION` or feature order, update the profile format key in `FaceProfileStore`, add tests, and ask users to re-enroll.

### `OwnerMatcher.java`

This compares every live candidate with every owner sample using cosine similarity. Version 1.2 examines similarities within the owner's enrollment to calibrate the requirement between 60% and the configured 72% maximum. It returns the decision, live score, and required score.

Change matching rules here without modifying camera code.

### `OwnerAuthorizationGate.java`

This keeps an already-confirmed owner authorized through a brief 0.9-second score drop caused by motion or one weak frame. Two or more faces cancel the grace immediately. Change the duration in `AppConfig.java`, not in this class.

### `FaceProfileStore.java`

This serializes the 12 numeric arrays, encrypts them with AES-GCM, and saves the ciphertext in private preferences. The encryption key is created by Android Keystore and cannot be exported normally. `clear()` removes both ciphertext and key. Version 1.2 removes incompatible v1.1 profiles so the user cannot accidentally compare aligned live faces with old unaligned samples.

Do not add raw photo storage here. If the encrypted format changes, use a new profile preference name so old data is safely ignored.

### `RiskEngine.java`

This is a time-smoothed state machine. It treats these as risks:

- more than one face;
- one face that does not match the owner;
- zero faces when strict no-face mode is on.

It requires a persistent risk before shielding and a persistent safe state before releasing. This avoids visible flicker when one frame is missed.

Change decision behavior here and add a matching case to `RiskEngineTest.java`.

### `ProtectionService.java`

This is the coordinator and Android foreground service. It receives Start, Enroll, Pause, and Stop actions; connects all other classes; updates the ongoing notification; and exposes an in-memory status snapshot to the activity.

Change notification/status wording here. Keep `startForeground` at service startup because modern Android requires it for background camera use.

### `OverlayController.java`

This builds the opaque full-screen shield using `TYPE_APPLICATION_OVERLAY`. All window changes are posted to Android's main thread. `FLAG_SECURE` prevents screenshots of the shield.

Change shield text, colors, icon size, and buttons here. Do not remove the Pause and Stop exits.

## 5. Resource and configuration files

- `AndroidManifest.xml`: declares camera, overlay, foreground-service, notification, vibration, and wake-lock permissions. It explicitly removes transitive internet/network permissions.
- `res/drawable/*.xml`: vector icon and rounded/color backgrounds.
- `res/values/colors.xml`: named colors.
- `res/values/styles.xml`: dark application theme.
- `res/values/strings.xml`: app and notification-channel names.
- `app/build.gradle`: application ID, Android 8 minimum, Android 15 target, version, Java 17, ML Kit, and JUnit.
- Root `build.gradle`: selects the Android Gradle Plugin.
- `settings.gradle`: tells Gradle the project name and `app` module.
- `gradle.properties`: memory and AndroidX settings.
- `.gitignore`: prevents generated and machine-local files from entering Git.
- `.github/workflows/android.yml`: builds and tests every GitHub push or pull request.

## 6. Tests

- `RiskEngineTest`: authorized, unknown, multiple-face, no-face, safe-release, and pause decisions.
- `FaceSignatureTest`: output size/normalization, brightness stability, and eye-alignment stability under translation, scale, and roll.
- `OwnerMatcherTest`: exact match, unrelated rejection, and calibration safety floor.
- `OwnerAuthorizationGateTest`: brief single-face tolerance and immediate multiple-face cancellation.

Run:

```bash
./gradlew test lintDebug assembleDebug
```

`test` checks pure Java logic. `lintDebug` checks Android configuration/code patterns. `assembleDebug` creates the installable test APK.

## 7. A safe editing routine

1. Make one focused change in the file responsible for that feature.
2. Run `./gradlew test lintDebug assembleDebug`.
3. Install the new APK over the old debug build.
4. If signature dimensions or storage changed, delete the old owner profile and enroll again.
5. Test owner, unknown person, two people, no face, pause, stop, screen rotation, and low light.
6. Revert the focused change if the test worsens instead of editing several other components at once.

## 8. Limits you must keep visible to users

This is not hardware-backed biometric authentication and has no depth/liveness test. A photo or video may spoof it. No threshold works perfectly for all faces, lighting, and cameras. Android can also prevent overlays on protected system/banking screens or stop background work under aggressive battery policies.

Use a strong phone lock and treat this app as an additional privacy shield.
