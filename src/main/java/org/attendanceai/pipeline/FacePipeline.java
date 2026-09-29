/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.pipeline;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.attendanceai.camera.CameraFrame;
import org.attendanceai.model.ModelIntegrity;
import org.attendanceai.store.AttendanceStore;
import org.attendanceai.store.Settings;
import org.attendanceai.vision.EmbeddingEngine;
import org.attendanceai.vision.Face;
import org.attendanceai.vision.FaceAligner;
import org.attendanceai.vision.FaceLandmarkerEngine;
import org.attendanceai.vision.FaceMatcher;
import org.attendanceai.vision.LandmarkSignatureEngine;
import org.attendanceai.vision.TfliteEmbeddingEngine;

/**
 * Orchestrates the vision pipeline for one frame: landmarker → align →
 * embed → match with a stability window, then auto-punch attendance for
 * stable matches. Native inference happens on whatever thread calls
 * {@link #process(CameraFrame)} — in practice the single background executor
 * of the UI. Frames are dropped (busy flag) instead of queued, matching the
 * camera's STRATEGY_KEEP_ONLY_LATEST behaviour.
 */
public final class FacePipeline {

    private static final String TAG = "FacePipeline";

    /** Delivered from the pipeline (engine) thread after every frame. */
    public interface Listener {
        void onResult(PipelineResult result);
    }

    /** Immutable per-frame outcome. */
    public static final class PipelineResult {
        public final String matchedPersonId;
        public final String matchedName;
        public final float score;
        public final int faces;
        public final String status;      // informational text for the UI
        public final boolean error;
        public final boolean punched;    // a fresh attendance record was written

        private PipelineResult(String personId, String name, float score,
                int faces, String status, boolean error, boolean punched) {
            this.matchedPersonId = personId;
            this.matchedName = name;
            this.score = score;
            this.faces = faces;
            this.status = status;
            this.error = error;
            this.punched = punched;
        }

        public static PipelineResult none(String status, int faces) {
            return new PipelineResult("", "", 0f, faces, status, false, false);
        }

        public static PipelineResult error(String status, int faces) {
            return new PipelineResult("", "", 0f, faces, status, true, false);
        }
    }

    private final Settings settings;
    private final AttendanceStore store;
    private final AttendanceService attendance;
    private final Listener listener;

    private FaceLandmarkerEngine landmarker;
    private EmbeddingEngine embedding;
    private Map<String, float[]> templates = new LinkedHashMap<String, float[]>();
    private final float[] alignedTensor;

    private volatile boolean busy = false;
    private String integrityNote = "unverified";
    private boolean signatureMode = false;

    // Stability window
    private String streakId = "";
    private int streakCount = 0;
    private float streakScore = 0f;

    public FacePipeline(Context context, Settings settings, AttendanceStore store,
            Listener listener) {
        this.settings = settings;
        this.store = store;
        this.listener = listener;
        this.attendance = new AttendanceService(settings.checkInCooldownMs);
        this.alignedTensor = new float[settings.modelWidth * settings.modelHeight * 3];
        init(context);
    }

    private void init(Context context) {
        try {
            File modelsDir = stageModels(context);
            verifyIntegrity(modelsDir);
        } catch (IOException e) {
            integrityNote = "model staging failed: " + e.getMessage();
            Log.e(TAG, integrityNote);
        }

        try {
            landmarker = new FaceLandmarkerEngine(context, "face_landmarker.task");
        } catch (RuntimeException e) {
            Log.e(TAG, "face landmarker init failed", e);
            integrityNote += " | face_landmarker.task unavailable";
        }

        try {
            File model = new File(store.modelsDir(), "mobilefacenet.tflite");
            if (Settings.EMBEDDER_TFLITE.equals(settings.embedder) && model.isFile()) {
                TfliteEmbeddingEngine tflite =
                        new TfliteEmbeddingEngine(model, settings.modelWidth, settings.modelHeight);
                embedding = tflite;
                // Surface the model's real tensor shapes in the app banner: a
                // wrong/missing model is then visible without reading logcat.
                integrityNote += " | embedder " + tflite.contractNote();
            } else {
                if (Settings.EMBEDDER_TFLITE.equals(settings.embedder)) {
                    integrityNote += " | mobilefacenet.tflite missing → signature mode";
                }
                embedding = null; // signature mode: computed from Face directly
                signatureMode = true;
            }
        } catch (IOException e) {
            Log.e(TAG, "embedding engine init failed", e);
            integrityNote += " | embedding engine failed → signature mode";
            signatureMode = true;
            embedding = null;
        }

        reloadTemplates();
    }

