package com.ankitkupanda.privacyguard;

/** Central place for behavior that is safe to tune without changing camera code. */
public final class AppConfig {
    public static final long PEEPING_TRIGGER_MS = 800L;
    public static final long NO_FACE_TRIGGER_MS = 1_500L;
    public static final long SAFE_RELEASE_MS = 1_500L;
    public static final long EMERGENCY_PAUSE_MS = 10_000L;

    public static final String PREFS = "privacy_guard_settings";
    public static final String PREF_STRICT_NO_FACE = "strict_no_face";

    private AppConfig() {
        // Constants only.
    }
}
