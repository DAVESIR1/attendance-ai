/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Cosine-similarity matcher over per-person embedding sets. Pure JVM code,
 * unit-tested; both the MobileFaceNet embeddings and the geometric fallback
 * signatures flow through the same matching path.
 *
 * A person may hold several embeddings (the guided multi-angle enrolment stores
 * one per captured pose). Matching therefore compares the live face against
 * EVERY stored embedding of every person and keeps the best score per person —
 * a side-on capture that scores 0.9 must not be hidden behind a frontal capture
 * that scores 0.4.
 */
public final class FaceMatcher {

    /** Best match is this when nothing scores above the threshold. */
    public static final String NO_MATCH_ID = "";

    /**
     * Score floor used before any comparison. Cosine similarity can legitimately
     * be as low as -1, so "no embeddings at all" must start below that.
     */
    public static final float NO_SCORE = -2f;


    private FaceMatcher() {
    }

    /** Cosine similarity of two equal-length vectors, l2 normalised. */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0f;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * (double) a[i];
            nb += b[i] * (double) b[i];
        }
        double denom = Math.sqrt(na * nb);
        return denom < 1e-12 ? 0f : (float) (dot / denom);
    }

    /** Component-wise mean of the vectors (used to build the template). */
    public static float[] mean(Collection<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return new float[0];
        }
        int size = 0;
        for (float[] v : vectors) {
            size = Math.max(size, v == null ? 0 : v.length);
        }
        float[] out = new float[size];
        double[] acc = new double[size];
        int count = 0;
        for (float[] v : vectors) {
            if (v == null || v.length != size) {
                continue;
            }
            for (int i = 0; i < size; i++) {
                acc[i] += v[i];
            }
            count++;
        }
        if (count == 0) {
            return new float[0];
        }
        for (int i = 0; i < size; i++) {
            out[i] = (float) (acc[i] / count);
        }
        return out;
    }

    /**
     * Best cosine score between {@code query} and any of one person's stored
     * embeddings. Returns {@link #NO_SCORE} when the person has none, so an
     * empty entry can never beat a real (even negative) score.
     */
    public static float bestScore(float[] query, List<float[]> templates) {
        float best = NO_SCORE;
        if (templates == null) {
            return best;
        }
        for (float[] template : templates) {
            float score = cosine(query, template);
            if (score > best) {
                best = score;
            }
        }
        return best;
    }

    /**
     * Matches {@code query} against per-person embedding sets
     * (personId → all stored embeddings). Each person is scored by their
     * best-matching embedding; the single best person is returned.
     */
    public static MatchResult match(float[] query, Map<String, ? extends List<float[]>> templates,
            float threshold) {
        String bestId = NO_MATCH_ID;
        float topScore = NO_SCORE;
        for (Map.Entry<String, ? extends List<float[]>> entry : templates.entrySet()) {
            float score = bestScore(query, entry.getValue());
            if (score > topScore) {
                topScore = score;
                bestId = entry.getKey();
            }
        }
        boolean accepted = bestId.length() > 0 && topScore >= threshold;
        return new MatchResult(accepted ? bestId : "", topScore, accepted);
    }

    /** Result of one matcher query. Empty id means "no acceptable match". */
    public static final class MatchResult {
        public final String personId;
        public final float score;
        public final boolean accepted;

        public MatchResult(String personId, float score, boolean accepted) {
            this.personId = personId;
            this.score = score;
            this.accepted = accepted;
        }
    }
}