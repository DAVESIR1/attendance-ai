/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import java.util.List;

/**
 * Turns MediaPipe FaceMesh landmarks into the approximate yaw/pitch/EAR values
 * the guided multi-angle enrolment flow needs. Pure JVM code — no Android
 * imports — so the arithmetic is unit-tested on the host with synthetic faces.
 *
 * The maths is intentionally simple (as the original spec allows): the nose-tip
 * offset from the eye midpoint, normalised by the inter-eye distance, is used
 * as a yaw proxy; the same offset measured vertically is the pitch proxy; the
 * classic six-point eye-aspect-ratio gives the blink signal. Thresholds and the
 * degree-scaling factors live in {@link GuidedCaptureController} and are meant
 * to be tuned against a real phone.
 */
public final class PoseMetrics {

    /** MediaPipe FaceMesh nose-tip index. */
    public static final int NOSE_TIP = 1;

    // Six-point EAR anchors (classic dlib mapping onto FaceMesh indices).
    // Right eye as seen in the image.
    private static final int R_EYE_H1 = 33;
    private static final int R_EYE_H2 = 133;
    private static final int R_EYE_V1 = 159;
    private static final int R_EYE_V2 = 145;
    private static final int R_EYE_V3 = 158;
    private static final int R_EYE_V4 = 153;
    // Left eye as seen in the image.
    private static final int L_EYE_H1 = 362;
    private static final int L_EYE_H2 = 263;
    private static final int L_EYE_V1 = 386;
    private static final int L_EYE_V2 = 374;
    private static final int L_EYE_V3 = 387;
    private static final int L_EYE_V4 = 373;

    /**
     * Nose offset / inter-eye distance is roughly ±0.3 for a real head turn, so
     * multiply by 45 to land in a "degrees-equivalent" range where the spec's
     * ±15 threshold is meaningful.
     */
    public static final float YAW_SCALE = 45f;
    public static final float PITCH_SCALE = 45f;

    private PoseMetrics() {
    }

    private static boolean has(Face face, int index) {
        return face != null && face.landmarkCount() > index;
    }

    private static float distance(Face face, int a, int b) {
        float dx = face.getX(a) - face.getX(b);
        float dy = face.getY(a) - face.getY(b);
        return (float) Math.hypot(dx, dy);
    }

    /**
     * Horizontal nose-tip displacement from the eye midpoint, normalised by the
     * inter-eye distance and scaled to degrees-equivalent. Negative = the
     * subject's left, positive = the subject's right.
     */
    public static float yawDegrees(Face face) {
        if (!has(face, Face.RIGHT_EYE_OUTER) || !has(face, NOSE_TIP)) {
            return 0f;
        }
        float interEye = distance(face, Face.LEFT_EYE_OUTER, Face.RIGHT_EYE_OUTER);
        if (interEye < 1e-5f) {
            return 0f;
        }
        float eyeMidX = (face.getX(Face.LEFT_EYE_OUTER) + face.getX(Face.RIGHT_EYE_OUTER)) * 0.5f;
        float ratio = (face.getX(NOSE_TIP) - eyeMidX) / interEye;
        return ratio * YAW_SCALE;
    }

    /**
     * Vertical nose-tip displacement from the eye midpoint, normalised by the
     * inter-eye distance and scaled to degrees-equivalent. Negative = looking
     * up, positive = looking down.
     */
    public static float pitchDegrees(Face face) {
        if (!has(face, Face.RIGHT_EYE_OUTER) || !has(face, NOSE_TIP)) {
            return 0f;
        }
        float interEye = distance(face, Face.LEFT_EYE_OUTER, Face.RIGHT_EYE_OUTER);
        if (interEye < 1e-5f) {
            return 0f;
        }
        float eyeMidY = (face.getY(Face.LEFT_EYE_OUTER) + face.getY(Face.RIGHT_EYE_OUTER)) * 0.5f;
        float ratio = (face.getY(NOSE_TIP) - eyeMidY) / interEye;
        return ratio * PITCH_SCALE;
    }

    /** Six-point eye-aspect-ratio averaged over both eyes; 0 when unavailable. */
    public static float eyeAspectRatio(Face face) {
        if (!has(face, L_EYE_V4)) {
            return 0f;
        }
        float right = ear(face, R_EYE_H1, R_EYE_H2, R_EYE_V1, R_EYE_V2, R_EYE_V3, R_EYE_V4);
        float left = ear(face, L_EYE_H1, L_EYE_H2, L_EYE_V1, L_EYE_V2, L_EYE_V3, L_EYE_V4);
        if (right <= 0f) {
            return left;
        }
        if (left <= 0f) {
            return right;
        }
        return (right + left) * 0.5f;
    }

    private static float ear(Face face, int h1, int h2, int v1, int v2, int v3, int v4) {
        float horizontal = distance(face, h1, h2);
        if (horizontal < 1e-5f) {
            return 0f;
        }
        return (distance(face, v1, v2) + distance(face, v3, v4)) / (2f * horizontal);
    }

    /** Builds the observation for one detected face; the largest is chosen upstream. */
    public static PoseSample sample(Face face, int faceCount) {
        if (face == null) {
            return PoseSample.none();
        }
        return new PoseSample(true, yawDegrees(face), pitchDegrees(face),
                eyeAspectRatio(face), faceCount);
    }

    /**
     * The face with the largest bounding-box area — the one the guided flow
     * captures when several people are in shot. Returns null for an empty or
     * all-null list. Pure geometry, unit-tested on the host.
     */
    public static Face largestFace(List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            return null;
        }
        Face best = null;
        float bestArea = -1f;
        for (Face face : faces) {
            if (face == null) {
                continue;
            }
            float area = face.width() * face.height();
            if (area > bestArea) {
                bestArea = area;
                best = face;
            }
        }
        return best;
    }
}
