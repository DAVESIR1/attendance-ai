/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

/**
 * One camera frame in ARGB-8888 (0xAARRGGBB) pixel order, converted off the
 * capture thread. Pure value object — no Android imports — so frame sizing
 * logic can be unit-tested on a host JVM.
 */
public final class CameraFrame {

    public final int width;
    public final int height;
    private final int[] argb;
    /** Wall-clock ms (SystClock.uptimeMillis on device). */
    public final long frameTimeMs;
    private byte[] rgbaCache;

    public CameraFrame(int width, int height, int[] argb, long frameTimeMs) {
        if (argb == null || argb.length < width * height) {
            throw new IllegalArgumentException("frame buffer too small");
        }
        this.width = width;
        this.height = height;
        this.argb = argb;
        this.frameTimeMs = frameTimeMs;
    }

    public int[] argb() {
        return argb;
    }

    public int pixel(int x, int y) {
        return argb[y * width + x];
    }

    /** Red channel of pixel (x, y) in 0..255. */
    public int red(int x, int y) {
        return (pixel(x, y) >> 16) & 0xFF;
    }

    public int green(int x, int y) {
        return (pixel(x, y) >> 8) & 0xFF;
    }

    public int blue(int x, int y) {
        return pixel(x, y) & 0xFF;
    }

    /** Lazily builds an RGBA byte buffer (r,g,b,a per pixel) for MediaPipe. */
    public byte[] rgba() {
        byte[] cached = rgbaCache;
        if (cached == null) {
            cached = new byte[width * height * 4];
            for (int i = 0; i < width * height; i++) {
                int p = argb[i];
                cached[i * 4] = (byte) ((p >> 16) & 0xFF);
                cached[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);
                cached[i * 4 + 2] = (byte) (p & 0xFF);
                cached[i * 4 + 3] = (byte) ((p >> 24) & 0xFF);
            }
            rgbaCache = cached;
        }
        return cached;
    }

    /**
     * Builds a frame from an RGBA byte stream (the byte order delivered by an
     * android.media.ImageReader in ImageFormat.FLEX_RGBA_8888).
     */
    public static CameraFrame fromRgba(byte[] rgba, int width, int height, long frameTimeMs) {
        int[] out = new int[width * height];
        int pixels = width * height;
        for (int i = 0; i < pixels; i++) {
            int r = rgba[i * 4] & 0xFF;
            int g = rgba[i * 4 + 1] & 0xFF;
            int b = rgba[i * 4 + 2] & 0xFF;
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return new CameraFrame(width, height, out, frameTimeMs);
    }
}