    /** Copies bundled assets into private storage (choice point for integrity). */
    private File stageModels(Context context) throws IOException {
        File dir = store.modelsDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create model dir " + dir);
        }
        copyAsset(context, "face_landmarker.task", new File(dir, "face_landmarker.task"));
        copyAsset(context, "mobilefacenet.tflite", new File(dir, "mobilefacenet.tflite"));
        // checksums.sha256 is staged into assets by scripts/fetch_models.sh.
        copyAssetQuietly(context, "checksums.sha256", new File(dir, "checksums.sha256"));
        return dir;
    }

    private static void copyAsset(Context context, String name, File target) throws IOException {
        try (InputStream in = context.getAssets().open(name);
                FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void copyAssetQuietly(Context context, String name, File target) {
        try {
            copyAsset(context, name, target);
        } catch (IOException ignored) {
            // checksums file is optional at runtime.
        }
    }

    private void verifyIntegrity(File modelsDir) {
        File checksums = new File(modelsDir, "checksums.sha256");
        if (!checksums.isFile()) {
            integrityNote = "no checksums bundled — models not verified";
            return;
        }
        List<String> failures;
        try {
            failures = ModelIntegrity.verifyAll(modelsDir, checksums);
        } catch (IOException e) {
            integrityNote = "checksum verification failed: " + e.getMessage();
            return;
        }
        integrityNote = failures.isEmpty() ? "verified"
                : "integrity failures: " + failures;
        if (!failures.isEmpty()) {
            Log.e(TAG, integrityNote);
        }
    }

    /** Reloads the person templates from the persisted roster. */
    public void reloadTemplates() {
        Map<String, float[]> fresh = new LinkedHashMap<String, float[]>();
        for (AttendanceStore.Person person : store.loadRoster().values()) {
            if (person.template != null && person.template.length > 0) {
                fresh.put(person.id, person.template);
            }
        }
        templates = fresh;
    }

    public String integrityNote() {
        return integrityNote;
    }

    public boolean inSignatureMode() {
        return signatureMode;
    }

    /**
     * Processes one frame. Returns immediately (dropping the frame) if the
     * previous frame is still being processed.
     */
    public void process(CameraFrame frame) {
        if (busy) {
            return;
        }
        busy = true;
        try {
            runOnce(frame);
        } catch (RuntimeException e) {
            Log.e(TAG, "pipeline error", e);
            listener.onResult(PipelineResult.error("inference error", frame == null ? 0 : 1));
        } finally {
            busy = false;
        }
    }

    private void runOnce(CameraFrame original) {
        if (landmarker == null) {
            listener.onResult(PipelineResult.error("face model unavailable", 0));
            return;
        }
        // The pipeline consumes the frame synchronously.
        java.util.List<Face> faces = landmarker.detect(original);
        if (faces.isEmpty()) {
            resetStreak();
            listener.onResult(PipelineResult.none("no face", 0));
            return;
        }
        Face best = pickBest(faces);
        if (best == null) {
            listener.onResult(PipelineResult.none("no face", faces.size()));
            return;
        }
        float[] vector = currentVector(best, original);
        if (vector.length == 0) {
            listener.onResult(PipelineResult.none("embedding failed", faces.size()));
            return;
        }
        FaceMatcher.MatchResult match = FaceMatcher.match(vector, templates,
                settings.similarityThreshold);
        if (!match.accepted) {
            resetStreak();
            listener.onResult(PipelineResult.none(
                    String.format("unknown face (best=%.2f)", match.score), faces.size()));
            return;
        }
        if (!match.personId.equals(streakId)) {
            streakId = match.personId;
            streakCount = 1;
            streakScore = match.score;
        } else {
            streakCount++;
            streakScore = (streakScore + match.score) / 2f;
        }

        boolean punched = false;
        if (streakCount >= settings.stableFrames) {
            AttendanceStore.Person person = store.loadRoster().get(match.personId);
            String name = person != null ? person.name : "person " + match.personId;
            AttendanceStore.Record record =
                    attendance.maybePunch(match.personId, name, streakScore,
                            System.currentTimeMillis());
            if (record != null) {
                punched = true;
                try {
                    store.appendRecord(record);
                } catch (IOException e) {
                    Log.e(TAG, "record persistence failed", e);
                }
            }
        }
        listener.onResult(new PipelineResult(match.personId,
                personName(match.personId), streakScore, faces.size(),
                match.personId + " seen (#" + streakCount + ")", false, punched));
    }

    private String personName(String id) {
        AttendanceStore.Person person = store.loadRoster().get(id);
        return person != null ? person.name : "";
    }

    private float[] currentVector(Face face, CameraFrame frame) {
        if (signatureMode) {
            return new LandmarkSignatureEngine().embed(face);
        }
        if (FaceAligner.fillAlignedTensor(face, frame, settings.modelWidth,
                settings.modelHeight, alignedTensor)) {
            return embedding.embed(alignedTensor);
        }
        return new float[0];
    }

    /** Enrols the best face in the last delivered frame under {@code name}. */
    public void enrollBestFace(CameraFrame lastFrame, String name) {
        if (lastFrame == null || landmarker == null || name == null || name.length() == 0) {
            listener.onResult(PipelineResult.error("cannot enrol (need a frame and a name)", 0));
            return;
        }
        java.util.List<Face> faces;
        try {
            faces = landmarker.detect(lastFrame);
        } catch (RuntimeException e) {
            // A detection failure must be visible on the enrolment path too —
            // the click handler has no try/catch around the pipeline.
            listener.onResult(PipelineResult.error("face detection failed", 0));
            return;
        }
        Face best = pickBest(faces);
        if (best == null) {
            listener.onResult(PipelineResult.error("no face to enrol", faces.size()));
            return;
        }
        float[] vector = currentVector(best, lastFrame);
        if (vector.length == 0) {
            listener.onResult(PipelineResult.error("embedding failed during enrolment",
                    faces.size()));
            return;
        }
        java.util.Map<String, AttendanceStore.Person> roster =
                new LinkedHashMap<String, AttendanceStore.Person>(store.loadRoster());
        String id = "person-" + System.currentTimeMillis();
        AttendanceStore.Person person =
                AttendanceStore.Person.create(id, name, vector);
        roster.put(id, person);
        try {
            store.saveRoster(roster);
            reloadTemplates();
        } catch (IOException e) {
            listener.onResult(PipelineResult.error("roster save failed: " + e.getMessage(), 1));
            return;
        }
        listener.onResult(new PipelineResult(id, name, 1f, faces.size(),
                "enrolled " + name, false, false));
    }

    private static Face pickBest(java.util.List<Face> faces) {
        Face best = null;
        for (Face face : faces) {
            if (best == null || face.quality() > best.quality()) {
                best = face;
            }
        }
        return best;
    }

    private void resetStreak() {
        streakId = "";
        streakCount = 0;
        streakScore = 0f;
    }

    /** Releases native resources (call on the pipeline thread, once). */
    public void close() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
    }
}