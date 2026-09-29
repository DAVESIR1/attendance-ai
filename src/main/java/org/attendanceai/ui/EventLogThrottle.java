/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

/**
 * Decides which per-frame pipeline statuses deserve a line in the on-screen
 * event log. Pure JVM code — no android.* imports — so the dedupe-key and
 * rate-limit rules are unit-tested on the host.
 *
 * Why this exists: the previous throttle compared the full status text, but
 * statuses embed a live similarity score (`match: Person 1 @0.72`,
 * `unknown face (best=0.55)`). The score changes nearly every frame, so the
 * text was never "equal to last" and the log grew at frame rate — the
 * "continuous entries" bug. This class fixes that in two layers:
 *
 * 1. KEY-BASED DEDUPE — callers log a stable key (per person / per status
 *    category) instead of the score-bearing text, so a logical state is
 *    reported once per continuous sighting, not once per frame.
 * 2. HARD RATE LIMIT — no two status-driven lines ever land closer than
 *    {@link #MIN_INTERVAL_MS} apart regardless of how fast keys change, so a
 *    flapping match/unknown pair can still only produce one line per 1.5 s.
 *
 * One-shot events (enrol outcome, "PRESENT: … recorded for today", camera
 * start/stop) are written directly through AttendanceActivity.log(), which
 * calls {@link #markLogged(long)} so they count against the rate limit too.
 * They are inherently rare (a button tap, or the 30 s per-person attendance
 * cooldown), so they bypass the interval check — a punch or enrol
 * confirmation must never be delayed or dropped.
 *
 * Not thread-safe: called only from the UI thread (presentResult runs inside
 * runOnUiThread).
 */
public final class EventLogThrottle {

    /** Minimum gap between two status-driven log lines. */
    public static final long MIN_INTERVAL_MS = 1500L;

    private final long minIntervalMs;

    /** Key of the state currently on screen (may not be logged yet). */
    private String currentKey = "";
    /** Key of the most recently written line, null before the first line. */
    private String loggedKey = null;
    /** True while {@link #currentKey} still needs a line of its own. */
    private boolean pending;
    private boolean hasLogged;
    private long lastLogMs;

    public EventLogThrottle() {
        this(MIN_INTERVAL_MS);
    }

    /** Test/override hook: a different minimum interval. */
    public EventLogThrottle(long minIntervalMs) {
        if (minIntervalMs < 0L) {
            throw new IllegalArgumentException("minIntervalMs must be >= 0");
        }
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Rate limit + once-per-continuous-state decision for frame statuses.
     *
     * @param key    stable identity of the logical state (see {@link #matchKey},
     *               {@link #statusKey}, {@link #errorKey}) — never score text
     * @param nowMs  current wall-clock time
     * @return true when the caller should write a log line for this key now.
     *         A state change arriving inside the rate-limit window stays
     *         pending and is emitted at the next open slot (never lost).
     */
    public boolean shouldLog(String key, long nowMs) {
        String next = key == null ? "" : key;
        if (!next.equals(currentKey)) {
            currentKey = next;
            // Re-entering an already reported state (e.g. a one-frame detection
            // blip back to the same person) must not restart the sighting, so
            // only genuinely new states stay pending.
            pending = (loggedKey == null) || !next.equals(loggedKey);
        }
        if (!pending) {
            return false;
        }
        if (hasLogged && nowMs - lastLogMs < minIntervalMs) {
            return false; // keep pending: the new state is still worth a line
        }
        loggedKey = currentKey;
        pending = false;
        hasLogged = true;
        lastLogMs = nowMs;
        return true;
    }

    /**
     * Records that a line was written (any source). AttendanceActivity.log()
     * calls this for every line, so the hard rate limit is measured from the
     * last line of any kind.
     */
    public void markLogged(long nowMs) {
        hasLogged = true;
        lastLogMs = nowMs;
    }

    /** Key of the last written line (null before the first). Test/inspection. */
    public String loggedKey() {
        return loggedKey;
    }


    // ------------------------------------------------------------- keys

    /**
     * Stable key for a non-match status. The score-bearing text is never used
     * as a key — that is what defeated the old throttle.
     */
    public static String statusKey(String status) {
        if (status == null) {
            return "";
        }
        if (status.startsWith("no face")) {
            return "no-face";
        }
        if (status.startsWith("unknown face")) {
            return "unknown";
        }
        if (status.startsWith("embedding failed")) {
            return "embedding-failed";
        }
        return status;
    }

    /** Stable key for a match: one line per person per continuous sighting. */
    public static String matchKey(String personId) {
        return "match:" + (personId == null ? "" : personId);
    }

    /** Stable key for an error status. */
    public static String errorKey(String status) {
        return "error:" + (status == null ? "" : status);
    }

    /**
     * True for every outcome produced by FacePipeline.enrollBestFace — success
     * ("enrolled …") and its five distinct failure messages. Enrol outcomes
     * freeze the live result line so the confirmation cannot be overwritten by
     * the next camera frame ~100 ms later, and are always logged exactly once.
     */
    public static boolean isEnrolmentStatus(String status) {
        if (status == null) {
            return false;
        }
        return status.startsWith("enrolled ")
                || status.startsWith("cannot enrol")
                || status.startsWith("no face to enrol")
                || status.startsWith("face detection failed")
                || status.startsWith("embedding failed during enrolment")
                || status.startsWith("roster save failed");
    }
}
