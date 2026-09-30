/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Host-JVM tests for the on-screen failure formatter. These rules decide what
 * the phone shows when the face model fails to load, so they must stay stable:
 * a one-line class+message+cause description, bounded and cycle-safe.
 */
public final class FailureDiagnosisTest {

    @Test
    public void nullFailureIsExplicit() {
        assertEquals("unknown failure", FailureDiagnosis.describe(null));
    }

    @Test
    public void classAndMessageAreIncluded() {
        assertEquals("IllegalStateException: models dir missing",
                FailureDiagnosis.describe(new IllegalStateException("models dir missing")));
    }

    @Test
    public void missingMessageYieldsClassNameOnly() {
        assertEquals("IllegalStateException",
                FailureDiagnosis.describe(new IllegalStateException()));
    }

    @Test
    public void causeChainIsAppended() {
        // The exact shape of the on-device report: a native-library Error
        // wrapped by the init stage that caught it.
        Throwable root = new UnsatisfiedLinkError(
                "dlopen failed: library libmediapipe_tasks_jni.so not found");
        Throwable failure = new RuntimeException("face landmarker init failed", root);
        assertEquals("RuntimeException: face landmarker init failed ← caused by "
                        + "UnsatisfiedLinkError: dlopen failed: library "
                        + "libmediapipe_tasks_jni.so not found",
                FailureDiagnosis.describe(failure));
    }

    @Test
    public void causeChainIsCappedAtMaxDepth() {
        Throwable failure = new RuntimeException("l1",
                new RuntimeException("l2",
                        new RuntimeException("l3",
                                new RuntimeException("l4"))));
        String text = FailureDiagnosis.describe(failure);
        assertTrue(text, text.contains("l1"));
        assertTrue(text, text.contains("l3"));
        assertFalse("depth cap must drop the 4th link: " + text, text.contains("l4"));
    }

    @Test
    public void messagesAreCollapsedToASingleLine() {
        assertEquals("RuntimeException: line one line two",
                FailureDiagnosis.describe(new RuntimeException("line one\n  line two")));
    }

    @Test
    public void blankMessageIsOmitted() {
        assertEquals("RuntimeException",
                FailureDiagnosis.describe(new RuntimeException("   ")));
    }

    @Test
    public void longTextIsTruncatedWithEllipsis() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            message.append('x');
        }
        String text = FailureDiagnosis.describe(new RuntimeException(message.toString()), 40);
        assertEquals(40, text.length());
        assertTrue(text, text.endsWith("…"));
    }

    @Test
    public void tinyLimitStillReturnsOneCharacter() {
        assertEquals("…", FailureDiagnosis.describe(new RuntimeException("abc"), 1));
    }

    @Test
    public void nonPositiveLimitIsRejected() {
        try {
            FailureDiagnosis.describe(new RuntimeException("x"), 0);
            fail("maxLength <= 0 must throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxLength"));
        }
    }

    @Test
    public void causeCyclesTerminate() {
        RuntimeException outer = new RuntimeException("outer");
        RuntimeException inner = new RuntimeException("inner");
        outer.initCause(inner);
        inner.initCause(outer);
        String text = FailureDiagnosis.describe(outer);
        assertTrue(text, text.contains("outer"));
        assertTrue(text, text.contains("inner"));
    }

    @Test
    public void defaultLimitIsApplied() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            message.append('y');
        }
        String text = FailureDiagnosis.describe(new RuntimeException(message.toString()));
        assertEquals(FailureDiagnosis.MAX_LENGTH, text.length());
    }
}