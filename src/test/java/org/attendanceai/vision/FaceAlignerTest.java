/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.attendanceai.camera.CameraFrame;

public class FaceAlignerTest {

    private static final float TOL = 1e-2f;

    @Test
    public void transformPreservesAnchors() {
        // A face whose anchors already sit at the canonical destination.
        float[] m = FaceAligner.similarityTransform(
                FaceAligner.DST_LEFT_EYE_X, FaceAligner.DST_LEFT_EYE_Y,
                FaceAligner.DST_RIGHT_EYE_X, FaceAligner.DST_RIGHT_EYE_Y,
                FaceAligner.DST_MOUTH_X, FaceAligner.DST_MOUTH_Y);
        float[] p = FaceAligner.transformPoint(m,
                FaceAligner.DST_LEFT_EYE_X, FaceAligner.DST_LEFT_EYE_Y);
        assertEquals(FaceAligner.DST_LEFT_EYE_X, p[0], TOL);
        assertEquals(FaceAligner.DST_LEFT_EYE_Y, p[1], TOL);
    }

    @Test
    public void transformHandlesOffCentres() {
        // Translate a canonical face by (+0.2, -0.1) and check the transform
        // recovers the canonical anchors.
        float lx = FaceAligner.DST_LEFT_EYE_X + 0.2f;
        float ly = FaceAligner.DST_LEFT_EYE_Y - 0.1f;
        float rx = FaceAligner.DST_RIGHT_EYE_X + 0.2f;
        float ry = FaceAligner.DST_RIGHT_EYE_Y - 0.1f;
        float mx = FaceAligner.DST_MOUTH_X + 0.2f;
        float my = FaceAligner.DST_MOUTH_Y - 0.1f;

        float[] m = FaceAligner.similarityTransform(lx, ly, rx, ry, mx, my);
        float[] out = FaceAligner.transformPoint(m, lx, ly);
        assertEquals(FaceAligner.DST_LEFT_EYE_X, out[0], TOL);
        assertEquals(FaceAligner.DST_LEFT_EYE_Y, out[1], TOL);
    }

    @Test
    public void samplerPicksExactPixel() {
        CameraFrame frame = solidFrame(4, 4, 100, 150, 200);
        float value = FaceAligner.sampleChannel(frame, 1f, 1f, 0);
        assertEquals((100f - 127.5f) / 128f, value, 1e-3f);
        float green = FaceAligner.sampleChannel(frame, 1f, 1f, 1);
        assertEquals((150f - 127.5f) / 128f, green, 1e-3f);
    }

    @Test
    public void samplerClampsOutOfBounds() {
        CameraFrame frame = solidFrame(4, 4, 10, 20, 30);
        // Coordinates far outside should clamp to nearest edge (no crash,
        // bounded result).
        float value = FaceAligner.sampleChannel(frame, 1000f, 1000f, 2);
        assertTrue(value >= -1f && value <= 1f);
    }

    @Test
    public void alignedTensorIsInterleavedRgb() {
        // A solid red frame: with interleaved NHWC layout every pixel group
        // must be (R, G, B) = (255, 0, 0) normalized. A planar layout would
        // place three identical R values first (caught by out[1] == out[0]).
        CameraFrame frame = solidFrame(64, 64, 255, 0, 0);
        Face face = faceWithAnchors(0.25f, 0.35f, 0.75f, 0.35f, 0.5f, 0.75f);
        float[] out = new float[8 * 8 * 3];
        assertTrue(FaceAligner.fillAlignedTensor(face, frame, 8, 8, out));

        float red = (255f - 127.5f) / 128f;
        float off = (0f - 127.5f) / 128f;
        assertEquals(red, out[0], TOL);
        assertEquals(off, out[1], TOL);
        assertEquals(off, out[2], TOL);
        // Pixel 1 continues the interleaved pattern.
        assertEquals(red, out[3], TOL);
        assertEquals(off, out[4], TOL);
        assertEquals(off, out[5], TOL);
    }

    @Test
    public void tooSmallFaceIsRejected() {
        CameraFrame frame = solidFrame(640, 480, 255, 255, 255);
        Face tiny = faceWithAnchors(0.49f, 0.49f, 0.51f, 0.49f, 0.5f, 0.5f);
        float[] out = new float[112 * 112 * 3];
        assertFalse(FaceAligner.fillAlignedTensor(tiny, frame, 112, 112, out));
    }

    private static CameraFrame solidFrame(int w, int h, int r, int g, int b) {
        int[] argb = new int[w * h];
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;
        for (int i = 0; i < argb.length; i++) {
            argb[i] = color;
        }
        return new CameraFrame(w, h, argb, 0L);
    }

    /** Builds a Face whose eye/mouth anchors sit around the given points. */
    private static Face faceWithAnchors(float lx, float ly, float rx, float ry,
            float mx, float my) {
        int n = Face.LANDMARKS_PER_FACE;
        float[] landmarks = new float[n * 3];
        put(landmarks, Face.LEFT_EYE_OUTER, lx, ly, 0f);
        put(landmarks, Face.RIGHT_EYE_OUTER, rx, ry, 0f);
        put(landmarks, Face.MOUTH_CENTER_TOP, mx, my - 0.01f, 0f);
        put(landmarks, Face.MOUTH_CENTER_BOTTOM, mx, my + 0.01f, 0f);
        return new Face(landmarks, n,
                new float[]{lx - 0.02f, ly - 0.02f, rx + 0.02f, my + 0.02f}, 0.9f);
    }

    private static void put(float[] l, int index, float x, float y, float z) {
        l[index * 3] = x;
        l[index * 3 + 1] = y;
        l[index * 3 + 2] = z;
    }
}