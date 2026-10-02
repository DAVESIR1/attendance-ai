/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.enrol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.attendanceai.vision.FaceMatcher;

/**
 * The two enrolment warnings the guided flow must raise before saving:
 *
 *  1. a duplicate NAME (case-insensitive, trimmed) already in the roster —
 *     a soft warning the user may continue past; and
 *  2. a suspiciously similar FACE (best cosine similarity above
 *     {@link #SIMILARITY_WARNING_THRESHOLD} against any existing person's
 *     stored embeddings) — also a soft warning (save anyway / cancel).
 *
 * Pure JVM code (no Android/Room imports) so the message builders and the
 * similarity boundary are unit-tested on the host. Storage-side types are
 * described by {@link KnownPerson} rather than Room entities so this class
 * stays framework-free.
 */
public final class EnrolmentWarnings {

    /** Best similarity above this looks like the same person already enrolled. */
    public static final float SIMILARITY_WARNING_THRESHOLD = 0.85f;

    private EnrolmentWarnings() {
    }

    /**
     * The existing name that matches {@code candidateName} ignoring case and
     * surrounding whitespace, or null. A blank candidate never matches.
     */
    public static String duplicateName(String candidateName, Collection<String> existingNames) {
        if (candidateName == null || existingNames == null) {
            return null;
        }
        String candidate = candidateName.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        for (String existing : existingNames) {
            if (existing != null && existing.trim().equalsIgnoreCase(candidate)) {
                return existing.trim();
            }
        }
        return null;
    }

    /**
     * Warning text for the duplicate-name dialog — a soft "continue anyway?"
     * rather than a hard block, as the spec requires.
     */
    public static String duplicateNameMessage(String matchedName) {
        String who = matchedName == null || matchedName.trim().isEmpty()
                ? "this name" : matchedName.trim();
        return "\"" + who + "\" already exists — continue anyway?";
    }

    /**
     * The existing person whose stored embeddings most resemble the new ones,
     * when that best score is strictly above {@code threshold}; otherwise null.
     * Comparison uses cosine similarity — the same metric the live matcher uses
     * — so the warning and matching never disagree about what "similar" means.
     */
    public static SimilarFace findSimilarFace(List<float[]> newEmbeddings,
            List<KnownPerson> known, float threshold) {
        SimilarFace best = null;
        if (newEmbeddings == null || known == null) {
            return null;
        }
        for (KnownPerson person : known) {
            if (person == null) {
                continue;
            }
            float score = bestScore(newEmbeddings, person.embeddings());
            if (score > threshold && (best == null || score > best.score)) {
                best = new SimilarFace(person.personId(), person.name(), score);
            }
        }
        return best;
    }

    /**
     * Best cosine score between each new embedding and each of a person's
     * stored embeddings; 0 when either side is empty.
     */
    public static float bestScore(List<float[]> candidates, List<float[]> stored) {
        float best = 0f;
        if (candidates == null || stored == null) {
            return 0f;
        }
        for (float[] candidate : candidates) {
            if (candidate == null || candidate.length == 0) {
                continue;
            }
            for (float[] known : stored) {
                if (known == null || known.length == 0) {
                    continue;
                }
                float score = FaceMatcher.cosine(candidate, known);
                if (score > best) {
                    best = score;
                }
            }
        }
        return best;
    }

    /** Warning text for the similar-face dialog. */
    public static String similarFaceMessage(SimilarFace similar) {
        if (similar == null) {
            return "";
        }
        String who = similar.personName == null || similar.personName.trim().isEmpty()
                ? "an existing person" : similar.personName.trim();
        return "this looks similar to " + who + " ("
                + String.format(java.util.Locale.US, "%.2f", similar.score)
                + ") — save anyway?";
    }

    /** One existing person's identity plus the embeddings to compare against. */
    public interface KnownPerson {
        String personId();

        String name();

        List<float[]> embeddings();
    }

    /** A simple immutable implementation of {@link KnownPerson}. */
    public static final class SimpleKnownPerson implements KnownPerson {
        private final String personId;
        private final String name;
        private final List<float[]> embeddings;

        public SimpleKnownPerson(String personId, String name, List<float[]> embeddings) {
            this.personId = personId;
            this.name = name;
            this.embeddings = embeddings == null
                    ? new ArrayList<float[]>() : new ArrayList<float[]>(embeddings);
        }

        @Override
        public String personId() {
            return personId;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<float[]> embeddings() {
            return embeddings;
        }
    }

    /** The person a new enrolment most resembles, with the score. */
    public static final class SimilarFace {
        public final String personId;
        public final String personName;
        public final float score;

        public SimilarFace(String personId, String personName, float score) {
            this.personId = personId;
            this.personName = personName;
            this.score = score;
        }
    }
}