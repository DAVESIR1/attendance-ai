/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Yuv420ToArgbConverterTest {

    @Test
    public void convertsBlackIsDark() {
        // Limited-range black: Y=16, U=V=128 → every channel stays at 16.
        int[] out = new int[2 * 2];
        Yuv420ToArgbConverter.convert(
                filled(4, (byte) 16), filled(1, (byte) 128), filled(1, (byte) 128),
                2, 2, 2, 1, 1, out);
        assertEquals(0xFF101010, out[0]);
        assertEquals(0xFF101010, out[3]);
    }

    @Test
    public void convertsWhiteIsBright() {
        // Limited-range white: Y=235, U=V=128 → 0xEB per channel.
        int[] out = new int[4];
        Yuv420ToArgbConverter.convert(
                filled(4, (byte) 235), filled(1, (byte) 128), filled(1, (byte) 128),
                2, 2, 2, 1, 1, out);
        assertEquals(0xFFEBEBEB, out[0]);
        assertEquals(0xFFEBEBEB, out[1]);
        assertEquals(0xFFEBEBEB, out[2]);
        assertEquals(0xFFEBEBEB, out[3]);
    }

    @Test
    public void convertsSaturatedRed() {
        // BT.601 red: Y=81, U=90, V=240 → R=238, G=14, B=14.
        int[] out = new int[4];
        Yuv420ToArgbConverter.convert(
                filled(4, (byte) 81), filled(1, (byte) 90), filled(1, (byte) 240),
                2, 2, 2, 1, 1, out);
        assertEquals(238, (out[0] >> 16) & 0xFF);
        assertEquals(14, (out[0] >> 8) & 0xFF);
        assertEquals(14, out[0] & 0xFF);
        assertEquals(238, (out[3] >> 16) & 0xFF);
    }

    @Test
    public void clampsOutOfRangeChroma() {
        int[] out = new int[1];
        Yuv420ToArgbConverter.convert(
                filled(1, (byte) 255), filled(1, (byte) 255), filled(1, (byte) 255),
                1, 1, 1, 1, 1, out);
        // R = 255 + 1.402·127 = 433 → 255; B = 255 + 1.772·127 = 480 → 255.
        assertEquals(255, (out[0] >> 16) & 0xFF);
        assertEquals(255, out[0] & 0xFF);
        int green = (out[0] >> 8) & 0xFF;
        assertTrue(green >= 0 && green <= 255);
        // Dark chroma must clamp to black, not wrap around.
        Yuv420ToArgbConverter.convert(
                filled(1, (byte) 0), filled(1, (byte) 0), filled(1, (byte) 0),
                1, 1, 1, 1, 1, out);
        assertEquals(0, (out[0] >> 16) & 0xFF);
        assertEquals(0, out[0] & 0xFF);
    }

    @Test
    public void respectsRowPaddingAndInterleavedChroma() {
        // 2×2 image, luminance rows padded to 4 bytes; chroma delivered with
        // pixelStride 2 (NV12-style: each plane buffer holds its own samples
        // at even offsets, exactly as android.media.Image exposes them).
        byte[] y = new byte[]{81, 81, 0, 0, 81, 81, 0, 0};
        byte[] u = new byte[]{90, 0, 90, 0};
        byte[] v = new byte[]{(byte) 240, 0, (byte) 240, 0};
        int[] out = new int[4];
        Yuv420ToArgbConverter.convert(y, u, v, 2, 2, 4, 4, 2, out);
        for (int i = 0; i < 4; i++) {
            assertEquals(238, (out[i] >> 16) & 0xFF);
            assertEquals(14, (out[i] >> 8) & 0xFF);
            assertEquals(14, out[i] & 0xFF);
        }
    }

    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = value;
        }
        return bytes;
    }
}
