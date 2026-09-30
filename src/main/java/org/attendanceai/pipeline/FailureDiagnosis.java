/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.pipeline;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Formats a startup/runtime failure into one short, human-readable line for
 * the on-screen banner, result line and event log. Pure JVM — no android.*
 * imports — so the formatting rules are unit-tested on the host.
 *
 * Why this exists: the previous code showed only
 * {@code failure.getClass().getSimpleName()}. For the reported phone bug
 * ("camera blocked because face model is unavailable") that yields a bare
 * "UnsatisfiedLinkError" — no message, no cause — which is impossible to
 * diagnose from a screenshot. The cause chain carries the real reason, e.g.
 * {@code UnsatisfiedLinkError: dlopen failed: library libmediapipe_tasks_jni.so not found}.
 */
public final class FailureDiagnosis {

    /** Hard cap so the text fits the single-line banner/result views. */
    public static final int MAX_LENGTH = 200;

    /** How much of a cause chain to include (outermost failure first). */
    public static final int MAX_CAUSE_DEPTH = 3;

    /** Separator between a failure and its cause. */
    public static final String CAUSE_SEPARATOR = " ← caused by ";

    /** Placeholder when there is nothing to describe. */
    public static final String UNKNOWN = "unknown failure";

    private FailureDiagnosis() {
    }

    /** Describes {@code failure} with the default length cap. */
    public static String describe(Throwable failure) {
        return describe(failure, MAX_LENGTH);
    }

    /**
     * Describes {@code failure} as {@code Type: message ← caused by Type: message …},
     * limited to {@code MAX_CAUSE_DEPTH} chain links (cycle-safe) and
     * truncated to {@code maxLength} characters with an ellipsis.
     *
     * @param failure   the failure to describe; {@code null} yields {@link #UNKNOWN}
     * @param maxLength positive hard cap for the returned text
     * @return a single-line description, never null
     */
    public static String describe(Throwable failure, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be > 0");
        }
        if (failure == null) {
            return truncate(UNKNOWN, maxLength);
        }
        StringBuilder text = new StringBuilder();
        // Identity set: cause chains may (maliciously or accidentally) loop.
        Set<Throwable> seen = Collections.newSetFromMap(
                new IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (text.length() > 0) {
                text.append(CAUSE_SEPARATOR);
            }
            text.append(typeName(current));
            String message = oneLine(current.getMessage());
            if (!message.isEmpty()) {
                text.append(": ").append(message);
            }
            current = current.getCause();
            depth++;
        }
        return truncate(text.toString(), maxLength);
    }

    /** Simple class name; full name for classes without one (anonymous). */
    static String typeName(Throwable failure) {
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }

    /** Messages must stay on one line — the banner/log are single-line views. */
    static String oneLine(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().replaceAll("\\s+", " ");
    }

    /** Cuts the text to {@code maxLength} characters (last one an ellipsis). */
    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength == 1) {
            return "…";
        }
        return text.substring(0, maxLength - 1) + "…";
    }
}