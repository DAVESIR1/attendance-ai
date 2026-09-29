/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

/**
 * Converts one YUV_420_888 camera frame (the only capture format guaranteed
 * by Camera2 on every device) into packed ARGB pixels. Pure JVM code — no
 * android.* imports — so the arithmetic is unit-tested on the host.
 *
 * Chroma is sampled at half resolution, planes may carry row padding
 * (rowStride &gt; width) and chroma bytes may be interleaved (pixelStride 2,
 * as NV12/NV21 deliver them), exactly as android.media.Image exposes them.
 *
 * Uses the standard limited-range BT.601 matrix:
 *   R = Y + 1.402 (V − 128)
 *   G = Y − 0.344136 (U − 128) − 0.714136 (V − 128)
 *   B = Y + 1.772 (U − 128)
 */
public final class Yuv420ToArgbConverter {

    private Yuv420ToArgbConverter() {
    }

    /**
     * @param yPlane        luminance bytes
     * @param uPlane        chroma-U bytes
     * @param vPlane        chroma-V bytes
     * @param width         image width in pixels
     * @param height        image height in pixels
     * @param yRowStride    bytes between luminance rows
     * @param uvRowStride   bytes between chroma rows
     * @param uvPixelStride bytes between neighbouring U/V samples
     * @param out           ARGB destination (length ≥ width·height, reused)
     */
    public static void convert(byte[] yPlane, byte[] uPlane, byte[] vPlane,
            int width, int height, int yRowStride, int uvRowStride,
            int uvPixelStride, int[] out) {
        for (int y = 0; y < height; y++) {
            int yRow = y * yRowStride;
            int uvRow = (y >> 1) * uvRowStride;
            int outRow = y * width;
            for (int x = 0; x < width; x++) {
                int yValue = yPlane[yRow + x] & 0xFF;
                int uvOffset = uvRow + (x >> 1) * uvPixelStride;
                int uValue = (uPlane[uvOffset] & 0xFF) - 128;
                int vValue = (vPlane[uvOffset] & 0xFF) - 128;

                int r = Math.round(yValue + 1.402f * vValue);
                int g = Math.round(yValue - 0.344136f * uValue - 0.714136f * vValue);
                int b = Math.round(yValue + 1.772f * uValue);
                out[outRow + x] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}
