/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.enrol;

/**
 * The name-first gate that must be satisfied before any camera or capture step
 * of the guided enrolment starts: the name is trimmed, must be non-blank and is
 * capped at {@link #MAX_NAME_LENGTH} characters.
 *
 * Pure JVM code so the rule is unit-tested on the host rather than only through
 * the dialog.
 */
public final class NameGate {

    /** Longest accepted name, so one field cannot overflow the UI or the DB. */
    public static final int MAX_NAME_LENGTH = 100;

    private NameGate() {
    }

    /** Trimmed name ("" for null). */
    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** True when the trimmed name is non-blank and within the length cap. */
    public static boolean isValid(String raw) {
        String name = normalise(raw);
        return !name.isEmpty() && name.length() <= MAX_NAME_LENGTH;
    }

    /** Empty when valid, otherwise the reason to show next to the field. */
    public static String invalidReason(String raw) {
        String name = normalise(raw);
        if (name.isEmpty()) {
            return "Enter a name to continue — it cannot be blank.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Name is too long (max " + MAX_NAME_LENGTH + " characters).";
        }
        return "";
    }
}
