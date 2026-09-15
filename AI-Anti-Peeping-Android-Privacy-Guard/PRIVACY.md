# Privacy Policy

PrivacyGuard AI processes front-camera frames on the device for face detection and owner matching.

- No photo or video is saved.
- During enrollment, the app creates 12 numeric visual signatures from the owner's face.
- The numeric signatures are encrypted with AES-GCM using a non-exportable key generated in Android Keystore.
- The encrypted profile is stored in the app's private preferences and can be deleted with **Delete owner profile**.
- Live camera frames and live signatures stay in memory and are discarded after analysis.
- No analytics, advertising SDK, account, or cloud service is included.
- The application does not request Android's `INTERNET` permission.
- The application does not request Android's `ACCESS_NETWORK_STATE` permission.

The front camera is active only while the user-started foreground protection service is running. Android displays a persistent notification during that time. The user can stop protection from the app, notification, or shield.

Uninstalling the app removes its private encrypted profile. Android backup is disabled for this app.

The face profile is an application-level visual signature. It is not connected to Android's biometric database and is not a replacement for the phone's PIN, password, fingerprint, or secure Face Unlock.
