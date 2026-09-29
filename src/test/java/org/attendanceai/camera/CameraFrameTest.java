/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CameraFrameTest {

    @Test
    public void fromRgbaMapsChannelsCorrectly() {
        byte[] rgba = new byte[4 * 4 * 4];
        // pixel 0: red = 255, green = 128, blue = 0, alpha = 255
        rgba[0] = (byte) 255;
        rgba[1] = (byte) 128;
        rgba[2] = (byte) 0;
        rgba[3] = (byte) 255;
        CameraFrame frame = CameraFrame.fromRgba(rgba, 4, 4, 7L);
        assertEquals(7L, frame.frameTimeMs);
        int pixel = frame.pixel(0, 0);
        assertEquals(255, frame.red(0, 0));
        assertEquals(128, frame.green(0, 0));
        assertEquals(0, frame.blue(0, 0));
        // ARGB packing: A=255, R=255, G=128, B=0
        assertEquals(0xFFFF8000, pixel);
    }

    @Test
    public void rgbaRoundTrips() {
        int[] argb = new int[2 * 2];
        argb[0] = 0xFF112233;
        argb[1] = 0xFF445566;
        argb[2] = 0xFF778899;
        argb[3] = 0xFFAABBCC;
        CameraFrame frame = new CameraFrame(2, 2, argb, 0L);
        byte[] rgba = frame.rgba();
        assertEquals(2 * 2 * 4, rgba.length);
        // pixel (1,0) = 0xFF445566
        assertEquals(0x44, rgba[1 * 4] & 0xFF);
        assertEquals(0x55, rgba[1 * 4 + 1] & 0xFF);
        assertEquals(0x66, rgba[1 * 4 + 2] & 0xFF);
        assertEquals(0xFF, rgba[1 * 4 + 3] & 0xFF);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroSizedFrames() {
        new CameraFrame(0, 480, new int[0], 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooSmallBuffers() {
        new CameraFrame(2, 2, new int[3], 0L);
    }
}