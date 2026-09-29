/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameRotatorTest {

    // 2 × 3 source, row-major labels:
    //   0 1
    //   2 3
    //   4 5
    private static final int[] SRC = {0, 1, 2, 3, 4, 5};

    @Test
    public void zeroRotationReturnsSameArray() {
        int[] out = FrameRotator.rotateCw(SRC, 2, 3, 0);
        assertSame(SRC, out);
        assertFalse(FrameRotator.swapsDimensions(0));
        assertFalse(FrameRotator.swapsDimensions(180));
        assertTrue(FrameRotator.swapsDimensions(90));
        assertTrue(FrameRotator.swapsDimensions(270));
    }

    @Test
    public void rotate90Clockwise() {
        //   0 1        4 2 0
        //   2 3   →    5 3 1
        //   4 5
        int[] out = FrameRotator.rotateCw(SRC, 2, 3, 90);
        assertArrayEquals(new int[]{4, 2, 0, 5, 3, 1}, out);
        assertEquals(6, out.length); // pixel count unchanged after the swap
    }

    @Test
    public void rotate180ReversesPixels() {
        int[] out = FrameRotator.rotateCw(SRC, 2, 3, 180);
        assertArrayEquals(new int[]{5, 4, 3, 2, 1, 0}, out);
    }

    @Test
    public void rotate270IsCounterClockwiseNinety() {
        //   0 1        1 3 5
        //   2 3   →    0 2 4
        //   4 5
        int[] out = FrameRotator.rotateCw(SRC, 2, 3, 270);
        assertArrayEquals(new int[]{1, 3, 5, 0, 2, 4}, out);
    }

    @Test
    public void rotate90Then270RestoresOriginal() {
        int[] rotated = FrameRotator.rotateCw(SRC, 2, 3, 90);      // 3 × 2
        int[] back = FrameRotator.rotateCw(rotated, 3, 2, 270);    // 2 × 3
        assertArrayEquals(SRC, back);
    }

    @Test
    public void negativeAndOversizedDegreesNormalize() {
        assertArrayEquals(FrameRotator.rotateCw(SRC, 2, 3, 270),
                FrameRotator.rotateCw(SRC, 2, 3, -90));
        assertArrayEquals(SRC, FrameRotator.rotateCw(SRC, 2, 3, 360));
    }
}