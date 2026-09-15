package com.ankitkupanda.privacyguard;

import android.Manifest;
import android.app.Activity;
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

/** Permission setup, controls, and live status for PrivacyGuard AI. */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 42;

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
    private TextView cameraPermission;
    private TextView overlayPermission;
    private Button startButton;
    private Button stopButton;
    private boolean waitingForOverlaySettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 11, 22));
        getWindow().setNavigationBarColor(Color.rgb(7, 11, 22));
        setContentView(buildInterface());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.post(refreshTask);
        if (waitingForOverlaySettings) {
            waitingForOverlaySettings = false;
            if (Settings.canDrawOverlays(this)) {
                startServiceNow();
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
                "The front camera watches for an extra viewer and covers your screen when shoulder surfing is detected.",
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
        statusDetail = text("Tap Start protection after granting both permissions.",
                14, Color.rgb(148, 163, 184));
        LinearLayout.LayoutParams detailParams = wrap();
        detailParams.topMargin = dp(8);
        statusCard.addView(statusDetail, detailParams);
        faceCount = text("Faces: —", 14, Color.rgb(96, 165, 250));
        LinearLayout.LayoutParams countParams = wrap();
        countParams.topMargin = dp(12);
        statusCard.addView(faceCount, countParams);
        content.addView(statusCard, cardSpacing());

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
                "Extra viewer: 0.8s  •  No face: 1.5s  •  Safe release: 1.5s",
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
        content.addView(step("1", "Start protection while this screen is open."));
        content.addView(step("2", "Open any other app; the foreground service keeps checking locally."));
        content.addView(step("3", "A second face triggers the shield. Pause or stop it from the shield or notification."));

        TextView privacy = text(
                "Privacy promise: the APK has no internet permission. Frames are processed in memory and are never saved.",
                13, Color.rgb(125, 211, 252));
        privacy.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams privacyParams = wrap();
        privacyParams.topMargin = dp(26);
        content.addView(privacy, privacyParams);

        return scroll;
    }

    private void beginStartFlow() {
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
        requestOverlayOrStart();
    }

    private void requestOverlayOrStart() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlaySettings = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        startServiceNow();
    }

    private void startServiceNow() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent service = new Intent(this, ProtectionService.class);
        startForegroundService(service);
        Toast.makeText(this, "Privacy protection started", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            requestOverlayOrStart();
        } else {
            Toast.makeText(this,
                    "PrivacyGuard AI cannot detect viewers without camera permission.",
                    Toast.LENGTH_LONG).show();
        }
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
        if (!snapshot.running) {
            statusTitle.setText("Protection is stopped");
            statusTitle.setTextColor(Color.WHITE);
            statusDetail.setText(snapshot.status);
            faceCount.setText("Faces: —");
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
        startButton.setEnabled(!snapshot.running);
        startButton.setAlpha(snapshot.running ? 0.55f : 1f);
        stopButton.setEnabled(snapshot.running);
        stopButton.setAlpha(snapshot.running ? 1f : 0.55f);
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
