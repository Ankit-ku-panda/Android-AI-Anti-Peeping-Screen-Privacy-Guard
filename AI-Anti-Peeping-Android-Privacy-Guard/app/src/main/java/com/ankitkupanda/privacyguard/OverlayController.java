package com.ankitkupanda.privacyguard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Owns the full-screen privacy shield that is displayed over other apps. */
public final class OverlayController {
    public interface Actions {
        void onPauseRequested();
        void onStopRequested();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Actions actions;

    private View overlayView;
    private TextView reasonView;

    public OverlayController(Context context, Actions actions) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.actions = actions;
    }

    public void show(String reason) {
        mainHandler.post(() -> showOnMain(reason));
    }

    public void hide() {
        mainHandler.post(this::hideOnMain);
    }

    private void showOnMain(String reason) {
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        if (overlayView != null) {
            reasonView.setText(reason);
            return;
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(48), dp(28), dp(48));
        root.setBackgroundResource(R.drawable.shield_background);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_shield);
        root.addView(icon, new LinearLayout.LayoutParams(dp(104), dp(104)));

        TextView eyebrow = label("PRIVACY SHIELD ACTIVE", 14, Color.rgb(34, 211, 238));
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams eyebrowParams = fullWidth();
        eyebrowParams.topMargin = dp(28);
        root.addView(eyebrow, eyebrowParams);

        TextView title = label("Your screen is protected", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = fullWidth();
        titleParams.topMargin = dp(10);
        root.addView(title, titleParams);

        reasonView = label(reason, 18, Color.rgb(203, 213, 225));
        LinearLayout.LayoutParams reasonParams = fullWidth();
        reasonParams.topMargin = dp(14);
        root.addView(reasonView, reasonParams);

        TextView privacy = label("Camera analysis stays on this phone. No photo or video is saved.",
                14, Color.rgb(148, 163, 184));
        LinearLayout.LayoutParams privacyParams = fullWidth();
        privacyParams.topMargin = dp(12);
        privacyParams.bottomMargin = dp(32);
        root.addView(privacy, privacyParams);

        Button pause = button("Pause for 10 seconds", R.drawable.button_primary);
        pause.setOnClickListener(v -> actions.onPauseRequested());
        root.addView(pause, fullWidth());

        Button stop = button("Stop protection", R.drawable.button_secondary);
        LinearLayout.LayoutParams stopParams = fullWidth();
        stopParams.topMargin = dp(12);
        root.addView(stop, stopParams);
        stop.setOnClickListener(v -> actions.onStopRequested());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(root, params);
            overlayView = root;
        } catch (RuntimeException ignored) {
            overlayView = null;
            reasonView = null;
        }
    }

    private void hideOnMain() {
        if (overlayView == null) {
            return;
        }
        try {
            windowManager.removeView(overlayView);
        } catch (RuntimeException ignored) {
            // The window may already have been removed by Android.
        }
        overlayView = null;
        reasonView = null;
    }

    private TextView label(String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private Button button(String text, int background) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackgroundResource(background);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
