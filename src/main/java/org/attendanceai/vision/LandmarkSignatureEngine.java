/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

/**
 * Experimental fallback embedder: derives a fixed-size geometric signature
 * from the 478 normalized landmarks alone, without any neural network.
 * Used only when no MobileFaceNet model is present (Settings.embedder =
 * "signature"), and clearly experimental — accuracy is far below the
 * learned embeddings.
 */
public final class LandmarkSignatureEngine {

    /** Output dimension of the geometric signature. */
    public static final int DIM = 32;

    public LandmarkSignatureEngine() {
    }

    public int outputDim() {
        return DIM;
    }

    /** Builds a deterministic signature from the face's landmarks. */
    public float[] embed(Face face) {
        float[] out = new float[DIM];
        if (face == null || face.landmarkCount() < 10) {
            return out;
        }
        float cx = face.centerX();
        float cy = face.centerY();
        float w = Math.max(face.width(), 1e-4f);
        float h = Math.max(face.height(), 1e-4f);

        // 1. Global scale-invariant ratios.
        out[0] = w / (w + h);
        out[1] = (face.centerX() - face.minLandmarkX()) / w;
        out[2] = (face.centerY() - face.minLandmarkY()) / h;

        // 2. Offset of a spread of landmark indices from the face centre,
        //    normalised by face size (view-invariant-ish traits).
        int[] indices = new int[]{
            33, 263, 13, 14, 61, 291, 1, 4, 98, 327, 152, 10,
            234, 454, 162, 389, 175, 397, 36, 285, 62, 292, 197, 377,
            0, 16, 24, 105, 297, 138
        };
        int used = 0;
        for (int i = 0; i < indices.length && used + 3 < DIM; i++) {
            int idx = indices[i];
            if (face.landmarkCount() <= idx) {
                continue;
            }
            out[2 + used++] = (face.getX(idx) - cx) / w;
            out[2 + used++] = (face.getY(idx) - cy) / h;
            out[2 + used++] = face.getZ(idx);
        }

        // 3. Fill the remainder with inter-anchor ratios (eyes and mouth).
        if (face.landmarkCount() > Face.RIGHT_EYE_OUTER) {
            float eyeDx = face.getX(Face.LEFT_EYE_OUTER) - face.getX(Face.RIGHT_EYE_OUTER);
            float eyeDy = face.getY(Face.LEFT_EYE_OUTER) - face.getY(Face.RIGHT_EYE_OUTER);
            float eyeAngle = (float) Math.atan2(eyeDy, eyeDx);
            while (used < DIM) {
                out[used++] = (used % 3 == 0) ? eyeAngle : (used % 3 == 1)
                        ? (float) Math.hypot(eyeDx, eyeDy) / w : 0f;
            }
        }
        return out;
    }
}