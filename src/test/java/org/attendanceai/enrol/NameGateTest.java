/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.enrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The name-first gate: trimmed, non-blank, at most 100 characters. */
public class NameGateTest {

    @Test
    public void plainNameIsValid() {
        assertTrue(NameGate.isValid("Priya"));
        assertEquals("Priya", NameGate.normalise("  Priya  "));
    }

    @Test
    public void blankOrNullNameIsRejected() {
        assertFalse(NameGate.isValid(""));
        assertFalse(NameGate.isValid("   "));
        assertFalse(NameGate.isValid(null));
        assertFalse(NameGate.isValid("\t\n"));
    }

    @Test
    public void nameIsCappedAtOneHundredCharacters() {
        StringBuilder hundred = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            hundred.append('a');
        }
        assertTrue(NameGate.isValid(hundred.toString()));

        assertFalse(NameGate.isValid(hundred.toString() + "b"));
        assertFalse(NameGate.isValid("  " + hundred + "  x"));
    }

    @Test
    public void invalidReasonsAreSpecificAndEmptyWhenValid() {
        assertEquals("", NameGate.invalidReason("Priya"));
        assertTrue(NameGate.invalidReason(" ").contains("blank"));

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            tooLong.append('x');
        }
        assertTrue(NameGate.invalidReason(tooLong.toString()).contains("100"));
    }
}
