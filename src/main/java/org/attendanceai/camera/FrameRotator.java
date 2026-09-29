/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

/**
 * Clockwise rotation of ARGB camera frames. Pure JVM code — no android.*
 * imports — so the index math is unit-tested on the host.
 *
 * Camera2 delivers frames in sensor orientation; on this device the front
 * camera reports SENSOR_ORIENTATION=270, so portrait frames arrive sideways
 * and face detection would see rotated faces. The backend rotates frames to
 * upright before they reach the pipeline.
 */
public final class FrameRotator {

    private FrameRotator() {
    }

    /**
     * Rotates a {@code width × height} ARGB image clockwise by
     * {@code degrees} (rounded to the nearest multiple of 90; multiples of
     * 360 or non-quarter turns return the source array unchanged).
     *
     * @return the rotated pixels (a new array for 90/180/270, else {@code src})
     */
    public static int[] rotateCw(int[] src, int width, int height, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) {
            return src;
        }
        int[] out = new int[width * height];
        if (d == 90) {
            // (x, y) → (height − 1 − y, x); destination is height × width.
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[x * height + (height - 1 - y)] = src[y * width + x];
                }
            }
            return out;
        }
        if (d == 180) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[(height - 1 - y) * width + (width - 1 - x)] = src[y * width + x];
                }
            }
            return out;
        }
        if (d == 270) {
            // (x, y) → (y, width − 1 − x); destination is height × width.
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[(width - 1 - x) * height + y] = src[y * width + x];
                }
            }
            return out;
        }
        return src;
    }

    /** True when the given rotation swaps the frame's width and height. */
    public static boolean swapsDimensions(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return d == 90 || d == 270;
    }
}
