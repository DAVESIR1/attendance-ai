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
import java.util.List;
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
        LinkedHashMap<String, List<float[]>> templates =
                new LinkedHashMap<String, List<float[]>>();
        templates.put("alice", Arrays.asList(new float[]{1f, 0f, 0f}));
        templates.put("bob", Arrays.asList(new float[]{0f, 1f, 0f}));

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
    public void multiEmbeddingMatchUsesTheBestStoredEmbeddingPerPerson() {
        // Alice's stored set mixes a frontal capture that does not resemble the
        // query with a side capture that does; only the best one may be used.
        LinkedHashMap<String, List<float[]>> templates =
                new LinkedHashMap<String, List<float[]>>();
        templates.put("alice", Arrays.asList(
                new float[]{1f, 0f, 0f},      // different pose: cosine 0
                new float[]{0.2f, 0.98f, 0f}, // best match: cosine ~0.98
                new float[]{0f, 0f, 1f}));    // orthogonal: cosine 0
        templates.put("bob", Arrays.asList(new float[]{0f, 0f, 1f}));

        FaceMatcher.MatchResult result =
                FaceMatcher.match(new float[]{0f, 1f, 0f}, templates, 0.9f);

        assertTrue("the best embedding of a person must win", result.accepted);
        assertEquals("alice", result.personId);
        assertEquals(0.98f, result.score, 0.01f);
    }

    @Test
    public void aPersonIsScoredByTheirBestEmbeddingNotTheirFirst() {
        List<float[]> alice = Arrays.asList(
                new float[]{0f, 0f, 1f}, new float[]{1f, 0f, 0f});

        assertEquals(1f, FaceMatcher.bestScore(new float[]{1f, 0f, 0f}, alice), 1e-5f);
    }

    @Test
    public void aPersonWithoutEmbeddingsNeverWins() {
        LinkedHashMap<String, List<float[]>> templates =
                new LinkedHashMap<String, List<float[]>>();
        templates.put("empty", new java.util.ArrayList<float[]>());
        templates.put("bob", Arrays.asList(new float[]{0f, 1f, 0f}));

        FaceMatcher.MatchResult result =
                FaceMatcher.match(new float[]{0f, 1f, 0f}, templates, 0.5f);

        assertEquals("bob", result.personId);
        assertEquals(FaceMatcher.NO_SCORE,
                FaceMatcher.bestScore(new float[]{1f, 0f}, new java.util.ArrayList<float[]>()),
                0f);
    }

    @Test
    public void bestScoreIsScaleInvariantAndToleratesBlanks() {
        assertEquals(1f, FaceMatcher.bestScore(new float[]{1f, 2f},
                Arrays.asList(new float[]{10f, 20f})), 1e-5f);
        assertEquals(FaceMatcher.NO_SCORE,
                FaceMatcher.bestScore(new float[]{1f}, null), 0f);
    }

    @Test
    public void cosineIsScaleInvariant() {
        float[] small = {1f, 2f, 3f};
        float[] large = {10f, 20f, 30f};
        float score = FaceMatcher.cosine(small, large);
        assertEquals("scaled vectors should have cosine 1", 1f, score, 1e-5f);
    }
}