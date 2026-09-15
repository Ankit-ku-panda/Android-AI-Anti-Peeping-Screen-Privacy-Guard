package com.ankitkupanda.privacyguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Permission setup, owner enrollment, controls, and live status for PrivacyGuard AI. */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 42;
    private static final String STATE_WAITING_FOR_OVERLAY = "waiting_for_overlay";
    private static final String STATE_PENDING_ACTION = "pending_action";

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshHandler.postDelayed(this, 500L);
        }
    };

    private TextView statusTitle;
    private TextView statusDetail;
    private TextView faceCount;
    private TextView ownerStatus;
    private TextView cameraPermission;
    private TextView overlayPermission;
    private Button startButton;
    private Button stopButton;
    private Button enrollButton;
    private Button deleteProfileButton;
    private FaceProfileStore profileStore;
    private boolean profileExists;
    private boolean waitingForOverlaySettings;
    private String pendingServiceAction = ProtectionService.ACTION_START;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 11, 22));
        getWindow().setNavigationBarColor(Color.rgb(7, 11, 22));
        profileStore = new FaceProfileStore(this);
        profileExists = profileStore.hasProfile();
        if (savedInstanceState != null) {
            waitingForOverlaySettings = savedInstanceState.getBoolean(
                    STATE_WAITING_FOR_OVERLAY, false);
            pendingServiceAction = savedInstanceState.getString(
                    STATE_PENDING_ACTION, ProtectionService.ACTION_START);
        }
        setContentView(buildInterface());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_WAITING_FOR_OVERLAY, waitingForOverlaySettings);
        outState.putString(STATE_PENDING_ACTION, pendingServiceAction);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.post(refreshTask);
        if (waitingForOverlaySettings) {
            waitingForOverlaySettings = false;
            if (Settings.canDrawOverlays(this)) {
                startServiceNow(pendingServiceAction);
            } else {
                Toast.makeText(this,
                        "Display-over-other-apps permission is needed for the privacy shield.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private View buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 11, 22));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView badge = text("ON-DEVICE PRIVACY", 13, Color.rgb(34, 211, 238));
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(badge);

        TextView title = text("PrivacyGuard AI", 34, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(8);
        content.addView(title, titleParams);

        TextView subtitle = text(
                "The front camera authorizes your enrolled face and covers the screen for an unknown or additional viewer.",
                16, Color.rgb(180, 194, 218));
        subtitle.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams subtitleParams = wrap();
        subtitleParams.topMargin = dp(10);
        subtitleParams.bottomMargin = dp(24);
        content.addView(subtitle, subtitleParams);

        LinearLayout statusCard = card();
        statusTitle = text("Protection is stopped", 20, Color.WHITE);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(statusTitle);
        statusDetail = text("Enroll the owner's face, then start protection.",
                14, Color.rgb(148, 163, 184));
        LinearLayout.LayoutParams detailParams = wrap();
        detailParams.topMargin = dp(8);
        statusCard.addView(statusDetail, detailParams);
        faceCount = text("Faces: —", 14, Color.rgb(96, 165, 250));
        LinearLayout.LayoutParams countParams = wrap();
        countParams.topMargin = dp(12);
        statusCard.addView(faceCount, countParams);
        content.addView(statusCard, cardSpacing());

        LinearLayout ownerCard = card();
        TextView ownerTitle = text("Owner authorization", 18, Color.WHITE);
        ownerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ownerCard.addView(ownerTitle);
        ownerStatus = text(profileExists
                        ? "Owner profile enrolled"
                        : "No owner profile enrolled",
                15, profileExists ? Color.rgb(52, 211, 153) : Color.rgb(251, 191, 36));
        ownerStatus.setPadding(0, dp(10), 0, 0);
        ownerCard.addView(ownerStatus);

        enrollButton = button(profileExists ? "Re-enroll owner face" : "Enroll owner face",
                R.drawable.button_primary);
        enrollButton.setOnClickListener(v ->
                beginPermissionFlow(ProtectionService.ACTION_ENROLL));
        LinearLayout.LayoutParams enrollParams = fullWidth();
        enrollParams.topMargin = dp(14);
        ownerCard.addView(enrollButton, enrollParams);

        deleteProfileButton = button("Delete owner profile", R.drawable.button_secondary);
        deleteProfileButton.setOnClickListener(v -> confirmDeleteProfile());
        LinearLayout.LayoutParams deleteParams = fullWidth();
        deleteParams.topMargin = dp(10);
        ownerCard.addView(deleteProfileButton, deleteParams);

        TextView enrollmentHelp = text(
                "Enrollment takes 12 samples: look forward, turn slightly, then look forward again. Use even lighting and remove glasses or a mask.",
                13, Color.rgb(148, 163, 184));
        enrollmentHelp.setLineSpacing(0f, 1.12f);
        enrollmentHelp.setPadding(0, dp(12), 0, 0);
        ownerCard.addView(enrollmentHelp);
        content.addView(ownerCard, cardSpacing());

        startButton = button("Start protection", R.drawable.button_primary);
        startButton.setOnClickListener(v -> beginStartFlow());
        content.addView(startButton, fullWidth());

        stopButton = button("Stop protection", R.drawable.button_secondary);
        stopButton.setOnClickListener(v -> stopService(
                new Intent(this, ProtectionService.class)));
        LinearLayout.LayoutParams stopParams = fullWidth();
        stopParams.topMargin = dp(12);
        content.addView(stopButton, stopParams);

        LinearLayout settingsCard = card();
        TextView settingsTitle = text("Protection settings", 18, Color.WHITE);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        settingsCard.addView(settingsTitle);

        Switch strictSwitch = new Switch(this);
        strictSwitch.setText("Shield when no face is visible");
        strictSwitch.setTextColor(Color.rgb(226, 232, 240));
        strictSwitch.setTextSize(15);
        strictSwitch.setChecked(getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
                .getBoolean(AppConfig.PREF_STRICT_NO_FACE, true));
        strictSwitch.setPadding(0, dp(10), 0, 0);
        strictSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
                        .edit().putBoolean(AppConfig.PREF_STRICT_NO_FACE, isChecked).apply());
        settingsCard.addView(strictSwitch, fullWidth());

        TextView timing = text(
                "Unknown/additional face: 0.8s  •  No face: 1.5s  •  Safe release: 1.5s",
                13, Color.rgb(148, 163, 184));
        timing.setPadding(0, dp(8), 0, 0);
        settingsCard.addView(timing);
        content.addView(settingsCard, cardSpacing());

        LinearLayout permissionCard = card();
        TextView permissionTitle = text("Required permissions", 18, Color.WHITE);
        permissionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        permissionCard.addView(permissionTitle);
        cameraPermission = text("Camera: checking…", 15, Color.rgb(203, 213, 225));
        cameraPermission.setPadding(0, dp(12), 0, 0);
        permissionCard.addView(cameraPermission);
        overlayPermission = text("Display over other apps: checking…",
                15, Color.rgb(203, 213, 225));
        overlayPermission.setPadding(0, dp(8), 0, 0);
        permissionCard.addView(overlayPermission);
        content.addView(permissionCard, cardSpacing());

        TextView howTitle = text("How it works", 20, Color.WHITE);
        howTitle.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(howTitle);
        content.addView(step("1", "Enroll only the phone owner's face in private."));
        content.addView(step("2", "Start protection, then open any other app."));
        content.addView(step("3", "One recognized owner stays clear; an unknown or additional face activates the shield."));

        TextView privacy = text(
                "Privacy: no photo is saved. Encrypted numeric face signatures stay on this phone, and the APK has no internet permission.",
                13, Color.rgb(125, 211, 252));
        privacy.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams privacyParams = wrap();
        privacyParams.topMargin = dp(26);
        content.addView(privacy, privacyParams);

        return scroll;
    }

    private void beginStartFlow() {
        if (!profileExists) {
            Toast.makeText(this,
                    "First enroll the phone owner's face.", Toast.LENGTH_LONG).show();
            beginPermissionFlow(ProtectionService.ACTION_ENROLL);
            return;
        }
        beginPermissionFlow(ProtectionService.ACTION_START);
    }

    private void beginPermissionFlow(String action) {
        pendingServiceAction = action;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        requestOverlayOrRun();
    }

    private void requestOverlayOrRun() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlaySettings = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        startServiceNow(pendingServiceAction);
    }

    private void startServiceNow(String action) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent service = new Intent(this, ProtectionService.class).setAction(action);
        startForegroundService(service);
        String message = ProtectionService.ACTION_ENROLL.equals(action)
                ? "Owner enrollment started — follow the instructions"
                : "Privacy protection started";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            requestOverlayOrRun();
        } else {
            Toast.makeText(this,
                    "PrivacyGuard AI cannot authorize the owner without camera permission.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteProfile() {
        if (!profileExists) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete owner profile?")
                .setMessage("Protection will stop. You must enroll again before it can restart.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteProfile())
                .show();
    }

    private void deleteProfile() {
        stopService(new Intent(this, ProtectionService.class));
        profileStore.clear();
        profileExists = false;
        Toast.makeText(this, "Owner profile deleted", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean cameraReady = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean overlayReady = Settings.canDrawOverlays(this);
        cameraPermission.setText(cameraReady ? "Camera: allowed" : "Camera: not allowed");
        cameraPermission.setTextColor(cameraReady
                ? Color.rgb(52, 211, 153) : Color.rgb(251, 113, 133));
        overlayPermission.setText(overlayReady
                ? "Display over other apps: allowed"
                : "Display over other apps: not allowed");
        overlayPermission.setTextColor(overlayReady
                ? Color.rgb(52, 211, 153) : Color.rgb(251, 113, 133));

        ProtectionService.Snapshot snapshot = ProtectionService.snapshot();
        if (snapshot.ownerEnrolled && !profileExists) {
            profileExists = profileStore.hasProfile();
        }

        if (!snapshot.running) {
            statusTitle.setText("Protection is stopped");
            statusTitle.setTextColor(Color.WHITE);
            statusDetail.setText(profileExists
                    ? "Tap Start protection when you are ready."
                    : "Enroll the owner's face, then start protection.");
            faceCount.setText("Faces: —");
        } else if (snapshot.enrolling) {
            statusTitle.setText("Enrolling owner face");
            statusTitle.setTextColor(Color.rgb(34, 211, 238));
            statusDetail.setText(snapshot.status);
            faceCount.setText("Faces: " + displayFaceCount(snapshot.faceCount));
        } else if (snapshot.shieldActive) {
            statusTitle.setText("Privacy shield active");
            statusTitle.setTextColor(Color.rgb(251, 113, 133));
            statusDetail.setText(snapshot.status);
            faceCount.setText("Faces: " + displayFaceCount(snapshot.faceCount));
        } else if (snapshot.paused) {
            statusTitle.setText("Protection paused");
            statusTitle.setTextColor(Color.rgb(251, 191, 36));
            statusDetail.setText(snapshot.status);
            faceCount.setText("Faces: " + displayFaceCount(snapshot.faceCount));
        } else {
            statusTitle.setText("Protection is running");
            statusTitle.setTextColor(Color.rgb(52, 211, 153));
            statusDetail.setText(snapshot.status);
            faceCount.setText("Faces: " + displayFaceCount(snapshot.faceCount));
        }

        if (snapshot.enrolling) {
            ownerStatus.setText("Enrollment progress: " + snapshot.enrollmentProgress
                    + "/" + AppConfig.ENROLLMENT_SAMPLE_COUNT);
            ownerStatus.setTextColor(Color.rgb(34, 211, 238));
        } else if (snapshot.running && snapshot.ownerAuthorized) {
            String match = snapshot.similarity < 0f ? ""
                    : String.format(Locale.US, " • %.0f%% match", snapshot.similarity * 100f);
            ownerStatus.setText("Owner authorized" + match);
            ownerStatus.setTextColor(Color.rgb(52, 211, 153));
        } else if (snapshot.running && snapshot.faceCount == 1 && snapshot.similarity >= 0f) {
            ownerStatus.setText(String.format(Locale.US,
                    "Face not authorized • %.0f%% match", snapshot.similarity * 100f));
            ownerStatus.setTextColor(Color.rgb(251, 113, 133));
        } else if (profileExists) {
            ownerStatus.setText("Owner profile enrolled");
            ownerStatus.setTextColor(Color.rgb(52, 211, 153));
        } else {
            ownerStatus.setText("No owner profile enrolled");
            ownerStatus.setTextColor(Color.rgb(251, 191, 36));
        }

        startButton.setEnabled(profileExists && !snapshot.running);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
        stopButton.setEnabled(snapshot.running);
        stopButton.setAlpha(snapshot.running ? 1f : 0.55f);
        enrollButton.setEnabled(!snapshot.enrolling);
        enrollButton.setAlpha(snapshot.enrolling ? 0.55f : 1f);
        enrollButton.setText(profileExists ? "Re-enroll owner face" : "Enroll owner face");
        deleteProfileButton.setEnabled(profileExists && !snapshot.enrolling);
        deleteProfileButton.setAlpha(deleteProfileButton.isEnabled() ? 1f : 0.55f);
    }

    private String displayFaceCount(int count) {
        return count < 0 ? "detecting…" : Integer.toString(count);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundResource(R.drawable.panel_background);
        return card;
    }

    private TextView step(String number, String description) {
        TextView view = text(number + ".  " + description, 15, Color.rgb(203, 213, 225));
        view.setLineSpacing(0f, 1.12f);
        view.setPadding(0, dp(12), 0, 0);
        return view;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }

    private Button button(String label, int background) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackgroundResource(background);
        return button;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return wrap();
    }

    private LinearLayout.LayoutParams cardSpacing() {
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = dp(18);
        params.bottomMargin = dp(18);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
