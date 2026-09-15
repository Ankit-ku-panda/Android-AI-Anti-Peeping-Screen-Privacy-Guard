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

/** Foreground camera service that continues protection while another app is open. */
public final class ProtectionService extends Service implements FaceMonitor.Listener {
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

        Snapshot(boolean running, boolean shieldActive, int faceCount,
                 String status, boolean paused) {
            this.running = running;
            this.shieldActive = shieldActive;
            this.faceCount = faceCount;
            this.status = status;
            this.paused = paused;
        }
    }

    private static volatile Snapshot latest =
            new Snapshot(false, false, -1, "Protection is stopped", false);

    public static Snapshot snapshot() {
        return latest;
    }

    private final RiskEngine riskEngine = new RiskEngine(
            AppConfig.PEEPING_TRIGGER_MS,
            AppConfig.NO_FACE_TRIGGER_MS,
            AppConfig.SAFE_RELEASE_MS);

    private OverlayController overlay;
    private FaceMonitor faceMonitor;
    private NotificationManager notificationManager;
    private long lastNotificationUpdate;
    private boolean lastShieldState;
    private boolean started;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
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
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE.equals(action)) {
            pauseProtection();
            return START_NOT_STICKY;
        }

        if (started) {
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification("Starting on-device face detection…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!Settings.canDrawOverlays(this)) {
            latest = new Snapshot(false, false, -1,
                    "Display-over-other-apps permission is missing", false);
            stopSelf();
            return START_NOT_STICKY;
        }

        started = true;
        latest = new Snapshot(true, false, -1, "Opening front camera…", false);
        faceMonitor = new FaceMonitor(this, this);
        faceMonitor.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onFaceCount(int faceCount) {
        SharedPreferences preferences = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        boolean strictNoFace = preferences.getBoolean(AppConfig.PREF_STRICT_NO_FACE, true);
        RiskEngine.Decision decision = riskEngine.update(
                SystemClock.elapsedRealtime(), faceCount, strictNoFace);

        if (decision.protectedScreen) {
            overlay.show(decision.reason);
        } else {
            overlay.hide();
        }

        if (decision.protectedScreen && !lastShieldState) {
            vibrateOnce();
        }
        lastShieldState = decision.protectedScreen;
        latest = new Snapshot(true, decision.protectedScreen, faceCount,
                decision.reason, decision.paused);

        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationUpdate >= 1_000L) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(notificationText(faceCount,
                    decision)));
            lastNotificationUpdate = now;
        }
    }

    @Override
    public void onError(String message) {
        latest = new Snapshot(true, false, -1, message, false);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void pauseProtection() {
        riskEngine.pause(SystemClock.elapsedRealtime(), AppConfig.EMERGENCY_PAUSE_MS);
        overlay.hide();
        lastShieldState = false;
        latest = new Snapshot(started, false, latest.faceCount,
                "Protection paused for 10 seconds", true);
        if (started) {
            notificationManager.notify(NOTIFICATION_ID,
                    buildNotification("Protection paused for 10 seconds"));
        }
    }

    private String notificationText(int faceCount, RiskEngine.Decision decision) {
        if (decision.paused) {
            return decision.reason;
        }
        if (decision.protectedScreen) {
            return "Shield active — " + decision.reason;
        }
        if (faceCount == 1) {
            return "Protected — one viewer detected";
        }
        return "Protected — " + faceCount + " faces detected";
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

        Intent pauseIntent = new Intent(this, ProtectionService.class).setAction(ACTION_PAUSE);
        PendingIntent pause = PendingIntent.getService(
                this, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ProtectionService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("PrivacyGuard AI is active")
                .setContentText(content)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        null, "Pause 10s", pause).build())
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stop).build())
                .build();
    }

    private void vibrateOnce() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(120L,
                    VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Override
    public void onDestroy() {
        started = false;
        if (faceMonitor != null) {
            faceMonitor.stop();
            faceMonitor = null;
        }
        if (overlay != null) {
            overlay.hide();
        }
        riskEngine.reset();
        latest = new Snapshot(false, false, -1, "Protection is stopped", false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
