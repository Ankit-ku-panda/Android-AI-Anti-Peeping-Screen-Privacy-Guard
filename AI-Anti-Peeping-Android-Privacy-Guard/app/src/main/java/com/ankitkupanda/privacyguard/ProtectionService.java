package com.ankitkupanda.privacyguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Foreground camera service for owner authorization and anti-peeping protection. */
public final class ProtectionService extends Service implements FaceMonitor.Listener {
    public static final String ACTION_START = "com.ankitkupanda.privacyguard.START";
    public static final String ACTION_ENROLL = "com.ankitkupanda.privacyguard.ENROLL";
    public static final String ACTION_STOP = "com.ankitkupanda.privacyguard.STOP";
    public static final String ACTION_PAUSE = "com.ankitkupanda.privacyguard.PAUSE";

    private static final String CHANNEL_ID = "privacy_guard_active";
    private static final int NOTIFICATION_ID = 7301;

    public static final class Snapshot {
        public final boolean running;
        public final boolean shieldActive;
        public final int faceCount;
        public final String status;
        public final boolean paused;
        public final boolean ownerEnrolled;
        public final boolean ownerAuthorized;
        public final boolean enrolling;
        public final int enrollmentProgress;
        public final float similarity;

        Snapshot(boolean running, boolean shieldActive, int faceCount, String status,
                 boolean paused, boolean ownerEnrolled, boolean ownerAuthorized,
                 boolean enrolling, int enrollmentProgress, float similarity) {
            this.running = running;
            this.shieldActive = shieldActive;
            this.faceCount = faceCount;
            this.status = status;
            this.paused = paused;
            this.ownerEnrolled = ownerEnrolled;
            this.ownerAuthorized = ownerAuthorized;
            this.enrolling = enrolling;
            this.enrollmentProgress = enrollmentProgress;
            this.similarity = similarity;
        }
    }

    private static volatile Snapshot latest = new Snapshot(
            false, false, -1, "Protection is stopped", false,
            false, false, false, 0, -1f);

    public static Snapshot snapshot() {
        return latest;
    }

    private final RiskEngine riskEngine = new RiskEngine(
            AppConfig.PEEPING_TRIGGER_MS,
            AppConfig.NO_FACE_TRIGGER_MS,
            AppConfig.SAFE_RELEASE_MS);
    private final OwnerAuthorizationGate authorizationGate = new OwnerAuthorizationGate(
            AppConfig.OWNER_AUTHORIZATION_GRACE_MS);
    private final Object enrollmentLock = new Object();
    private final List<float[]> enrollmentSamples = new ArrayList<>();

    private OverlayController overlay;
    private FaceMonitor faceMonitor;
    private FaceProfileStore profileStore;
    private OwnerMatcher ownerMatcher;
    private NotificationManager notificationManager;
    private long lastNotificationUpdate;
    private long nextEnrollmentSampleAt;
    private boolean lastShieldState;
    private boolean started;
    private volatile boolean enrolling;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        profileStore = new FaceProfileStore(this);
        ownerMatcher = new OwnerMatcher(AppConfig.OWNER_MATCH_THRESHOLD);
        ownerMatcher.setTemplates(profileStore.load());
        latest = snapshot(false, false, -1, "Protection is stopped",
                false, false, 0, -1f);

