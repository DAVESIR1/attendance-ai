/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drives the guided multi-angle enrolment sequence
 * LOOK_UP → LOOK_DOWN → LOOK_LEFT → LOOK_RIGHT → BLINK.
 *
 * Feed it one {@link PoseSample} per detected frame (largest face when several
 * are present) and it decides, in order:
 *
 *  1. which step is current and what instruction/progress to show;
 *  2. whether the current step's pose (or blink) has held for
 *     {@link #HOLD_FRAMES} consecutive frames — only then does it report a
 *     capture, so a blurry mid-motion frame is never stored;
 *  3. when to auto-advance, and when the whole flow is complete.
 *
 * It is deliberately pure JVM code (no android.* imports, no camera, no
 * embedder) so the whole state machine — including the hold-count edge case and
 * the no-face hint — is unit-tested on the host with synthetic landmark
 * sequences. The caller (the pipeline) owns frame selection, embedding
 * computation and storage.
 *
 * Thresholds are the spec's starting values and are expected to be tuned
 * against a real phone; every one of them is a constructor parameter so a test
 * (or a later settings screen) can override them without editing this class.
 */
public final class GuidedCaptureController {

    /** |yaw| above this counts as a left/right head turn (degrees-equivalent). */
    public static final float YAW_THRESHOLD_DEGREES = 15f;
    /** |pitch| above this counts as an up/down head tilt (degrees-equivalent). */
    public static final float PITCH_THRESHOLD_DEGREES = 15f;
    /** Consecutive frames a pose/eye-state must hold before a capture. */
    public static final int HOLD_FRAMES = 3;
    /** EAR below this counts as "eyes closed" for the blink step. */
    public static final float BLINK_CLOSED_EAR = 0.20f;
    /** EAR above this counts as "eyes reopened", completing a blink. */
    public static final float BLINK_OPEN_EAR = 0.25f;
    /** The eyes must reopen within this long after closing, or it is not a blink. */
    public static final long BLINK_WINDOW_MS = 1000L;
    /** No face for longer than this shows the passive on-screen hint. */
    public static final long NO_FACE_HINT_MS = 5000L;

    /** Passive hint shown while no face has been seen for a while. */
    public static final String NO_FACE_HINT = "keep your face in frame";
    /** Warning shown when more than one face is in frame (largest is used). */
    public static final String MULTI_FACE_HINT = "multiple faces detected, using the largest";

    private static final CaptureStep[] STEPS = CaptureStep.values();

    private final float yawThreshold;
    private final float pitchThreshold;
    private final int holdFrames;
    private final float blinkClosedEar;
    private final float blinkOpenEar;
    private final long blinkWindowMs;
    private final long noFaceHintMs;

    private int stepIndex;
    private final List<CaptureStep> captures = new ArrayList<CaptureStep>();

    // Per-step pose-hold counter (pose steps) and blink bookkeeping.
    private int holdCount;
    private int closedRun;
    private long closedSinceMs;
    private boolean blinkArmed;

    /**
     * When the current no-face streak began; -1 while a face is present (so a
     * first frame at timestamp 0 is not mistaken for "no streak yet").
     */
    private long noFaceSinceMs = -1L;

    private boolean complete;

    /** Production defaults (the spec's starting thresholds). */
    public GuidedCaptureController() {
        this(YAW_THRESHOLD_DEGREES, PITCH_THRESHOLD_DEGREES, HOLD_FRAMES,
                BLINK_CLOSED_EAR, BLINK_OPEN_EAR, BLINK_WINDOW_MS, NO_FACE_HINT_MS);
    }

    /** Full control for tests / future tuning. */
    public GuidedCaptureController(float yawThreshold, float pitchThreshold, int holdFrames,
            float blinkClosedEar, float blinkOpenEar, long blinkWindowMs, long noFaceHintMs) {
        this.yawThreshold = yawThreshold;
        this.pitchThreshold = pitchThreshold;
        this.holdFrames = Math.max(1, holdFrames);
        this.blinkClosedEar = blinkClosedEar;
        this.blinkOpenEar = blinkOpenEar;
        this.blinkWindowMs = blinkWindowMs;
        this.noFaceHintMs = noFaceHintMs;
    }

    /** The step currently being captured, or null once the flow is complete. */
    public CaptureStep currentStep() {
        return complete ? null : STEPS[stepIndex];
    }

    /** Steps captured so far, in order. */
    public List<CaptureStep> captures() {
        return Collections.unmodifiableList(captures);
    }

    /** Number of captured steps (0..5). */
    public int capturedCount() {
        return captures.size();
    }

    public boolean isComplete() {
        return complete;
    }

    /** "Step 2 of 5" for the progress indicator. */
    public static String progressText(CaptureStep step) {
        return "Step " + step.stepNumber() + " of " + CaptureStep.count();
    }

    /**
     * Feeds one frame's observation and returns what the UI should show and
     * whether a frame should be captured for the current step.
     */
    public Update onFrame(PoseSample sample, long nowMs) {
        PoseSample observed = sample == null ? PoseSample.none() : sample;
        boolean multiFace = observed.facePresent && observed.faceCount > 1;

        if (complete) {
            return new Update(null, -1, "All steps captured", "Done",
                    false, null, true, false, multiFace, false);
        }

        CaptureStep step = STEPS[stepIndex];
        String progress = progressText(step);

        if (!observed.facePresent) {
            // Track the no-face streak, but never fail the flow: the hint is
            // purely informational and the step simply waits.
            if (noFaceSinceMs < 0L) {
                noFaceSinceMs = nowMs;
            }
            boolean hint = nowMs - noFaceSinceMs >= noFaceHintMs;
            resetHold();
            return new Update(step, stepIndex, step.instruction(), progress,
                    false, null, false, hint, multiFace, false);
        }
        noFaceSinceMs = -1L;

        if (step == CaptureStep.BLINK) {
            if (observeBlink(observed.eyeAspectRatio, nowMs)) {
                return capture(step, multiFace);
            }
            return new Update(step, stepIndex, step.instruction(), progress,
                    false, null, false, false, multiFace, blinkArmed);
        }

        if (poseSatisfied(step, observed)) {
            holdCount++;
            if (holdCount >= holdFrames) {
                return capture(step, multiFace);
            }
        } else {
            holdCount = 0;
        }
        return new Update(step, stepIndex, step.instruction(), progress,
                false, null, false, false, multiFace, false);
    }

    private boolean poseSatisfied(CaptureStep step, PoseSample sample) {
        switch (step) {
            case LOOK_UP:
                return sample.pitchDegrees <= -pitchThreshold;
            case LOOK_DOWN:
                return sample.pitchDegrees >= pitchThreshold;
            case LOOK_LEFT:
                return sample.yawDegrees <= -yawThreshold;
            case LOOK_RIGHT:
                return sample.yawDegrees >= yawThreshold;
            default:
                return false;
        }
    }

    /**
     * Blink detection: the eyes must close (EAR below {@link #BLINK_CLOSED_EAR})
     * for {@link #HOLD_FRAMES} consecutive frames, then reopen (EAR above
     * {@link #BLINK_OPEN_EAR}) within {@link #BLINK_WINDOW_MS} of first closing.
     * Returns true only on the frame that completes a genuine blink — so a
     * closed-eye run of only two frames never captures.
     */
    private boolean observeBlink(float ear, long nowMs) {
        if (ear < blinkClosedEar) {
            if (closedRun == 0) {
                closedSinceMs = nowMs;
            }
            closedRun++;
            if (closedRun >= holdFrames) {
                blinkArmed = true;
            }
            return false;
        }
        if (closedRun == 0) {
            return false;
        }
        if (nowMs - closedSinceMs > blinkWindowMs) {
            // Held shut too long to be a blink (or the eyes never reopened):
            // give up on this attempt so its start time cannot leak into the
            // next one.
            resetBlinkAttempt();
            return false;
        }
        if (ear > blinkOpenEar) {
            boolean completed = blinkArmed;
            resetBlinkAttempt();
            return completed;
        }
        // Mid-transition frame (closed ≤ EAR ≤ open): keep the attempt alive so a
        // fast blink is not dropped just because one frame landed between the
        // two thresholds; the window check above still bounds it.
        return false;
    }

    private void resetBlinkAttempt() {
        closedRun = 0;
        closedSinceMs = 0L;
        blinkArmed = false;
    }

    /** Records a capture for {@code step} and auto-advances to the next step. */
    private Update capture(CaptureStep step, boolean multiFace) {
        captures.add(step);
        resetHold();
        if (stepIndex < STEPS.length - 1) {
            stepIndex++;
        } else {
            complete = true;
        }
        if (complete) {
            return new Update(null, -1, "All steps captured", "Done",
                    true, step, true, false, multiFace, false);
        }
        CaptureStep next = STEPS[stepIndex];
        return new Update(next, stepIndex, next.instruction(), progressText(next),
                true, step, false, false, multiFace, false);
    }

    private void resetHold() {
        holdCount = 0;
        closedRun = 0;
        closedSinceMs = 0L;
        blinkArmed = false;
    }

    /** One frame's decision: what to show, and whether to capture now. */
    public static final class Update {
        /** Current step (null when complete). */
        public final CaptureStep step;
        /** Zero-based index of the current step (-1 when complete). */
        public final int stepIndex;
        /** Instruction text for the current step. */
        public final String instruction;
        /** Progress indicator text, e.g. "Step 2 of 5". */
        public final String progress;
        /** True only on the frame where the current step was captured. */
        public final boolean captured;
        /** The step captured on this frame, or null. */
        public final CaptureStep capturedStep;
        /** True once all five steps are captured. */
        public final boolean complete;
        /** True when the passive "keep your face in frame" hint applies. */
        public final boolean noFaceHint;
        /** True when more than one face is in frame (the largest is used). */
        public final boolean multiFaceWarning;
        /** True while waiting for the eyes to reopen during the blink step. */
        public final boolean waitingForEyeOpen;

        Update(CaptureStep step, int stepIndex, String instruction, String progress,
                boolean captured, CaptureStep capturedStep, boolean complete,
                boolean noFaceHint, boolean multiFaceWarning, boolean waitingForEyeOpen) {
            this.step = step;
            this.stepIndex = stepIndex;
            this.instruction = instruction;
            this.progress = progress;
            this.captured = captured;
            this.capturedStep = capturedStep;
            this.complete = complete;
            this.noFaceHint = noFaceHint;
            this.multiFaceWarning = multiFaceWarning;
            this.waitingForEyeOpen = waitingForEyeOpen;
        }
    }
}