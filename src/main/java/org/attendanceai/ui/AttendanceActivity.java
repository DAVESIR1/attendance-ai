/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.attendanceai.camera.Camera2Backend;
import org.attendanceai.camera.CameraBackend;
import org.attendanceai.camera.CameraFrame;
import org.attendanceai.camera.SimulatedCameraBackend;
import org.attendanceai.pipeline.FacePipeline;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildContent();
        store = new AttendanceStore(getFilesDir());
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

        TextView title = new TextView(this);
        title.setText("Attendance AI");
        title.setTextSize(26f);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        banner = new TextView(this);
        banner.setTextSize(12f);
        banner.setTextColor(0xFF9AE6FF);
        root.addView(banner);

        resultView = new TextView(this);
        resultView.setTextSize(16f);
        resultView.setTextColor(Color.WHITE);
        root.addView(resultView);

        startButton = new Button(this);
        startButton.setText("Start camera");
        startButton.setOnClickListener(v -> toggleCamera());
        root.addView(startButton);

        enrollButton = new Button(this);
        enrollButton.setText("Enrol current face");
        enrollButton.setOnClickListener(v -> enrol());
        root.addView(enrollButton);

        Button clear = new Button(this);
        clear.setText("Clear roster");
        clear.setOnClickListener(v -> clearRoster());
        root.addView(clear);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextColor(0xFFCCCCCC);
        scroller.addView(logView);
        root.addView(scroller);

        setContentView(root);
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
        boolean ok = backend.start(frame -> {
            lastFrame = frame;
            if (enqueued.compareAndSet(false, true)) {
                engine.execute(() -> {
                    try {
                        pipeline.process(frame);
                    } finally {
                        enqueued.set(false);
                    }
                });
            }
        }, 640, 480);
        if (!ok) {
            log("no camera — starting simulated camera");
            backend = new SimulatedCameraBackend();
            backend.start(frame -> {
                lastFrame = frame;
                if (enqueued.compareAndSet(false, true)) {
                    engine.execute(() -> {
                        try {
                            pipeline.process(frame);
                        } finally {
                            enqueued.set(false);
                        }
                    });
                }
            }, 640, 480);
        }
        camera = backend;
        running = true;
        startButton.setText("Stop camera");
        log("camera started");
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
        banner.setText(bannerStatus());
    }

    @Override
    protected void onPause() {
        stopCamera();
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
            int y = 0;
            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                int specHeight = View.MeasureSpec.UNSPECIFIED;
                if (i == count - 1) {
                    specHeight = View.MeasureSpec.makeMeasureSpec(Math.max(0, height - y),
                            View.MeasureSpec.EXACTLY);
                }
                child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        specHeight);
                int childHeight = child.getMeasuredHeight();
                int bottom = Math.min(height, y + childHeight);
                child.layout(0, y, width, bottom);
                y += childHeight;
            }
        }
    }
}