        overlay = new OverlayController(this, new OverlayController.Actions() {
            @Override
            public void onPauseRequested() {
                pauseProtection();
            }

            @Override
            public void onStopRequested() {
                stopSelf();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseProtection();
            return START_NOT_STICKY;
        }

        if (!started && !startCameraService()) {
            return START_NOT_STICKY;
        }

        if (ACTION_ENROLL.equals(action)) {
            beginEnrollment();
        } else if (!ownerMatcher.hasProfile()) {
            latest = snapshot(true, false, -1,
                    "Enroll the owner's face before starting protection",
                    false, false, 0, -1f);
        }
        return START_NOT_STICKY;
    }

    private boolean startCameraService() {
        Notification notification = buildNotification("Starting on-device face authorization…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!Settings.canDrawOverlays(this)) {
            latest = snapshot(false, false, -1,
                    "Display-over-other-apps permission is missing",
                    false, false, 0, -1f);
            stopSelf();
            return false;
        }

        started = true;
        latest = snapshot(true, false, -1, "Opening front camera…",
                false, false, 0, -1f);
        faceMonitor = new FaceMonitor(this, this);
        faceMonitor.start();
        return true;
    }

    private void beginEnrollment() {
        synchronized (enrollmentLock) {
            enrollmentSamples.clear();
            nextEnrollmentSampleAt = 0L;
            enrolling = true;
        }
        riskEngine.reset();
        authorizationGate.reset();
        overlay.hide();
        latest = snapshot(true, false, -1,
                "Enrollment: look directly at the front camera",
                false, true, 0, -1f);
        notificationManager.notify(NOTIFICATION_ID,
                buildNotification("Owner enrollment started"));
    }

    @Override
    public void onFaceFrame(FaceMonitor.FaceFrame frame) {
        if (enrolling) {
            handleEnrollment(frame);
            return;
        }

        if (!ownerMatcher.hasProfile()) {
            overlay.hide();
            authorizationGate.reset();
            latest = snapshot(true, false, frame.faceCount,
                    "No owner profile — tap Enroll owner face",
                    false, false, 0, -1f);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        OwnerMatcher.Match match = ownerMatcher.match(frame.signatures);
        boolean directAuthorization = frame.faceCount == 1 && match.authorized;
        boolean ownerAuthorized = authorizationGate.update(
                now, frame.faceCount, directAuthorization);
        SharedPreferences preferences = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        boolean strictNoFace = preferences.getBoolean(AppConfig.PREF_STRICT_NO_FACE, true);
        RiskEngine.Decision decision = riskEngine.updateAuthorization(
                now, frame.faceCount, ownerAuthorized, strictNoFace);

        if (decision.protectedScreen) {
            overlay.show(decision.reason);
        } else {
            overlay.hide();
        }
        if (decision.protectedScreen && !lastShieldState) {
            vibrateOnce();
        }
        lastShieldState = decision.protectedScreen;
        latest = snapshot(true, decision.protectedScreen, frame.faceCount,
                statusFor(decision, frame.faceCount, ownerAuthorized, directAuthorization,
                        match.similarity, match.requiredSimilarity),
                decision.paused, ownerAuthorized, 0, match.similarity);

        if (now - lastNotificationUpdate >= 1_000L) {
            notificationManager.notify(NOTIFICATION_ID,
                    buildNotification(notificationText(frame.faceCount, decision,
                            ownerAuthorized)));
            lastNotificationUpdate = now;
        }
    }

    private void handleEnrollment(FaceMonitor.FaceFrame frame) {
        int progress;
        String guidance;
        long now = SystemClock.elapsedRealtime();
        synchronized (enrollmentLock) {
            progress = enrollmentSamples.size();
            if (frame.faceCount != 1) {
                guidance = frame.faceCount == 0
                        ? "Enrollment: place only your face in front of the camera"
                        : "Enrollment paused: remove every other face";
            } else if (frame.signatures.size() != 1) {
                guidance = "Enrollment: move closer and face the camera directly";
            } else if (!enrollmentPoseAccepted(progress, frame.primaryYaw)) {
                guidance = enrollmentGuidance(progress);
            } else if (now < nextEnrollmentSampleAt) {
                guidance = enrollmentGuidance(progress);
            } else {
                enrollmentSamples.add(frame.signatures.get(0).clone());
                progress = enrollmentSamples.size();
                nextEnrollmentSampleAt = now + AppConfig.ENROLLMENT_SAMPLE_INTERVAL_MS;
                guidance = enrollmentGuidance(progress);
            }

            if (progress >= AppConfig.ENROLLMENT_SAMPLE_COUNT) {
                try {
                    profileStore.save(enrollmentSamples);
                    ownerMatcher.setTemplates(enrollmentSamples);
                    enrolling = false;
                    authorizationGate.update(SystemClock.elapsedRealtime(), 1, true);
                    riskEngine.reset();
                    latest = snapshot(true, false, 1,
                            "Owner face enrolled and authorized",
                            false, true, progress, 1f);
                    notificationManager.notify(NOTIFICATION_ID,
                            buildNotification("Owner enrolled — protection is active"));
                    return;
                } catch (Exception error) {
                    enrolling = false;
                    enrollmentSamples.clear();
                    latest = snapshot(true, false, frame.faceCount,
                            "Could not securely save the owner profile",
                            false, false, 0, -1f);
                    return;
                }
            }
        }

        latest = snapshot(true, false, frame.faceCount,
                guidance + " (" + progress + "/" + AppConfig.ENROLLMENT_SAMPLE_COUNT + ")",
                false, false, progress, -1f);
    }

    private boolean enrollmentPoseAccepted(int progress, float yaw) {
        if (progress < 4) {
            return Math.abs(yaw) <= 14f;
        }
        if (progress < 8) {
            return Math.abs(yaw) >= 8f && Math.abs(yaw) <= 30f;
        }
        return Math.abs(yaw) <= 18f;
    }

    private String enrollmentGuidance(int progress) {
        if (progress < 4) {
            return "Enrollment: look directly at the camera";
        }
        if (progress < 8) {
            return "Enrollment: slowly turn your head slightly to one side";
        }
        if (progress < AppConfig.ENROLLMENT_SAMPLE_COUNT) {
            return "Enrollment: look directly at the camera again";
        }
        return "Finishing secure enrollment…";
    }

    @Override
    public void onError(String message) {
        latest = snapshot(true, false, -1, message,
                false, false, enrollmentSamples.size(), -1f);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void pauseProtection() {
        if (enrolling) {
            synchronized (enrollmentLock) {
                enrolling = false;
                enrollmentSamples.clear();
            }
        }
        riskEngine.pause(SystemClock.elapsedRealtime(), AppConfig.EMERGENCY_PAUSE_MS);
        overlay.hide();
        lastShieldState = false;
        latest = snapshot(started, false, latest.faceCount,
                "Protection paused for 10 seconds", true,
                false, latest.enrollmentProgress, latest.similarity);
        if (started) {
            notificationManager.notify(NOTIFICATION_ID,
                    buildNotification("Protection paused for 10 seconds"));
        }
    }

    private String statusFor(RiskEngine.Decision decision, int faces,
                             boolean authorized, boolean directAuthorization,
                             float similarity, float requiredSimilarity) {
        if (decision.paused || faces == 0 || faces > 1) {
            return decision.reason;
        }
        if (directAuthorization) {
            return String.format(Locale.US, "Owner authorized • %.0f%% match",
                    Math.max(0f, similarity) * 100f);
        }
        if (authorized) {
            return String.format(Locale.US, "Owner authorized • stabilizing (%.0f%% now)",
                    Math.max(0f, similarity) * 100f);
        }
        if (similarity >= 0f) {
            return String.format(Locale.US, "Face not authorized • %.0f%% match (needs %.0f%%)",
                    similarity * 100f, requiredSimilarity * 100f);
        }
        return decision.reason;
    }

    private String notificationText(int faces, RiskEngine.Decision decision,
                                    boolean authorized) {
        if (decision.paused) {
            return decision.reason;
        }
        if (decision.protectedScreen) {
            return "Shield active — " + decision.reason;
        }
        if (faces == 1 && authorized) {
            return "Protected — owner authorized";
        }
        return "Protected — checking " + faces + " face(s)";
    }

    private Snapshot snapshot(boolean running, boolean shield, int faces, String status,
                              boolean paused, boolean authorized, int progress, float similarity) {
        return new Snapshot(running, shield, faces, status, paused,
                ownerMatcher != null && ownerMatcher.hasProfile(), authorized,
                enrolling, progress, similarity);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String content) {
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(
                this, 1, new Intent(this, ProtectionService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 2, new Intent(this, ProtectionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("PrivacyGuard AI is active")
                .setContentText(content)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Pause 10s", pause).build())
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void vibrateOnce() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    120L, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Override
    public void onDestroy() {
        started = false;
        enrolling = false;
        synchronized (enrollmentLock) {
            enrollmentSamples.clear();
        }
        if (faceMonitor != null) {
            faceMonitor.stop();
            faceMonitor = null;
        }
        if (overlay != null) {
            overlay.hide();
        }
        riskEngine.reset();
        authorizationGate.reset();
        latest = new Snapshot(false, false, -1, "Protection is stopped", false,
                profileStore != null && profileStore.hasProfile(), false,
                false, 0, -1f);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
