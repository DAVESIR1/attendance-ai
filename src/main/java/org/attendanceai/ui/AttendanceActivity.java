/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.attendanceai.camera.Camera2Backend;
import org.attendanceai.camera.CameraBackend;
import org.attendanceai.camera.CameraFrame;
import org.attendanceai.camera.SimulatedCameraBackend;
import org.attendanceai.pipeline.FacePipeline;
import org.attendanceai.presentation.lockscreen.LockScreenActivity;
import org.attendanceai.presentation.lockscreen.SecurityGate;
import org.attendanceai.data.local.db.AttendanceDatabase;
import org.attendanceai.data.local.db.VaultSession;
import org.attendanceai.store.AttendanceStore;

/**
 * Single-activity app shell (classic programmatic views — no XML layouts).
 *
 * Layout: a vertically stacked ColumnLayout with
 *   title / integrity-banner / live result / buttons / scrollable log.
 *
 * The camera delivers RGBA frames; the engine executor hands each frame to
 * the pipeline (dropping frames while one is in flight). All pipeline
 * results are marshalled to the UI thread.
 */
public final class AttendanceActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CAMERA = 1001;

    private ColumnLayout root;
    private TextView banner;
    private TextView resultView;
    private TextView logView;
    private Button startButton;
    private Button enrollButton;

    private final ExecutorService engine = Executors.newSingleThreadExecutor();
    private final AtomicBoolean enqueued = new AtomicBoolean(false);

    private CameraBackend camera;
    private FacePipeline pipeline;
    private AttendanceStore store;
    private volatile CameraFrame lastFrame;

    private boolean running;

    /**
     * Phase-1 security gate: the lock screen (seed-phrase setup on first
     * run, PIN/biometric unlock afterwards) runs in front of the app.
     * On RESULT_OK the session is marked unlocked and the activity
     * initialises; any other outcome closes the app (fail closed).
     */
    private final ActivityResultLauncher<Intent> lockLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    initAfterUnlock();
                } else {
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SecurityGate.lockRequired()) {
            lockLauncher.launch(LockScreenActivity.createIntent(this));
            return;
        }
        initAfterUnlock();
    }

    /** Original onCreate body — only executed once the vault is satisfied. */
    private void initAfterUnlock() {
        AttendanceDatabase database = VaultSession.database();
        // Continue only when the lock flow has opened the encrypted Room
        // session. A security-gate flag without a database is fail-closed.
        if (SecurityGate.lockRequired() || database == null) {
            finish();
            return;
        }
        buildContent();
        store = new AttendanceStore(getFilesDir(), database);
        pipeline = new FacePipeline(this, store.loadSettings(), store, result -> {
            runOnUiThread(() -> presentResult(result));
        });
        banner.setText(bannerStatus());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    PERMISSION_REQUEST_CAMERA);
        }
    }

    private void buildContent() {
        root = new ColumnLayout(this);
        root.setPadding(20, 24, 20, 20);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFE8E5FF, 0xFFF4F7FC, 0xFFE2F7F2}));

        TextView title = new TextView(this);
        title.setText("Attendance AI");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF1D2942);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Private • Offline • Encrypted");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xFF66738D);
        root.addView(subtitle);

        banner = cardText(12f, 0xFF66738D);
        root.addView(banner);

        resultView = cardText(16f, 0xFF1D2942);
        root.addView(resultView);

        startButton = actionButton("Start camera", 0xFF6D5DF5);
        startButton.setOnClickListener(v -> toggleCamera());
        root.addView(startButton);

        enrollButton = actionButton("Enrol current face", 0xFF28B8A6);
        enrollButton.setOnClickListener(v -> enrol());
        root.addView(enrollButton);

        Button clear = actionButton("Clear roster", 0xFF9A79D9);
        clear.setOnClickListener(v -> clearRoster());
        root.addView(clear);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackground(roundBackground(0xDFFFFFFF, 22));
        scroller.setPadding(16, 14, 16, 14);
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextColor(0xFF66738D);
        scroller.addView(logView);
        root.addView(scroller);

        setContentView(root);
    }

    private TextView cardText(float size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(16, 14, 16, 14);
        view.setBackground(roundBackground(0xDFFFFFFF, 18));
        return view;
    }

    private Button actionButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15f);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(54);
        button.setPadding(18, 8, 18, 8);
        button.setBackground(roundBackground(color, 18));
        return button;
    }

    private GradientDrawable roundBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return drawable;
    }

    private String bannerStatus() {
        StringBuilder sb = new StringBuilder("models: " + pipeline.integrityNote());
        if (pipeline.inSignatureMode()) {
            sb.append(" | SIGNATURE MODE (experimental)");
        }
        sb.append(" | roster: ").append(store.loadRoster().size())
          .append(" | records: ").append(store.loadRecords().size());
        return sb.toString();
    }

    private void log(String line) {
        String current = logView.getText().toString();
        String updated = (current.length() > 0 ? current + "\n" : "") + line;
        logView.setText(updated);
    }

    private void presentResult(FacePipeline.PipelineResult result) {
        if (result.error) {
            resultView.setTextColor(0xFFFF6B6B);
            resultView.setText("! " + result.status);
            log("error: " + result.status);
            return;
        }
        resultView.setTextColor(Color.WHITE);
        if (result.matchedPersonId.length() > 0) {
            resultView.setText("✓ " + result.matchedName + "  (sim "
                    + String.format("%.2f", result.score) + ")  " + result.status);
            log("match: " + result.matchedName + " @" + String.format("%.2f", result.score));
        } else {
            resultView.setText(result.status);
            log(result.status);
        }
    }

    private void toggleCamera() {
        if (!running) {
            startCamera();
        } else {
            stopCamera();
        }
    }

    private void startCamera() {
        if (running) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            log("camera permission not granted");
            return;
        }
        CameraBackend backend = new Camera2Backend(this);
        boolean ok;
        try {
            ok = backend.start(this::submitFrame, 640, 480);
        } catch (RuntimeException e) {
            log("camera unavailable — using safe fallback");
            ok = false;
        }
        if (!ok) {
            log("no camera — starting simulated camera");
            backend = new SimulatedCameraBackend();
            if (!backend.start(this::submitFrame, 640, 480)) {
                log("camera fallback unavailable");
                return;
            }
        }
        camera = backend;
        running = true;
        startButton.setText("Stop camera");
        log("camera started");
    }

    private void submitFrame(CameraFrame frame) {
        lastFrame = frame;
        if (!enqueued.compareAndSet(false, true)) {
            return;
        }
        try {
            engine.execute(() -> {
                try {
                    if (pipeline != null) {
                        pipeline.process(frame);
                    }
                } finally {
                    enqueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            enqueued.set(false);
            runOnUiThread(() -> log("camera stopped"));
        }
    }

    private void stopCamera() {
        if (camera != null) {
            camera.stop();
            camera = null;
        }
        running = false;
        startButton.setText("Start camera");
        log("camera stopped");
    }

    private void enrol() {
        if (lastFrame == null) {
            log("no frame captured yet — cannot enrol");
            return;
        }
        int next = store.loadRoster().size() + 1;
        enrollNamed("Person " + next);
    }

    private void enrollNamed(String name) {
        pipeline.enrollBestFace(lastFrame, name);
    }

    private void clearRoster() {
        try {
            store.saveRoster(new java.util.LinkedHashMap<String, AttendanceStore.Person>());
            pipeline.reloadTemplates();
            resultView.setText("roster cleared");
            log("roster cleared");
        } catch (java.io.IOException e) {
            log("clear failed: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("camera permission granted");
                startCamera();
            } else {
                log("camera permission denied — using simulated camera");
                startCamera();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The activity is resumed once while the lock screen is displayed,
        // before initAfterUnlock() has created the content views.
        if (banner != null && pipeline != null && store != null) {
            banner.setText(bannerStatus());
        }
    }

    @Override
    protected void onPause() {
        // The lock screen is launched before the main UI is initialised.
        // Avoid touching the camera/UI during that first pause callback.
        if (camera != null || running) {
            stopCamera();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        if (pipeline != null) {
            pipeline.close();
        }
        engine.shutdownNow();
        super.onDestroy();
    }

    // ---- minimal vertical container -----------------------------------------

    /** Stacks children top-to-bottom; the last child fills remaining height. */
    private static final class ColumnLayout extends ViewGroup {
        ColumnLayout(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            int left = getPaddingLeft();
            int top = getPaddingTop();
            int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            int y = top;
            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                int specHeight = View.MeasureSpec.UNSPECIFIED;
                if (i == count - 1) {
                    specHeight = View.MeasureSpec.makeMeasureSpec(
                            Math.max(0, contentHeight - (y - top)),
                            View.MeasureSpec.EXACTLY);
                }
                child.measure(View.MeasureSpec.makeMeasureSpec(contentWidth,
                                View.MeasureSpec.EXACTLY), specHeight);
                int childHeight = child.getMeasuredHeight();
                int bottom = Math.min(height - getPaddingBottom(), y + childHeight);
                child.layout(left, y, left + contentWidth, bottom);
                y += childHeight;
            }
        }
    }
}