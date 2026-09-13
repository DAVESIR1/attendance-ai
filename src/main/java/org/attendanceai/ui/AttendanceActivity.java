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
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

import org.attendanceai.pipeline.FacePipeline;
import org.attendanceai.presentation.lockscreen.LockScreenActivity;
import org.attendanceai.presentation.lockscreen.SecurityGate;
import org.attendanceai.BuildConfig;
import org.attendanceai.data.local.db.AttendanceDatabase;
import org.attendanceai.data.local.db.LegacyJsonMigrator;
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
    private SurfaceView previewView;
    private Surface previewSurface;
    private String lastStatus = "";
    private long lastStatusLogMs;
    private String initializationPhase = "secure session";

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
        try {
            initializationPhase = "encrypted session";
            AttendanceDatabase database = VaultSession.database();
            // Continue only when the lock flow has opened the encrypted Room
            // session. A security-gate flag without a database is fail-closed,
            // but it must remain visible instead of silently finishing.
            if (SecurityGate.lockRequired() || database == null) {
                showInitializationError("The encrypted session is not available yet.");
                return;
            }

            initializationPhase = "encrypted attendance store";
            buildContent();
            runLegacyMigrationSafely(database);
            store = new AttendanceStore(getFilesDir(), database);
            initializationPhase = "face recognition pipeline";
            try {
                pipeline = new FacePipeline(this, store.loadSettings(), store, result -> {
                    runOnUiThread(() -> presentResult(result));
                });
                banner.setText(bannerStatus());
            } catch (Throwable pipelineFailure) {
                // Keep the secure attendance shell usable even when a native
                // ML library/model is unavailable on a particular phone.
                pipeline = null;
                String modelError = pipelineFailure.getClass().getSimpleName();
                banner.setText("Encrypted storage ready • face model unavailable (" + modelError + ")");
                resultView.setText("Camera features need the face model to start");
                log("face recognition pipeline unavailable: " + modelError);
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA},
                        PERMISSION_REQUEST_CAMERA);
            }
        } catch (Throwable failure) {
            // Database, migration, or store failures must be actionable on
            // screen rather than looking like an unlock failure.
            showInitializationError(
                    "The secure app session could not be started at: " + initializationPhase +
                            "\nDiagnostic: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * One-time import of the pre-SQLCipher JSON files (which stored raw face
     * templates unencrypted) into the encrypted Room database, followed by an
     * overwrite-and-delete of the source files. Only counts are reported —
     * never names, templates or timestamps. On failure the JSON files are kept
     * (no data lost), the condition is surfaced in the on-screen log, and the
     * encrypted database remains the authoritative store either way.
     */
    private void runLegacyMigrationSafely(AttendanceDatabase database) {
        try {
            LegacyJsonMigrator.Outcome outcome =
                    LegacyJsonMigrator.migrateBlocking(getFilesDir(), database);
            if (outcome.getFailed()) {
                log("legacy data migration failed (" + outcome.getFailure()
                        + ") — unencrypted files kept");
            } else if (outcome.getMigrated()) {
                log("legacy data migrated to encrypted storage (people: "
                        + outcome.getPeopleImported() + ", records: "
                        + outcome.getRecordsImported() + ")");
                if (!outcome.getFilesDeleted()) {
                    log("warning: legacy JSON files could not be removed after migration");
                }
            }
        } catch (Throwable migrationFailure) {
            // Never let a migration problem block the unlock flow.
            log("legacy data migration skipped ("
                    + migrationFailure.getClass().getSimpleName() + ")");
        }
    }

    private void showInitializationError(String message) {
        ColumnLayout errorRoot = new ColumnLayout(this);
        errorRoot.setPadding(24, 32, 24, 24);
        errorRoot.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFE8E5FF, 0xFFF4F7FC, 0xFFFFE9EF}));

        TextView title = new TextView(this);
        title.setText("Attendance AI • " + BuildConfig.VERSION_NAME);
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF1D2942);
        errorRoot.addView(title);

        TextView body = cardText(16f, 0xFF1D2942);
        body.setText(message + "\n\nYour recovery phrase and stored data were not deleted.");
        errorRoot.addView(body);

        Button retry = actionButton("Try again", 0xFF6D5DF5);
        retry.setOnClickListener(v -> recreate());
        errorRoot.addView(retry);

        Button close = actionButton("Close", 0xFF9A79D9);
        close.setOnClickListener(v -> finish());
        errorRoot.addView(close);
        setContentView(errorRoot);
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
        subtitle.setText("Private • Offline • Encrypted • " + BuildConfig.VERSION_NAME);
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xFF66738D);
        root.addView(subtitle);

        previewView = new SurfaceView(this);
        previewView.setBackground(roundBackground(0xFFCBD6E8, 22));
        previewView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        previewView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // SurfaceView owns this Surface; Camera2 only borrows it while running.
                previewSurface = holder.getSurface();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Camera2 scales the preview into the holder surface.
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                previewSurface = null;
            }
        });
        root.addView(previewView);

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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
            resultView.setTextColor(0xFFC23B5A);
            resultView.setText("! " + result.status);
            logStatus("error: " + result.status);
            return;
        }
        resultView.setTextColor(0xFF1D2942);
        if (result.matchedPersonId.length() > 0) {
            resultView.setText("✓ " + result.matchedName + "  (sim "
                    + String.format("%.2f", result.score) + ")  " + result.status);
            logStatus("match: " + result.matchedName + " @" + String.format("%.2f", result.score));
        } else {
            resultView.setText(result.status);
            logStatus(result.status);
        }
    }

    private void logStatus(String status) {
        long now = System.currentTimeMillis();
        if (!status.equals(lastStatus) || now - lastStatusLogMs >= 1500L) {
            lastStatus = status;
            lastStatusLogMs = now;
            log(status);
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
        if (pipeline == null) {
            resultView.setText("Face model is unavailable; camera cannot start yet");
            log("camera blocked because face model is unavailable");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            log("camera permission not granted");
            return;
        }
        if (previewSurface == null) {
            resultView.setText("Camera preview is still loading — please try again");
            log("camera preview is not ready");
            return;
        }
        CameraBackend backend = new Camera2Backend(this);
        boolean ok;
        try {
            ok = backend.start(this::submitFrame, 640, 480, previewSurface);
        } catch (RuntimeException e) {
            log("camera unavailable — using safe fallback");
            ok = false;
        }
        if (!ok) {
            resultView.setText("Camera could not be opened");
            log("camera unavailable — no simulated frames started");
            return;
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
        if (startButton != null) {
            startButton.setText("Start camera");
        }
        if (logView != null) {
            log("camera stopped");
        }
    }

    private void enrol() {
        if (pipeline == null) {
            log("enrollment blocked because face model is unavailable");
            return;
        }
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
        if (pipeline == null) {
            log("roster controls are unavailable until the face model loads");
            return;
        }
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
                resultView.setText("Camera permission is required to start the camera");
                log("camera permission denied — camera remains stopped");
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
        previewSurface = null;
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
        protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
            return new ViewGroup.LayoutParams(getContext(), attrs);
        }

        @Override
        protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
            return new ViewGroup.LayoutParams(params);
        }

        @Override
        protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
            return params != null;
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
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null && params.height > 0) {
                    specHeight = View.MeasureSpec.makeMeasureSpec(params.height,
                            View.MeasureSpec.EXACTLY);
                } else if (i == count - 1) {
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