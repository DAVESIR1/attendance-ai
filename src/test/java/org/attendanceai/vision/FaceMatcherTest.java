/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class FaceMatcherTest {

    @Test
    public void identicalVectorsScoreOne() {
        float[] a = {1f, 0f, 1f};
        float score = FaceMatcher.cosine(a, a.clone());
        assertTrue("identical vectors should score ~1, got " + score, score > 0.999f);
    }

    @Test
    public void orthogonalVectorsScoreZero() {
        float score = FaceMatcher.cosine(new float[]{1f, 0f}, new float[]{0f, 2f});
        assertEquals(0f, score, 1e-5f);
    }

    @Test
    public void oppositeVectorsScoreMinusOne() {
        float score = FaceMatcher.cosine(new float[]{1f, 0f}, new float[]{-1f, 0f});
        assertEquals(-1f, score, 1e-5f);
    }

    @Test
    public void lengthMismatchYieldsZero() {
        assertEquals(0f, FaceMatcher.cosine(new float[]{1f}, new float[]{1f, 1f}), 0f);
        assertEquals(0f, FaceMatcher.cosine(null, new float[]{1f}), 0f);
    }

    @Test
    public void meanOfVectorsIsComponentwise() {
        float[] mean = FaceMatcher.mean(Arrays.asList(
                new float[]{1f, 3f}, new float[]{3f, 1f}));
        assertEquals(2f, mean[0], 1e-5f);
        assertEquals(2f, mean[1], 1e-5f);
    }

    @Test
    public void matchAcceptsOnlyAboveThreshold() {
        LinkedHashMap<String, float[]> templates = new LinkedHashMap<String, float[]>();
        templates.put("alice", new float[]{1f, 0f, 0f});
        templates.put("bob", new float[]{0f, 1f, 0f});

        FaceMatcher.MatchResult accepted =
                FaceMatcher.match(new float[]{0.9f, 0.1f, 0f}, templates, 0.8f);
        assertTrue(accepted.accepted);
        assertEquals("alice", accepted.personId);

        FaceMatcher.MatchResult rejected =
                FaceMatcher.match(new float[]{0.5f, 0.5f, 0f}, templates, 0.99f);
        assertTrue(!rejected.accepted);
        assertEquals("", rejected.personId);
    }

    @Test
    public void cosineIsScaleInvariant() {
        float[] small = {1f, 2f, 3f};
        float[] large = {10f, 20f, 30f};
        float score = FaceMatcher.cosine(small, large);
        assertEquals("scaled vectors should have cosine 1", 1f, score, 1e-5f);
    }
}