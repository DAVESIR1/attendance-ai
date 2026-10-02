/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Pose arithmetic on synthetic landmark sets. The synthetic face puts the eyes
 * 0.2 apart with the nose 0.1 to one side, so the expected yaw/pitch is a plain
 * multiplication — no real model or camera needed.
 */
public class PoseMetricsTest {

    private static final float EYE_Y = 0.4f;
    private static final float RIGHT_EYE_X = 0.4f;
    private static final float RIGHT_INNER_X = 0.48f;
    private static final float LEFT_INNER_X = 0.52f;
    private static final float LEFT_EYE_X = 0.6f;

    /**
     * Builds a face with the given nose position and eye openness.
     * {@code lidGap} is half the vertical distance between the upper and lower
     * lid points; EAR = 25 × lidGap for this geometry.
     */
    private static Face face(float noseX, float noseY, float lidGap) {
        float[] landmarks = new float[Face.LANDMARKS_PER_FACE * Face.LANDMARK_DIM];
        // Eye corners are written for the exact indices PoseMetrics reads
        // (33/133 and 362/263) — Face.LEFT/RIGHT_EYE_OUTER are named from the
        // subject's perspective and do NOT match those, so index explicitly.
        put(landmarks, 33, RIGHT_EYE_X, EYE_Y);
        put(landmarks, 133, RIGHT_INNER_X, EYE_Y);
        put(landmarks, 362, LEFT_INNER_X, EYE_Y);
        put(landmarks, 263, LEFT_EYE_X, EYE_Y);
        put(landmarks, 159, 0.42f, EYE_Y - lidGap);
        put(landmarks, 145, 0.42f, EYE_Y + lidGap);
        put(landmarks, 158, 0.45f, EYE_Y - lidGap);
        put(landmarks, 153, 0.45f, EYE_Y + lidGap);
        put(landmarks, 386, 0.54f, EYE_Y - lidGap);
        put(landmarks, 374, 0.54f, EYE_Y + lidGap);
        put(landmarks, 387, 0.57f, EYE_Y - lidGap);
        put(landmarks, 373, 0.57f, EYE_Y + lidGap);
        put(landmarks, PoseMetrics.NOSE_TIP, noseX, noseY);
        return new Face(landmarks, Face.LANDMARKS_PER_FACE,
                new float[]{0.4f, 0.35f, 0.6f, 0.65f}, 1f);
    }

    private static void put(float[] landmarks, int index, float x, float y) {
        landmarks[index * Face.LANDMARK_DIM] = x;
        landmarks[index * Face.LANDMARK_DIM + 1] = y;
        landmarks[index * Face.LANDMARK_DIM + 2] = 0f;
    }

    @Test
    public void straightHeadHasZeroYawAndPitch() {
        // Nose tip level with the eyes and midway between them.
        Face straight = face(0.5f, EYE_Y, 0.012f);

        assertEquals(0f, PoseMetrics.yawDegrees(straight), 1e-4f);
        assertEquals(0f, PoseMetrics.pitchDegrees(straight), 1e-4f);
    }

    @Test
    public void noseToTheRightIsAPositiveYaw() {
        Face turned = face(0.6f, EYE_Y + 0.1f, 0.012f);

        // (0.6 - 0.5) / 0.2 * 45 = 22.5 degrees-equivalent.
        assertEquals(22.5f, PoseMetrics.yawDegrees(turned), 1e-3f);
        assertTrue("a right turn must clear the +15 threshold",
                PoseMetrics.yawDegrees(turned) > GuidedCaptureController.YAW_THRESHOLD_DEGREES);
    }

    @Test
    public void noseToTheLeftIsANegativeYaw() {
        Face turned = face(0.4f, EYE_Y + 0.1f, 0.012f);

        assertEquals(-22.5f, PoseMetrics.yawDegrees(turned), 1e-3f);
        assertTrue(PoseMetrics.yawDegrees(turned)
                < -GuidedCaptureController.YAW_THRESHOLD_DEGREES);
    }

    @Test
    public void noseAboveTheEyesLooksUpAndBelowLooksDown() {
        Face up = face(0.5f, 0.3f, 0.012f);
        Face down = face(0.5f, 0.5f, 0.012f);

        assertEquals(-22.5f, PoseMetrics.pitchDegrees(up), 1e-3f);
        assertEquals(22.5f, PoseMetrics.pitchDegrees(down), 1e-3f);
    }

    @Test
    public void eyeAspectRatioDistinguishesOpenFromShut() {
        Face open = face(0.5f, EYE_Y + 0.1f, 0.012f);
        Face shut = face(0.5f, EYE_Y + 0.1f, 0.005f);

        assertEquals(0.30f, PoseMetrics.eyeAspectRatio(open), 1e-3f);
        assertEquals(0.125f, PoseMetrics.eyeAspectRatio(shut), 1e-3f);
        assertTrue(PoseMetrics.eyeAspectRatio(open) > GuidedCaptureController.BLINK_OPEN_EAR);
        assertTrue(PoseMetrics.eyeAspectRatio(shut) < GuidedCaptureController.BLINK_CLOSED_EAR);
    }

    @Test
    public void sampleCarriesPoseAndFaceCount() {
        PoseSample sample = PoseMetrics.sample(face(0.6f, 0.5f, 0.012f), 2);

        assertTrue(sample.facePresent);
        assertEquals(2, sample.faceCount);
        assertEquals(22.5f, sample.yawDegrees, 1e-3f);
        assertEquals(22.5f, sample.pitchDegrees, 1e-3f);
        assertEquals(0.30f, sample.eyeAspectRatio, 1e-3f);
    }

    @Test
    public void aFaceWithoutTheAnchorsDegradesToZeroes() {
        Face sparse = new Face(new float[]{0.5f, 0.5f, 0f}, 1, new float[]{0f, 0f, 1f, 1f}, 0f);

        assertEquals(0f, PoseMetrics.yawDegrees(sparse), 0f);
        assertEquals(0f, PoseMetrics.pitchDegrees(sparse), 0f);
        assertEquals(0f, PoseMetrics.eyeAspectRatio(sparse), 0f);
        assertFalse(PoseMetrics.sample(null, 0).facePresent);
    }

    @Test
    public void largestFaceIsChosenByBoundingBoxArea() {
        Face small = new Face(new float[3], 1, new float[]{0f, 0f, 0.2f, 0.2f}, 1f);
        Face large = new Face(new float[3], 1, new float[]{0f, 0f, 0.5f, 0.5f}, 0.1f);
        Face medium = new Face(new float[3], 1, new float[]{0f, 0f, 0.3f, 0.3f}, 1f);

        assertEquals(large, PoseMetrics.largestFace(Arrays.asList(small, large, medium)));
        assertEquals(medium, PoseMetrics.largestFace(Arrays.asList(small, medium)));
        assertNull(PoseMetrics.largestFace(Collections.<Face>emptyList()));
        assertNull(PoseMetrics.largestFace(null));
    }
}
