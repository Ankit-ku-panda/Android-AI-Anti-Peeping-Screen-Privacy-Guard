# AI Anti-Peeping Android Privacy Guard

PrivacyGuard AI is an Android screen-privacy app that uses the phone's front camera to detect shoulder surfing. When a second face remains visible for 0.8 seconds, it places a full-screen privacy shield over other apps. All face detection happens locally on the phone.

## What this Android version does

- Monitors the front camera in a visible foreground service.
- Detects faces with the bundled, offline Google ML Kit model.
- Shows a full-screen shield over other apps when more than one face is detected.
- Optionally shields when no face is visible, protecting a phone left unattended.
- Waits for a stable safe view before removing the shield, preventing flicker.
- Provides Pause for 10 seconds and Stop controls on both the shield and notification.
- Saves no frames and requests no internet permission.

This app detects the number of visible faces. It does **not** identify who a person is, perform biometric authentication, or guarantee that every glance will be detected.

## Install the supplied APK

1. Copy `PrivacyGuardAI-Android-v1.0.0-debug.apk` to your Android phone.
2. Open the APK from Files or Downloads.
3. If Android asks, allow **Install unknown apps** for the app you used to open it.
4. Tap **Install**, then **Open**.
5. Tap **Start protection**.
6. Allow the Camera permission and, when shown, Notifications.
7. On the Android settings page, enable **Display over other apps** for PrivacyGuard AI.
8. Return to PrivacyGuard AI. Protection starts automatically.
9. Open another app. Keep the front camera unobstructed.

To stop protection, use the app's Stop button, the shield's Stop button, or the ongoing notification.

## Android limitations you should know

Android requires the camera foreground service to be started while PrivacyGuard AI is visible. A persistent notification is mandatory while the camera is active. Some manufacturers aggressively stop background apps; if that happens, allow PrivacyGuard AI to run in the background or exclude it from battery optimization in the phone's settings.

The overlay cannot cover protected system screens such as permission dialogs and some banking/security screens. This is enforced by Android.

## Project structure

| Path | Purpose | Safe changes |
| --- | --- | --- |
| `app/src/main/java/.../AppConfig.java` | Timing and preference keys | Change trigger, release, and pause durations here. |
| `app/src/main/java/.../RiskEngine.java` | Stable privacy decision logic | Change the state machine without touching camera code. |
| `app/src/main/java/.../FaceMonitor.java` | Camera2 and ML Kit face counting | Change camera resolution or detector settings here. |
| `app/src/main/java/.../ProtectionService.java` | Foreground service and notification | Change service actions and notification text here. |
| `app/src/main/java/.../OverlayController.java` | Full-screen shield | Change shield wording, colors, or controls here. |
| `app/src/main/java/.../MainActivity.java` | Main screen and permission flow | Change the dashboard UI here. |
| `app/src/main/res/drawable/` | Backgrounds, buttons, and app icon | Change appearance without editing Java logic. |
| `app/src/test/.../RiskEngineTest.java` | State-machine tests | Add cases when changing risk behavior. |
| `app/build.gradle` | Android version and dependencies | Update SDK or library versions here. |
| `AndroidManifest.xml` | Permissions, activity, service | Change only when adding platform capabilities. |

The Java package is `com.ankitkupanda.privacyguard`, and the minimum supported version is Android 8.0 (API 26).

## Build from source

Install Android Studio with Android SDK 35, open this folder, let Gradle sync, and run the `app` configuration. From a terminal:

```bash
./gradlew clean test lintDebug assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On Windows, you can run `build_windows.bat` after Android Studio has installed the SDK and `ANDROID_HOME` is set.

## Release signing

The included APK is a debug-signed build intended for testing and direct installation. Before publishing to Google Play, create a private release keystore, keep it outside the repository, configure a Gradle signing block, and build an Android App Bundle with `./gradlew bundleRelease`.

## Privacy and security

See [PRIVACY.md](PRIVACY.md). The manifest deliberately has no `INTERNET` permission. Face frames are passed directly from Camera2 to ML Kit in memory and immediately closed after analysis.

## License

MIT License. See [LICENSE](LICENSE).
