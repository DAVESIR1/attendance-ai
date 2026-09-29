/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import org.attendanceai.camera.CameraFrame;

/**
 * Facial alignment: maps the detected face onto a canonical crop using a
 * 2-D similarity transform fitted on three stable anchors — the left and
 * right eye corners and the mouth centre (FaceMesh indices). The output is
 * a float tensor of shape {h, w, 3} with channel values in [-1, 1], exactly
 * what MobileFaceNet-style embedding models expect.
 *
 * The mathematics are pure JVM code (no android.* imports) and are covered
 * by unit tests; only {@code sampleInto} touches a {@link CameraFrame}.
 */
public final class FaceAligner {

    /** Destination anchors, normalised to the output crop (facenet layout). */
    public static final float DST_LEFT_EYE_X = 0.3199f;
    public static final float DST_LEFT_EYE_Y = 0.3370f;
    public static final float DST_RIGHT_EYE_X = 0.6812f;
    public static final float DST_RIGHT_EYE_Y = 0.3370f;
    public static final float DST_MOUTH_X = 0.5f;
    public static final float DST_MOUTH_Y = 0.7460f;

    /** Face too small (relative to frame diagonal) to trust alignment. */
    public static final float MIN_FACE_FRACTION = 0.12f;

    private FaceAligner() {
    }

    /**
     * Fits a 2-D similarity transform u = (a·x + b·y + c, d·x + e·y + f)
     * mapping the three source anchors onto the three destination anchors in
     * the least-squares sense (Umeyama/Forstner derivation, without the
     * reflection case — faces are not mirrored).
     *
     * @return float[6] {a, b, c, d, e, f}
     */
    public static float[] similarityTransform(
            float srcLx, float srcLy, float srcRx, float srcRy,
            float srcMx, float srcMy) {
        float[] sx = new float[]{srcLx, srcRx, srcMx};
        float[] sy = new float[]{srcLy, srcRy, srcMy};
        float[] dx = new float[]{DST_LEFT_EYE_X, DST_RIGHT_EYE_X, DST_MOUTH_X};
        float[] dy = new float[]{DST_LEFT_EYE_Y, DST_RIGHT_EYE_Y, DST_MOUTH_Y};

        float srcCx = (sx[0] + sx[1] + sx[2]) / 3f;
        float srcCy = (sy[0] + sy[1] + sy[2]) / 3f;
        float dstCx = (dx[0] + dx[1] + dx[2]) / 3f;
        float dstCy = (dy[0] + dy[1] + dy[2]) / 3f;

        float sxx = 0f, sxy = 0f, syx = 0f, syy = 0f, ssum = 0f;
        for (int i = 0; i < 3; i++) {
            float px = sx[i] - srcCx;
            float py = sy[i] - srcCy;
            float qx = dx[i] - dstCx;
            float qy = dy[i] - dstCy;
            sxx += px * qx;
            sxy += px * qy;
            syx += py * qx;
            syy += py * qy;
            ssum += px * px + py * py;
        }

        float a = sxx + syy;                // Σ p·q
        float b = sxy - syx;                // Σ p×q
        float magSq = a * a + b * b;
        float scale = ssum > 1e-12f ? (float) Math.sqrt(magSq) / ssum : 0f;
        float cosT = magSq > 1e-20f ? a / (float) Math.sqrt(magSq) : 1f;
        float sinT = magSq > 1e-20f ? b / (float) Math.sqrt(magSq) : 0f;

        // u = scale·(cosθ·x − sinθ·y) + c ; v = scale·(sinθ·x + cosθ·y) + f
        float ka = scale * cosT;
        float kb = -scale * sinT;
        float kd = scale * sinT;
        float ke = scale * cosT;
        float kc = dstCx - ka * srcCx - kb * srcCy;
        float kf = dstCy - kd * srcCx - ke * srcCy;
        return new float[]{ka, kb, kc, kd, ke, kf};
    }

