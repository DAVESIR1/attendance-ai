/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

/**
 * One frame's approximate head-pose and eye-state observations, in the small
 * set of units the guided-capture state machine needs. Produced from a
 * {@link Face}'s landmarks by {@link PoseMetrics} and consumed by
 * {@link GuidedCaptureController}.
 *
 * Deliberately a plain value object with no Android imports, so the state
 * machine can be unit-tested on the host JVM with synthetic landmark sequences
 * (including sequences that must NOT trigger a capture).
 *
 * Sign conventions (empirical, tuned on the phone — see PoseMetrics):
 *   yawDegrees   &lt; 0 → head turned to the subject's LEFT  (LOOK_LEFT)
 *   yawDegrees   &gt; 0 → head turned to the subject's RIGHT (LOOK_RIGHT)
 *   pitchDegrees &lt; 0 → head tilted UP   (LOOK_UP)
 *   pitchDegrees &gt; 0 → head tilted DOWN (LOOK_DOWN)
 * eyeAspectRatio is the classic EAR: ~0.25-0.35 with the eyes open, dropping
 * toward ~0.10 when closed.
 */
public final class PoseSample {

    /** True when a face was found in this frame. */
    public final boolean facePresent;
    /** Approximate horizontal head rotation in degrees-equivalent. */
    public final float yawDegrees;
    /** Approximate vertical head rotation in degrees-equivalent. */
    public final float pitchDegrees;
    /** Eye aspect ratio (EAR) averaged over both eyes; 0 when unavailable. */
    public final float eyeAspectRatio;
    /** Number of faces detected in the frame (1 in the common case). */
    public final int faceCount;

    public PoseSample(boolean facePresent, float yawDegrees, float pitchDegrees,
            float eyeAspectRatio, int faceCount) {
        this.facePresent = facePresent;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.eyeAspectRatio = eyeAspectRatio;
        this.faceCount = faceCount;
    }

    /** No face in this frame. */
    public static PoseSample none() {
        return new PoseSample(false, 0f, 0f, 0f, 0);
    }

    /** Convenience factory for tests and synthetic sequences. */
    public static PoseSample of(float yawDegrees, float pitchDegrees, float ear) {
        return new PoseSample(true, yawDegrees, pitchDegrees, ear, 1);
    }

    /** Same pose, but with an explicit face count (multi-face warning tests). */
    public static PoseSample of(float yawDegrees, float pitchDegrees, float ear, int faceCount) {
        return new PoseSample(true, yawDegrees, pitchDegrees, ear, faceCount);
    }
}
