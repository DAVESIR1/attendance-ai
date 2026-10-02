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
import org.attendanceai.enrol.EnrolmentDetails;
import org.attendanceai.enrol.EnrolmentWarnings;
import org.attendanceai.model.ModelIntegrity;
import org.attendanceai.store.AttendanceStore;
import org.attendanceai.store.Settings;
import org.attendanceai.vision.CaptureStep;
import org.attendanceai.vision.EmbeddingEngine;
import org.attendanceai.vision.Face;
import org.attendanceai.vision.FaceAligner;
import org.attendanceai.vision.FaceLandmarkerEngine;
import org.attendanceai.vision.FaceMatcher;
import org.attendanceai.vision.GuidedCaptureController;
import org.attendanceai.vision.LandmarkSignatureEngine;
import org.attendanceai.vision.PoseMetrics;
import org.attendanceai.vision.PoseSample;
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
        /** Guided-enrolment progress, or null for an ordinary recognition frame. */
        public final GuidedStatus guided;

        private PipelineResult(String personId, String name, float score,
                int faces, String status, boolean error, boolean punched,
                GuidedStatus guided) {
            this.matchedPersonId = personId;
            this.matchedName = name;
            this.score = score;
            this.faces = faces;
            this.status = status;
            this.error = error;
            this.punched = punched;
            this.guided = guided;
        }

        public static PipelineResult none(String status, int faces) {
            return new PipelineResult("", "", 0f, faces, status, false, false, null);
        }

        public static PipelineResult error(String status, int faces) {
            return new PipelineResult("", "", 0f, faces, status, true, false, null);
        }

        static PipelineResult guided(GuidedStatus guided, int faces) {
            return new PipelineResult("", "", 0f, faces, guided.statusText(), false, false, guided);
        }
    }

    /**
     * One guided-enrolment frame: which pose is being captured, what to tell the
     * user, and how many poses are stored so far. Reaches the UI through
     * {@link PipelineResult#guided} so the existing single listener (and its
     * UI-thread marshalling) stays the only callback path.
     */
    public static final class GuidedStatus {
        public final String name;
        public final CaptureStep step;        // null once every step is captured
        public final String instruction;      // "" once complete
        public final String progress;         // "Step 2 of 5"
        public final boolean noFaceHint;
        public final boolean multiFaceWarning;
        public final boolean waitingForEyeOpen;
        public final boolean complete;
        public final int capturedCount;       // steps captured by the state machine
        public final int embeddingCount;      // embeddings actually stored
        public final int stepCount;
        public final EnrolmentWarnings.SimilarFace similarFace; // null until complete

        GuidedStatus(String name, CaptureStep step, String instruction, String progress,
                boolean noFaceHint, boolean multiFaceWarning, boolean waitingForEyeOpen,
                boolean complete, int capturedCount, int embeddingCount,
                EnrolmentWarnings.SimilarFace similarFace) {
            this.name = name;
            this.step = step;
            this.instruction = instruction;
            this.progress = progress;
            this.noFaceHint = noFaceHint;
            this.multiFaceWarning = multiFaceWarning;
            this.waitingForEyeOpen = waitingForEyeOpen;
            this.complete = complete;
            this.capturedCount = capturedCount;
            this.embeddingCount = embeddingCount;
            this.stepCount = CaptureStep.count();
            this.similarFace = similarFace;
        }

        /** One-line human summary used for the log and the result label. */
        public String statusText() {
            if (complete) {
                return "captured " + capturedCount + "/" + stepCount
                        + " poses (" + embeddingCount + " embeddings)";
            }
            return instruction + " — " + progress;
        }
    }

    private final Settings settings;
    private final AttendanceStore store;
    private final AttendanceService attendance;
    private final Listener listener;

    private FaceLandmarkerEngine landmarker;
    private EmbeddingEngine embedding;
    /** personId → every embedding stored for that person (guided multi-angle). */
    private Map<String, List<float[]>> templates = new LinkedHashMap<String, List<float[]>>();
    private final float[] alignedTensor;

    private volatile boolean busy = false;
    private String integrityNote = "unverified";
    private boolean signatureMode = false;
    /** Causes of every engine/stage that failed to initialise ("" when all loaded). */
    private String initFailureNote = "";
    /** Short landmarker failure text embedded in the per-frame status while it is null. */
    private String landmarkerStatusNote = "";

    // Stability window
    private String streakId = "";
    private int streakCount = 0;
    private float streakScore = 0f;

    // ---- guided multi-angle enrolment -------------------------------------
    /** Non-null exactly while a guided enrolment session is in progress. */
    private GuidedCaptureController guided;
    private String guidedName = "";
    /** One embedding per captured pose (up to CaptureStep.count()). */
    private final List<float[]> guidedEmbeddings = new ArrayList<float[]>();
    /** Warning shown before saving when the new face resembles an existing one. */
    private EnrolmentWarnings.SimilarFace guidedSimilar;
    /** Poses whose embedding could not be computed (reported, never silently lost). */
    private int guidedEmbeddingFailures;

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
        } catch (Throwable failure) {
            // Throwable, not IOException: a staging failure must degrade the
            // integrity note, never escape and kill the whole pipeline.
            String note = FailureDiagnosis.describe(failure);
            integrityNote = "model staging failed: " + note;
            initFailureNote += (initFailureNote.isEmpty() ? "" : " | ") + "model staging: " + note;
            Log.e(TAG, integrityNote, failure);
        }

        try {
            // The staged file is the fallback model source (see FaceLandmarkerEngine):
            // asset path first, then a direct buffer of this file, then the file.
            landmarker = new FaceLandmarkerEngine(context, "face_landmarker.task",
                    new File(store.modelsDir(), "face_landmarker.task"));
        } catch (Throwable failure) {
            // Catch Throwable, not just RuntimeException: FaceLandmarker's
            // static initializer calls System.loadLibrary("mediapipe_tasks_jni"),
            // so a native-library problem arrives as UnsatisfiedLinkError /
            // ExceptionInInitializerError / NoClassDefFoundError — all Errors.
            // Those used to escape this method, blow up the constructor, null
            // the pipeline and block the camera entirely ("camera blocked
            // because face model is unavailable"). Now the pipeline is built
            // anyway (landmarker == null → per-frame "face model unavailable")
            // and the REAL cause is shown in the banner AND the event log.
            Log.e(TAG, "face landmarker init failed", failure);
            String note = FailureDiagnosis.describe(failure);
            integrityNote += " | face_landmarker.task load failed: " + note;
            initFailureNote += (initFailureNote.isEmpty() ? "" : " | ")
                    + "face landmarker: " + note;
            landmarkerStatusNote = FailureDiagnosis.describe(failure, 120);
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
        } catch (Throwable failure) {
            // Same reasoning as the landmarker above: TFLite loads its native
            // library from a static initializer too, and an invalid model can
            // throw unchecked exceptions the old `catch (IOException)` missed.
            // Fall back to signature mode instead of losing the pipeline.
            Log.e(TAG, "embedding engine init failed", failure);
            String note = FailureDiagnosis.describe(failure);
            integrityNote += " | embedding engine failed (" + note + ") → signature mode";
            initFailureNote += (initFailureNote.isEmpty() ? "" : " | ")
                    + "embedding engine: " + note;
            signatureMode = true;
            embedding = null;
        }

        try {
            reloadTemplates();
        } catch (Throwable failure) {
            // A roster/template read failure must not block the camera: the
            // templates simply stay empty and are reloaded on the next call.
            Log.e(TAG, "template reload failed", failure);
            String note = FailureDiagnosis.describe(failure);
            integrityNote += " | template reload failed: " + note;
            initFailureNote += (initFailureNote.isEmpty() ? "" : " | ")
                    + "template reload: " + note;
        }
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

    /**
     * Reloads every person's stored embeddings from the persisted roster. The
     * matcher compares the live face against ALL of them, so a person enrolled
     * from five angles is recognised from any of them.
     */
    public void reloadTemplates() {
        Map<String, List<float[]>> fresh = new LinkedHashMap<String, List<float[]>>();
        for (AttendanceStore.Person person : store.loadRoster().values()) {
            List<float[]> embeddings = person.allTemplates();
            if (!embeddings.isEmpty()) {
                fresh.put(person.id, embeddings);
            }
        }
        templates = fresh;
    }

    public String integrityNote() {
        return integrityNote;
    }

    /**
     * Non-empty when at least one init stage failed (model staging, landmarker,
     * embedder, template reload). Shown once in the on-screen log at startup so
     * the exact cause travels with a screenshot of the log, not only the banner.
     */
    public String initFailureNote() {
        return initFailureNote;
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
            // Carry the real cause in the status itself: it is the line the
            // user sees ticking every frame AND (deduped by key) the line the
            // event log keeps, so a screenshot names the failure.
            listener.onResult(PipelineResult.error(
                    landmarkerStatusNote.isEmpty()
                            ? "face model unavailable"
                            : "face model unavailable — " + landmarkerStatusNote,
                    0));
            return;
        }
        // The pipeline consumes the frame synchronously.
        java.util.List<Face> faces = landmarker.detect(original);
        if (guided != null) {
            // Guided enrolment owns the frame: no matching and no punches while
            // a person is being enrolled.
            runGuidedStep(original, faces);
            return;
        }
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
                match.personId + " seen (#" + streakCount + ")", false, punched, null));
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

    // ---------------------------------------- guided multi-angle enrolment

    /** True while a guided enrolment session is running (and not yet saved). */
    public boolean guidedActive() {
        return guided != null;
    }

    /** True once every pose of the running session has been captured. */
    public boolean guidedComplete() {
        return guided != null && guided.isComplete();
    }

    /** Poses captured so far in the running session. */
    public int guidedCaptureCount() {
        return guided == null ? 0 : guided.capturedCount();
    }

    /** Embeddings stored so far in the running session. */
    public int guidedEmbeddingCount() {
        return guidedEmbeddings.size();
    }

    /** The existing person the captured embeddings resemble, or null. */
    public EnrolmentWarnings.SimilarFace guidedSimilarFace() {
        return guidedSimilar;
    }

    /** Every enrolled person's name, for the duplicate-name gate. */
    public java.util.List<String> rosterNames() {
        java.util.List<String> names = new ArrayList<String>();
        for (AttendanceStore.Person person : store.loadRoster().values()) {
            names.add(person.name);
        }
        return names;
    }

    /**
     * Starts the guided capture for {@code name}. Returns an empty string on
     * success, or a user-facing reason when the flow cannot start. The camera
     * must already be running — frames drive the whole state machine.
     */
    public String beginGuidedCapture(String name) {
        if (landmarker == null) {
            return "cannot enrol: face model unavailable";
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "cannot enrol: no name given";
        }
        guided = new GuidedCaptureController();
        guidedName = trimmed;
        guidedEmbeddings.clear();
        guidedSimilar = null;
        guidedEmbeddingFailures = 0;
        return "";
    }

    /** Abandons the running session without saving anything. */
    public void cancelGuidedCapture() {
        guided = null;
        guidedName = "";
        guidedEmbeddings.clear();
        guidedSimilar = null;
        guidedEmbeddingFailures = 0;
    }

    /**
     * Feeds one frame through the guided state machine: picks the largest face
     * when several are present, samples its pose, and on a capture stores ONE
     * embedding for that pose (up to {@link CaptureStep#count()} total).
     */
    private void runGuidedStep(CameraFrame frame, java.util.List<Face> faces) {
        if (guided.isComplete()) {
            // The final status was delivered on the completing frame; the flow
            // now waits for the details dialogs, so keep ignoring camera frames.
            return;
        }
        Face largest = PoseMetrics.largestFace(faces);
        PoseSample sample = largest == null
                ? PoseSample.none() : PoseMetrics.sample(largest, faces.size());
        GuidedCaptureController.Update update =
                guided.onFrame(sample, System.currentTimeMillis());
        if (update.captured && largest != null) {
            float[] vector = currentVector(largest, frame);
            if (vector.length > 0) {
                guidedEmbeddings.add(vector);
            } else {
                guidedEmbeddingFailures++;
            }
        }
        if (update.complete && guidedSimilar == null) {
            guidedSimilar = findSimilarFace(guidedEmbeddings);
        }
        listener.onResult(PipelineResult.guided(guidedStatus(update), faces.size()));
    }

    private GuidedStatus guidedStatus(GuidedCaptureController.Update update) {
        return new GuidedStatus(guidedName, update.step, update.instruction,
                update.progress, update.noFaceHint, update.multiFaceWarning,
                update.waitingForEyeOpen, update.complete, guided.capturedCount(),
                guidedEmbeddings.size(), update.complete ? guidedSimilar : null);
    }

    /**
     * Best resemblance between the freshly captured embeddings and any existing
     * person's stored embeddings, above the spec's 0.85 warning threshold.
     */
    private EnrolmentWarnings.SimilarFace findSimilarFace(List<float[]> captured) {
        java.util.List<EnrolmentWarnings.KnownPerson> known =
                new ArrayList<EnrolmentWarnings.KnownPerson>();
        for (Map.Entry<String, List<float[]>> entry : templates.entrySet()) {
            known.add(new EnrolmentWarnings.SimpleKnownPerson(
                    entry.getKey(), personName(entry.getKey()), entry.getValue()));
        }
        return EnrolmentWarnings.findSimilarFace(captured, known,
                EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD);
    }

    /**
     * Saves the captured person: every captured embedding plus whatever optional
     * details were filled in (all nullable). Reports the outcome through the
     * normal listener, so the existing sticky enrolment confirmation and the
     * roster-count refresh apply unchanged.
     */
    public void completeGuidedCapture(EnrolmentDetails details) {
        if (guided == null || !guided.isComplete()) {
            listener.onResult(PipelineResult.error("cannot enrol: capture incomplete", 0));
            return;
        }
        if (guidedEmbeddings.isEmpty()) {
            listener.onResult(PipelineResult.error("cannot enrol: no embeddings captured", 0));
            cancelGuidedCapture();
            return;
        }
        Map<String, AttendanceStore.Person> roster =
                new LinkedHashMap<String, AttendanceStore.Person>(store.loadRoster());
        String id = "person-" + System.currentTimeMillis();
        AttendanceStore.Person person =
                AttendanceStore.Person.createMulti(id, guidedName, guidedEmbeddings);
        EnrolmentDetails filled = details == null ? EnrolmentDetails.empty() : details;
        person.identityNumber = filled.identityNumber;
        person.dob = filled.dobMillis;
        person.bloodGroup = filled.bloodGroup;
        person.mobile = filled.mobile;
        roster.put(id, person);
        try {
            store.saveRoster(roster);
            reloadTemplates();
        } catch (IOException e) {
            listener.onResult(PipelineResult.error("roster save failed: " + e.getMessage(), 1));
            // Leave the guided mode behind: a session that stays "complete" but
            // unsaved would keep matching switched off for every later frame.
            cancelGuidedCapture();
            return;
        }
        String name = guidedName;
        int stored = guidedEmbeddings.size();
        int failed = guidedEmbeddingFailures;
        cancelGuidedCapture();
        String note = failed == 0 ? ""
                : " (" + failed + (failed == 1 ? " pose" : " poses") + " not embedded)";
        listener.onResult(new PipelineResult(id, name, 1f, 1,
                "enrolled " + name + " — " + stored + " embeddings" + note,
                false, false, null));
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