    /** Applies the transform matrix m={a,b,c,d,e,f} to (x,y). */
    public static float[] transformPoint(float[] m, float x, float y) {
        return new float[]{m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]};
    }

    /**
     * Samples the face region of {@code frame} into a float tensor of shape
     * {outH, outW, 3} laid out row-major with the RGB channels interleaved per
     * pixel (TFLite NHWC) and channel values in [-1, 1]
     * (pixel = (v − 127.5) / 128). Returns false when the face is untrustable
     * (missing anchors or too small relative to the frame).
     */
    public static boolean fillAlignedTensor(Face face, CameraFrame frame,
            int outWidth, int outHeight, float[] out) {
        if (out == null || out.length < outWidth * outHeight * 3 || !face.hasAlignmentAnchors()) {
            return false;
        }
        float frameDiag = (float) Math.hypot(frame.width, frame.height);
        float faceDiag = (float) Math.hypot(face.width() * frame.width,
                face.height() * frame.height);
        if (faceDiag / frameDiag < MIN_FACE_FRACTION) {
            return false;
        }

        float lx = face.getX(Face.LEFT_EYE_OUTER) * frame.width;
        float ly = face.getY(Face.LEFT_EYE_OUTER) * frame.height;
        float rx = face.getX(Face.RIGHT_EYE_OUTER) * frame.width;
        float ry = face.getY(Face.RIGHT_EYE_OUTER) * frame.height;
        float mx = ((face.getX(Face.MOUTH_CENTER_TOP) + face.getX(Face.MOUTH_CENTER_BOTTOM)) * 0.5f)
                * frame.width;
        float my = ((face.getY(Face.MOUTH_CENTER_TOP) + face.getY(Face.MOUTH_CENTER_BOTTOM)) * 0.5f)
                * frame.height;

        float[] transform = similarityTransform(lx, ly, rx, ry, mx, my);

        for (int oy = 0; oy < outHeight; oy++) {
            float ny = oy / (float) (outHeight - 1);
            for (int ox = 0; ox < outWidth; ox++) {
                float nx = ox / (float) (outWidth - 1);
                // Inverse map: source = M⁻¹(dst). For a similarity transform
                // the inverse has the same rotation and 1/scale.
                float sx = inverseX(transform, nx, ny);
                float sy = inverseY(transform, nx, ny);
                float srcX = sx * (frame.width - 1);
                float srcY = sy * (frame.height - 1);
                // Interleaved RGB per pixel. TFLite input tensors are NHWC
                // ({batch, height, width, 3}) — the three channels sit next to
                // each other in memory, not in separate planes.
                int index = (oy * outWidth + ox) * 3;
                out[index] = sampleChannel(frame, srcX, srcY, 0);
                out[index + 1] = sampleChannel(frame, srcX, srcY, 1);
                out[index + 2] = sampleChannel(frame, srcX, srcY, 2);
            }
        }
        return true;
    }

    /** Inverse of the forward similarity transform (x' = Ax + By + C). */
    private static float inverseX(float[] m, float x, float y) {
        float a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5];
        float det = a * e - b * d;
        if (Math.abs(det) < 1e-9f) {
            return c;
        }
        return ((x - c) * e - (y - f) * b) / det;
    }

    private static float inverseY(float[] m, float x, float y) {
        float a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5];
        float det = a * e - b * d;
        if (Math.abs(det) < 1e-9f) {
            return f;
        }
        return (a * (y - f) - d * (x - c)) / det;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * Bilinear-samples one channel of the frame at fractional pixel
     * coordinates. channel: 0 = red, 1 = green, 2 = blue.
     */
    public static float sampleChannel(CameraFrame frame, float x, float y, int channel) {
        float x0 = (float) Math.floor(x);
        float y0 = (float) Math.floor(y);
        float tx = x - x0;
        float ty = y - y0;
        int ix0 = (int) clamp(x0, 0, frame.width - 1);
        int iy0 = (int) clamp(y0, 0, frame.height - 1);
        int ix1 = (int) clamp(x0 + 1, 0, frame.width - 1);
        int iy1 = (int) clamp(y0 + 1, 0, frame.height - 1);

        int c00 = channelValue(frame, ix0, iy0, channel);
        int c10 = channelValue(frame, ix1, iy0, channel);
        int c01 = channelValue(frame, ix0, iy1, channel);
        int c11 = channelValue(frame, ix1, iy1, channel);

        float top = c00 + tx * (c10 - c00);
        float bottom = c01 + tx * (c11 - c01);
        float value = top + ty * (bottom - top);
        return (value - 127.5f) / 128.0f;
    }

    private static int channelValue(CameraFrame frame, int x, int y, int channel) {
        switch (channel) {
            case 0:
                return frame.red(x, y);
            case 1:
                return frame.green(x, y);
            default:
                return frame.blue(x, y);
        }
    }
}