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
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.attendanceai.camera.CameraBackend;
import org.attendanceai.camera.CameraFrame;

import org.attendanceai.pipeline.FacePipeline;
import org.attendanceai.pipeline.FailureDiagnosis;
import org.attendanceai.presentation.lockscreen.LockScreenActivity;
import org.attendanceai.presentation.lockscreen.SecurityGate;
import org.attendanceai.vision.CameraFacing;
import org.attendanceai.vision.CameraFacingState;
import org.attendanceai.vision.CameraXSource;
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
 * The CameraX capture source delivers upright ARGB frames; the engine executor
 * hands each frame to the pipeline (dropping frames while one is in flight).
 * All pipeline results are marshalled to the UI thread.
 */
public final class AttendanceActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CAMERA = 1001;

    /**
     * Enrol confirmation holds the live result line for this long — it used to
     * be overwritten by the very next camera frame (~100 ms later), which is
     * why enrolment looked like it produced no confirmation.
     */
    private static final long ENROL_STICKY_MS = 2500L;

    private ColumnLayout root;
    private TextView banner;
    private TextView resultView;
    private TextView logView;
    private Button startButton;
    private Button enrollButton;
    /** CameraX renders the preview into this view; the CameraXSource owns it. */
    private PreviewView previewView;
    /**
     * Which physical camera to bind. Starts on FRONT (the app's original
     * default) and flips on every tap of the switch-camera button; it lives
     * here, not in the camera source, so it survives camera restarts.
     */
    private final CameraFacingState cameraFacing = new CameraFacingState();
    /** Rate-limits frame statuses to one log line per 1.5 s, deduped by key. */
    private final EventLogThrottle logThrottle = new EventLogThrottle();
    /** Bounded scrollback: the log view never grows past its line cap. */
    private final LogHistory logHistory = new LogHistory();
    /** While now < this, the live result line is frozen on an enrol message. */
    private long enrolStickyUntilMs;
    private String initializationPhase = "secure session";
    /** Cause of the last pipeline-init failure ("" while the model is healthy). */
    private String pipelineFailureNote = "";

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

    /**
     * Settings is launched for a result so a roster clear performed there can
     * reload this screen's face templates (and refresh the banner) instead of
     * leaving the matcher matching against people that no longer exist.
     */
    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                String action = data == null ? null : data.getAction();
                if (result.getResultCode() == RESULT_OK
                        && SettingsActivity.RESULT_RELOAD_TEMPLATES.equals(action)) {
                    if (pipeline != null) {
                        pipeline.reloadTemplates();
                    }
                    refreshBanner();
                    log("roster changed in Settings — face templates reloaded");
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
            if (!initPipeline()) {
                // initPipeline already put the detailed cause on the banner and
                // the result line — exactly ONE log line for the startup failure.
                log("face model unavailable at startup: " + pipelineFailureNote);
            } else if (!pipeline.initFailureNote().isEmpty()) {
                // The shell is alive but an engine failed to load: one line with
                // the exact cause, so the log itself is diagnosable.
                log("face model problem: " + pipeline.initFailureNote());
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
     * Builds the face-recognition pipeline. Never throws: any failure —
     * including Errors (UnsatisfiedLinkError, ExceptionInInitializerError,
     * NoClassDefFoundError) raised by the MediaPipe/TFLite static
     * initializers when they load their native libraries — leaves
     * {@link #pipeline} null, records a readable one-line cause in
     * {@link #pipelineFailureNote} and shows it on the banner.
     *
     * Called again from {@link #startCamera()}, so every "Start camera" tap
     * retries a previously failed model load instead of only repeating the
     * error. Logging is the caller's job: startup logs unconditionally, the
     * retry path logs through the throttle so repeated taps cannot stack
     * identical "camera blocked" lines.
     */
    private boolean initPipeline() {
        try {
            pipeline = new FacePipeline(this, store.loadSettings(), store, result -> {
                runOnUiThread(() -> presentResult(result));
            });
            pipelineFailureNote = "";
            refreshBanner();
            return true;
        } catch (Throwable failure) {
            pipeline = null;
            pipelineFailureNote = FailureDiagnosis.describe(failure);
            banner.setText("Encrypted storage ready • face model unavailable ("
                    + pipelineFailureNote + ")");
            resultView.setTextColor(0xFFC23B5A);
            resultView.setText("Camera features need the face model to start — cause in the banner");
            return false;
        }
    }

    /**
     * Refreshes the integrity banner. {@link #bannerStatus()} queries Room, so
     * a storage hiccup must degrade to a note instead of crashing — and it
     * must never run through a catch that nulls an already-constructed
     * pipeline: the old inline try covered BOTH construction and the banner
     * refresh, so a banner failure silently discarded a healthy pipeline.
     */
    private void refreshBanner() {
        if (banner == null) {
            // The lock screen runs before buildContent() — nothing to refresh.
            return;
        }
        if (pipeline == null) {
            banner.setText("Encrypted storage ready • face model unavailable ("
                    + (pipelineFailureNote.isEmpty() ? "not initialised" : pipelineFailureNote)
                    + ")");
            return;
        }
        try {
            banner.setText(bannerStatus());
        } catch (Throwable failure) {
            banner.setText("models: " + pipeline.integrityNote()
                    + " | status unavailable (" + FailureDiagnosis.describe(failure) + ")");
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

        // CameraX needs a view it can drive itself: PreviewView handles the
        // surface, aspect ratio, rotation and scaling, so the app has no
        // SurfaceHolder bookkeeping left to get wrong.
        previewView = new PreviewView(this);
        previewView.setBackground(roundBackground(0xFFCBD6E8, 22));
        previewView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        // Preview + floating icons (item 1 camera switch, item 2 navigation):
        // the buttons only drive camera/navigation, never the pipeline.
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.addView(previewView);
        ImageButton menuButton = new ImageButton(this);
        menuButton.setImageResource(android.R.drawable.ic_menu_more);
        menuButton.setContentDescription("Open menu");
        menuButton.setBackground(roundBackground(0xDFFFFFFF, 18));
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        menuLp.setMargins(dp(12), dp(12), 0, 0);
        menuButton.setLayoutParams(menuLp);
        menuButton.setOnClickListener(v -> openNavigationMenu(v));
        previewFrame.addView(menuButton);
        ImageButton switchCamera = new ImageButton(this);
        switchCamera.setImageResource(android.R.drawable.ic_menu_rotate);
        switchCamera.setContentDescription("Switch camera");
        switchCamera.setBackground(roundBackground(0xDFFFFFFF, 18));
        FrameLayout.LayoutParams switchLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        switchLp.setMargins(0, 0, dp(12), dp(12));
        switchCamera.setLayoutParams(switchLp);
        switchCamera.setOnClickListener(v -> switchCameraFacing());
        previewFrame.addView(switchCamera);
        root.addView(previewFrame, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

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

    /**
     * Appends one line to the scrolling log (fix 2): bounded to the newest
     * {@link LogHistory#DEFAULT_MAX_LINES} entries, and every write counts
     * against the throttle's hard rate limit.
     */
    private void log(String line) {
        logThrottle.markLogged(System.currentTimeMillis());
        logView.setText(logHistory.append(line));
    }

    /** Writes a frame status only when the throttle allows it (fixes 1+2+5). */
    private void logThrottled(String key, String line) {
        if (logThrottle.shouldLog(key, System.currentTimeMillis())) {
            log(line);
        }
    }

    /**
     * Per-frame result display: the live result line keeps updating every
     * frame; the scrolling log gets only key-changed status lines, at most one
     * per 1.5 s. One-shot events (punch, enrol) are always logged.
     */
    private void presentResult(FacePipeline.PipelineResult result) {
        long now = System.currentTimeMillis();
        String sim = String.format("%.2f", result.score);
        String who = result.matchedName.length() > 0
                ? result.matchedName : result.matchedPersonId;

        // (3) The one outcome that writes to the database: announce it — it
        // was previously computed (PipelineResult.punched) but never read —
        // and refresh the banner's record count immediately instead of only
        // in onResume().
        if (result.punched) {
            log("PRESENT: " + who + " (sim " + sim + ") recorded for today");
            refreshBanner();
        }

        // (4) Enrol outcomes freeze the live result line for ENROL_STICKY_MS
        // so the confirmation cannot be overwritten by the next frame, and are
        // always logged exactly once.
        if (EventLogThrottle.isEnrolmentStatus(result.status)) {
            resultView.setTextColor(result.error ? 0xFFC23B5A : 0xFF1D2942);
            resultView.setText(result.error
                    ? "! " + result.status
                    : "✓ " + (result.matchedName.length() > 0
                            ? result.matchedName + " enrolled" : result.status));
            log((result.error ? "error: " : "") + result.status);
            if (!result.error) {
                refreshBanner(); // roster count changed
            }
            enrolStickyUntilMs = now + ENROL_STICKY_MS;
            return;
        }

        // Sticky window: hold the enrol message on screen.
        if (now < enrolStickyUntilMs) {
            return;
        }

        if (result.error) {
            resultView.setTextColor(0xFFC23B5A);
            resultView.setText("! " + result.status);
            logThrottled(EventLogThrottle.errorKey(result.status),
                    "error: " + result.status);
            return;
        }
        resultView.setTextColor(0xFF1D2942);
        if (result.matchedPersonId.length() > 0) {
            // Live line unchanged (updates every frame with the fresh score);
            // the log uses a stable per-person key so a continuous sighting is
            // reported once, and again only after leaving and coming back.
            resultView.setText("✓ " + who + "  (sim " + sim + ")  " + result.status);
            logThrottled(EventLogThrottle.matchKey(result.matchedPersonId),
                    "match: " + who + " @" + sim);
        } else {
            resultView.setText(result.status);
            logThrottled(EventLogThrottle.statusKey(result.status), result.status);
        }
    }

    private void toggleCamera() {
        if (!running) {
            startCamera();
        } else {
            stopCamera();
        }
    }

    /**
     * Camera-switch button (item 1): flips the facing state and, when the
     * camera is live, stops the current session and restarts it with the
     * other CameraSelector. The pipeline (detection/matching/logging) is
     * untouched — only the camera source is torn down and rebuilt.
     */
    private void switchCameraFacing() {
        CameraFacing next = cameraFacing.toggle();
        if (running) {
            log("switching camera: " + next.getLabel() + " camera requested");
            stopCamera();
            startCamera();
        } else {
            log("camera switch: next start will use the " + next.getLabel() + " camera");
        }
    }

    private void startCamera() {
        if (running) {
            return;
        }
        if (pipeline == null) {
            // Every tap is a retry: if the model failed to load at startup
            // (native library, model staging, storage), try again before
            // blocking — a transient failure must not brick the camera.
            if (!initPipeline()) {
                resultView.setTextColor(0xFFC23B5A);
                resultView.setText("! face model unavailable — cause in the banner");
                // Throttled and keyed by cause: tapping Start repeatedly must
                // not stack identical "camera blocked" lines (phone report).
                logThrottled("camera-blocked:" + pipelineFailureNote,
                        "camera blocked: face model unavailable (" + pipelineFailureNote + ")");
                return;
            }
            resultView.setTextColor(0xFF1D2942);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            resultView.setText("Camera permission needed — allow it, then tap Start camera");
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    PERMISSION_REQUEST_CAMERA);
            log("camera permission not granted");
            return;
        }
        if (previewView == null) {
            resultView.setText("Camera preview is still loading — please try again");
            log("camera preview is not ready");
            return;
        }
        // CameraX backend: PreviewView renders the preview and the ImageAnalysis
        // use case feeds frames in; both are bound to this activity's lifecycle.
        // The requested facing comes from the switch-camera state (front default).
        CameraXSource source = new CameraXSource(this, previewView, cameraFacing.getFacing());
        source.setStateListener(new CameraXSource.StateListener() {
            @Override
            public void onCameraStarted() {
                runOnUiThread(() -> log("camera ready (CameraX preview + analysis bound)"));
            }

            @Override
            public void onCameraFailed(String reason) {
                // Asynchronous CameraX failures must be visible on screen —
                // they are exactly what used to fail silently.
                runOnUiThread(() -> {
                    resultView.setText("Camera problem: " + reason);
                    log("camera failed: " + reason);
                });
            }
        });
        boolean ok;
        try {
            ok = source.start(this::submitFrame, 640, 480);
        } catch (RuntimeException e) {
            log("camera unavailable: " + e.getClass().getSimpleName());
            ok = false;
        }
        if (!ok) {
            String reason = source.lastError();
            resultView.setText(reason.isEmpty()
                    ? "Camera could not be opened"
                    : "Camera could not be opened (" + reason + ")");
            log("camera unavailable: " + (reason.isEmpty() ? "unknown reason" : reason));
            return;
        }
        camera = source;
        running = true;
        startButton.setText("Stop camera");
        log("camera starting (CameraX)");
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
            logThrottled("enrol-blocked:" + pipelineFailureNote,
                    "enrollment blocked: face model unavailable (" + pipelineFailureNote + ")");
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

    /**
     * Navigation shell (item 2): the floating top-left button opens a small
     * menu with the three destinations. Plain {@link PopupMenu} plus activities
     * — no Navigation component, no new library, and nothing added to the
     * existing Compose lock screen.
     */
    private void openNavigationMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        for (NavTarget target : NavTarget.values()) {
            popup.getMenu().add(Menu.NONE, target.getMenuId(), target.ordinal(),
                    target.getLabel());
        }
        popup.setOnMenuItemClickListener(item -> {
            onNavigationSelected(NavTarget.fromMenuId(item.getItemId()));
            return true;
        });
        popup.show();
    }

    /** Routes one menu choice; Home is this screen, so it only acknowledges. */
    private void onNavigationSelected(NavTarget target) {
        switch (target) {
            case REPORTS:
                startActivity(ReportsActivity.createIntent(this));
                break;
            case SETTINGS:
                settingsLauncher.launch(SettingsActivity.createIntent(this));
                break;
            case HOME:
            default:
                log("menu: Home is already open");
                break;
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
        if (banner != null && store != null) {
            refreshBanner();
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