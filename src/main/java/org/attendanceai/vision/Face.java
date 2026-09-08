/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

/**
 * One detected face: 478 normalized (x, y, z) MediaPipe landmarks plus a
 * bounding box and an estimated quality in 0..1. Pure value object — no
 * Android imports — so geometry helpers are unit-testable on a host JVM.
 *
 * Anchor landmark indices follow the MediaPipe FaceMesh layout used by the
 * Face Landmarker task (tesselation). The FaceAligner only needs a handful of
 * stable facial anchors, so this class tolerates models with fewer landmarks
 * by falling back to bbox-relative anchors.
 */
public final class Face {

    public static final int LANDMARKS_PER_FACE = 478;
    public static final int LANDMARK_DIM = 3;

    // Anchor indices used for alignment (standard FaceMesh indices).
    public static final int LEFT_EYE_OUTER = 33;
    public static final int RIGHT_EYE_OUTER = 263;
    public static final int MOUTH_CENTER_TOP = 13;
    public static final int MOUTH_CENTER_BOTTOM = 14;

    private final float[] landmarks; // x,y,z triples
    private final int landmarkCount; // number of VALID triples (may be < 478)
    private final float bboxMinX;
    private final float bboxMinY;
    private final float bboxMaxX;
    private final float bboxMaxY;
    private final float quality;

    public Face(float[] landmarks, int landmarkCount, float[] bbox, float quality) {
        this.landmarks = landmarks;
        this.landmarkCount = landmarkCount;
        this.bboxMinX = bbox[0];
        this.bboxMinY = bbox[1];
        this.bboxMaxX = bbox[2];
        this.bboxMaxY = bbox[3];
        this.quality = quality;
    }

    public float[] landmarks() {
        return landmarks;
    }

    /** Number of valid landmark triples stored. */
    public int landmarkCount() {
        return landmarkCount;
    }

    public float getX(int index) {
        return landmarks[index * LANDMARK_DIM];
    }

    public float getY(int index) {
        return landmarks[index * LANDMARK_DIM + 1];
    }

    public float getZ(int index) {
        return landmarks[index * LANDMARK_DIM + 2];
    }

    public float bboxMinX() {
        return bboxMinX;
    }

    public float bboxMinY() {
        return bboxMinY;
    }

    public float bboxMaxX() {
        return bboxMaxX;
    }

    public float bboxMaxY() {
        return bboxMaxY;
    }

    public float width() {
        return bboxMaxX - bboxMinX;
    }

    public float height() {
        return bboxMaxY - bboxMinY;
    }

    public float centerX() {
        return (bboxMinX + bboxMaxX) * 0.5f;
    }

    public float centerY() {
        return (bboxMinY + bboxMaxY) * 0.5f;
    }

    /** 0..1 heuristic: landmark completeness × face size. */
    public float quality() {
        return quality;
    }

    /** True when the anchor set needed by {@link FaceAligner} is present. */
    public boolean hasAlignmentAnchors() {
        int maxIndex = Math.max(Math.max(LEFT_EYE_OUTER, RIGHT_EYE_OUTER),
                Math.max(MOUTH_CENTER_TOP, MOUTH_CENTER_BOTTOM));
        return landmarkCount > maxIndex;
    }

    /** Left-most x among valid landmarks. */
    public float minLandmarkX() {
        float minX = Float.MAX_VALUE;
        for (int i = 0; i < landmarkCount; i++) {
            minX = Math.min(minX, getX(i));
        }
        return minX;
    }

    public float maxLandmarkX() {
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < landmarkCount; i++) {
            maxX = Math.max(maxX, getX(i));
        }
        return maxX;
    }

    public float minLandmarkY() {
        float minY = Float.MAX_VALUE;
        for (int i = 0; i < landmarkCount; i++) {
            minY = Math.min(minY, getY(i));
        }
        return minY;
    }

    public float maxLandmarkY() {
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < landmarkCount; i++) {
            maxY = Math.max(maxY, getY(i));
        }
        return maxY;
    }
}