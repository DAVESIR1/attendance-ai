/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

/**
 * The ordered poses of the guided multi-angle enrolment flow. The order is part
 * of the spec (LOOK_UP → LOOK_DOWN → LOOK_LEFT → LOOK_RIGHT → BLINK) and the
 * on-screen instruction is derived from the same enum so the two can never
 * drift apart.
 */
public enum CaptureStep {
    LOOK_UP("Look up"),
    LOOK_DOWN("Look down"),
    LOOK_LEFT("Look left"),
    LOOK_RIGHT("Look right"),
    BLINK("Blink your eyes");

    private final String instruction;

    CaptureStep(String instruction) {
        this.instruction = instruction;
    }

    /** Short on-screen instruction text for this step. */
    public String instruction() {
        return instruction;
    }

    /** Total number of guided steps (5). */
    public static int count() {
        return values().length;
    }

    /** One-based position of this step, for "Step 2 of 5". */
    public int stepNumber() {
        return ordinal() + 1;
    }
}