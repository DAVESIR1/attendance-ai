/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.enrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The two soft warnings of the guided flow: duplicate name (case-insensitive,
 * trimmed) and similar face (best cosine strictly above 0.85).
 */
public class EnrolmentWarningsTest {

    private static List<String> roster(String... names) {
        return new ArrayList<String>(Arrays.asList(names));
    }

    // ------------------------------------------------- duplicate name gate

    @Test
    public void exactDuplicateNameIsReported() {
        assertEquals("Priya", EnrolmentWarnings.duplicateName("Priya",
                roster("Amit", "Priya", "Ravi")));
    }

    @Test
    public void duplicateNameIgnoresCaseAndSurroundingSpace() {
        assertEquals("Priya", EnrolmentWarnings.duplicateName("  pRiYa ",
                roster("Amit", "  Priya  ")));
    }

    @Test
    public void distinctNamesAreNotFlagged() {
        assertNull(EnrolmentWarnings.duplicateName("Priyanka", roster("Priya")));
        assertNull(EnrolmentWarnings.duplicateName("Bob", roster("Amit", "Ravi")));
    }

    @Test
    public void blankCandidateNeverMatches() {
        assertNull(EnrolmentWarnings.duplicateName("   ", roster("Priya")));
        assertNull(EnrolmentWarnings.duplicateName(null, roster("Priya")));
        assertNull(EnrolmentWarnings.duplicateName("Priya", null));
    }

    @Test
    public void duplicateMessageWarnsButAllowsContinuing() {
        String message = EnrolmentWarnings.duplicateNameMessage("Priya");

        assertTrue(message.contains("Priya"));
        assertTrue(message.contains("already exists"));
        assertTrue(message.contains("continue anyway"));
    }

    // -------------------------------------------------- similar face gate

    /** A unit vector whose cosine similarity with {1, 0} is exactly {@code cos}. */
    private static float[] atCos(double cos) {
        return new float[]{(float) cos, (float) Math.sqrt(1.0 - cos * cos)};
    }

    private static List<EnrolmentWarnings.KnownPerson> known(
            String id, String name, float[]... embeddings) {
        List<EnrolmentWarnings.KnownPerson> people =
                new ArrayList<EnrolmentWarnings.KnownPerson>();
        people.add(new EnrolmentWarnings.SimpleKnownPerson(id, name,
                new ArrayList<float[]>(Arrays.asList(embeddings))));
        return people;
    }

    @Test
    public void similarityExactlyAtTheThresholdDoesNotWarn() {
        List<float[]> captured = Arrays.asList(new float[]{1f, 0f});

        EnrolmentWarnings.SimilarFace similar = EnrolmentWarnings.findSimilarFace(
                captured, known("person-1", "Priya", atCos(0.85)),
                EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD);

        assertNull("0.85 must not warn — the rule is 'exceeds 0.85'", similar);
    }

    @Test
    public void similarityJustAboveTheThresholdWarns() {
        List<float[]> captured = Arrays.asList(new float[]{1f, 0f});

        EnrolmentWarnings.SimilarFace similar = EnrolmentWarnings.findSimilarFace(
                captured, known("person-1", "Priya", atCos(0.86)),
                EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD);

        assertNotNull(similar);
        assertEquals("person-1", similar.personId);
        assertEquals("Priya", similar.personName);
        assertEquals(0.86f, similar.score, 1e-4f);
    }

    @Test
    public void bestEmbeddingWinsForAMultiAnglePerson() {
        // The person's first four poses do not resemble the new face at all; the
        // fifth does. Matching must use the best, exactly like FaceMatcher.
        List<float[]> captured = Arrays.asList(new float[]{1f, 0f});
        List<EnrolmentWarnings.KnownPerson> people = known("person-7", "Ravi",
                atCos(-0.9), atCos(-0.5), atCos(0.1), atCos(0.4), atCos(0.93));

        EnrolmentWarnings.SimilarFace similar = EnrolmentWarnings.findSimilarFace(
                captured, people, EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD);

        assertNotNull(similar);
        assertEquals("person-7", similar.personId);
        assertEquals(0.93f, similar.score, 1e-4f);
    }

    @Test
    public void theMostSimilarPersonWinsWhenSeveralAreClose() {
        List<float[]> captured = Arrays.asList(new float[]{1f, 0f});
        List<EnrolmentWarnings.KnownPerson> people =
                new ArrayList<EnrolmentWarnings.KnownPerson>();
        people.addAll(known("person-1", "Priya", atCos(0.87)));
        people.addAll(known("person-2", "Amit", atCos(0.95)));

        EnrolmentWarnings.SimilarFace similar = EnrolmentWarnings.findSimilarFace(
                captured, people, EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD);

        assertNotNull(similar);
        assertEquals("person-2", similar.personId);
    }

    @Test
    public void emptyRosterOrEmptyCaptureNeverWarns() {
        List<float[]> captured = Arrays.asList(new float[]{1f, 0f});

        assertNull(EnrolmentWarnings.findSimilarFace(captured, null,
                EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD));
        assertNull(EnrolmentWarnings.findSimilarFace(captured,
                new ArrayList<EnrolmentWarnings.KnownPerson>(),
                EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD));
        assertNull(EnrolmentWarnings.findSimilarFace(null, known("person-1", "Priya",
                atCos(0.99)), EnrolmentWarnings.SIMILARITY_WARNING_THRESHOLD));
    }

    @Test
    public void similarMessageNamesThePersonAndTheScore() {
        EnrolmentWarnings.SimilarFace similar =
                new EnrolmentWarnings.SimilarFace("person-3", "Meera", 0.91f);

        String message = EnrolmentWarnings.similarFaceMessage(similar);

        assertTrue(message.contains("Meera"));
        assertTrue(message.contains("0.91"));
        assertTrue(message.contains("save anyway"));
    }

    @Test
    public void bestScoreHandlesEmptyAndNullInputs() {
        assertEquals(0f, EnrolmentWarnings.bestScore(null, null), 0f);
        assertEquals(0f, EnrolmentWarnings.bestScore(
                Arrays.asList(new float[0]), Arrays.asList(new float[]{1f})), 0f);
    }
}