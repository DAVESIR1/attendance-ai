/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import java.util.Collection;
import java.util.Map;

/**
 * Cosine-similarity matcher over per-person mean templates. Pure JVM code,
 * unit-tested; both the MobileFaceNet embeddings and the geometric fallback
 * signatures flow through the same matching path.
 */
public final class FaceMatcher {

    /** Best match is this when nothing scores above the threshold. */
    public static final String NO_MATCH_ID = "";

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
     * Matches {@code query} against {@code templates} (personId → mean
     * vector). Returns the single best candidate.
     */
    public static MatchResult match(float[] query, Map<String, float[]> templates,
            float threshold) {
        String bestId = NO_MATCH_ID;
        float bestScore = -1f;
        for (Map.Entry<String, float[]> entry : templates.entrySet()) {
            float score = cosine(query, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestId = entry.getKey();
            }
        }
        boolean accepted = bestId.length() > 0 && bestScore >= threshold;
        return new MatchResult(accepted ? bestId : "", bestScore, accepted);
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