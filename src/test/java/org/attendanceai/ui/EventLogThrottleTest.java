/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Covers the two rules that caused the "continuous entries" bug: key-based
 * dedupe (fix 1 + 5) and the hard one-line-per-1.5 s rate limit (fix 2),
 * plus the key derivation and enrolment classification the UI depends on.
 */
public class EventLogThrottleTest {

    private static final long STEP = EventLogThrottle.MIN_INTERVAL_MS;

    @Test
    public void firstStatusLogsImmediately() {
        EventLogThrottle throttle = new EventLogThrottle();
        assertTrue(throttle.shouldLog("no-face", 0L));
        assertEquals("no-face", throttle.loggedKey());
    }

    @Test
    public void samePersonLogsOnceForAHundredFrames() {
        // Fix 5: 300 frames of the same person (varying scores upstream) must
        // produce exactly ONE log line — the key never changes.
        EventLogThrottle throttle = new EventLogThrottle();
        int logged = 0;
        long now = 1_000_000L;
        for (int frame = 0; frame < 300; frame++) {
            if (throttle.shouldLog(EventLogThrottle.matchKey("person-1"), now)) {
                logged++;
            }
            now += 100L; // ~30 s of frames
        }
        assertEquals(1, logged);
    }

    @Test
    public void stateChangeWaitsForTheRateLimitAndThenLogs() {
        EventLogThrottle throttle = new EventLogThrottle();
        assertTrue(throttle.shouldLog("unknown", 0L));
        assertFalse("inside the 1.5 s window", throttle.shouldLog("match:p1", 100L));
        assertFalse("still inside the window", throttle.shouldLog("match:p1", STEP - 1));
        assertTrue("window elapsed", throttle.shouldLog("match:p1", STEP));
        assertEquals("pending state must be the one logged", "match:p1", throttle.loggedKey());
    }

    @Test
    public void hardRateLimitHoldsUnderFlappingKeys() {
        // Alternating match/unknown keys 100 times over 10 s: never more than
        // one line per 1.5 s regardless of how fast the content changes.
        EventLogThrottle throttle = new EventLogThrottle();
        List<Long> written = new ArrayList<Long>();
        long now = 0L;
        for (int i = 0; i < 100; i++) {
            String key = (i % 2 == 0)
                    ? EventLogThrottle.matchKey("person-1") : "unknown";
            if (throttle.shouldLog(key, now)) {
                written.add(now);
            }
            now += 100L;
        }
        assertTrue("expected at most 7 lines in 10 s, got " + written.size(),
                written.size() <= 7);
        for (int i = 1; i < written.size(); i++) {
            assertTrue("lines must be >= 1.5 s apart",
                    written.get(i) - written.get(i - 1) >= STEP);
        }
    }

    @Test
    public void returningToAnAlreadyReportedStateDoesNotLogAgain() {
        // One-frame detection blip back to the same person must not restart
        // the sighting (a continuous sighting stays a single line).
        EventLogThrottle throttle = new EventLogThrottle();
        assertTrue(throttle.shouldLog(EventLogThrottle.matchKey("person-1"), 0L));
        assertFalse(throttle.shouldLog("no-face", 100L));   // rate-limited, pending
        assertFalse(throttle.shouldLog(EventLogThrottle.matchKey("person-1"), 200L));
        assertFalse("same person later still needs no line",
                throttle.shouldLog(EventLogThrottle.matchKey("person-1"), 60_000L));
        // A genuinely new state after that does get its line.
        assertTrue(throttle.shouldLog("unknown", 60_000L));
    }

    @Test
    public void logsAgainAfterLeavingTheFrameAndComingBack() {
        // Fix 5 explicitly: once per continuous sighting, not once per frame.
        EventLogThrottle throttle = new EventLogThrottle();
        assertTrue("first sighting", throttle.shouldLog(
                EventLogThrottle.matchKey("person-1"), 0L));
        assertTrue("left the frame", throttle.shouldLog("no-face", 2 * STEP));
        assertTrue("second sighting", throttle.shouldLog(
                EventLogThrottle.matchKey("person-1"), 4 * STEP));
    }

    @Test
    public void eventLinesCountAgainstTheRateLimit() {
        // markLogged() is called for every written line (enrol, punch, camera
        // messages), so a status right after an event still has to wait.
        EventLogThrottle throttle = new EventLogThrottle();
        assertTrue(throttle.shouldLog("no-face", 0L));
        throttle.markLogged(500L); // e.g. "PRESENT: … recorded for today"
        assertFalse(throttle.shouldLog("match:p1", 600L));
        assertTrue(throttle.shouldLog("match:p1", 500L + STEP));
    }

    @Test
    public void statusKeyIgnoresTheVaryingScore() {
        // The score used to defeat the old full-text comparison.
        assertEquals("unknown",
                EventLogThrottle.statusKey("unknown face (best=0.55)"));
        assertEquals("unknown",
                EventLogThrottle.statusKey("unknown face (best=0.99)"));
        assertEquals("no-face", EventLogThrottle.statusKey("no face"));
        assertEquals("embedding-failed", EventLogThrottle.statusKey("embedding failed"));
        assertEquals("", EventLogThrottle.statusKey(null));
        // Already-stable texts are their own key.
        assertEquals("face model unavailable",
                EventLogThrottle.statusKey("face model unavailable"));
    }

    @Test
    public void matchAndErrorKeysAreFormatted() {
        assertEquals("match:person-1", EventLogThrottle.matchKey("person-1"));
        assertEquals("match:", EventLogThrottle.matchKey(null));
        assertEquals("error:inference error", EventLogThrottle.errorKey("inference error"));
    }

    @Test
    public void enrolmentOutcomesAreRecognised() {
        assertTrue(EventLogThrottle.isEnrolmentStatus("enrolled Person 1"));
        assertTrue(EventLogThrottle.isEnrolmentStatus(
                "cannot enrol (need a frame and a name)"));
        // The per-guard messages added after the phone report: a missing face
        // model, a missing frame and a missing name each get their own text.
        assertTrue(EventLogThrottle.isEnrolmentStatus(
                "cannot enrol: face model unavailable"));
        assertTrue(EventLogThrottle.isEnrolmentStatus(
                "cannot enrol: face model unavailable — UnsatisfiedLinkError: dlopen failed"));
        assertTrue(EventLogThrottle.isEnrolmentStatus(
                "cannot enrol: no frame yet (start the camera first)"));
        assertTrue(EventLogThrottle.isEnrolmentStatus("cannot enrol: no name given"));
        assertTrue(EventLogThrottle.isEnrolmentStatus("no face to enrol"));
        assertTrue(EventLogThrottle.isEnrolmentStatus("face detection failed"));
        assertTrue(EventLogThrottle.isEnrolmentStatus(
                "embedding failed during enrolment"));
        assertTrue(EventLogThrottle.isEnrolmentStatus("roster save failed: disk"));
        // Regular per-frame statuses are NOT enrol outcomes: they must stay on
        // the throttled path and must not freeze the live result line.
        assertFalse(EventLogThrottle.isEnrolmentStatus("no face"));
        assertFalse(EventLogThrottle.isEnrolmentStatus("unknown face (best=0.55)"));
        assertFalse(EventLogThrottle.isEnrolmentStatus("person-1 seen (#7)"));
        assertFalse(EventLogThrottle.isEnrolmentStatus("inference error"));
        assertFalse(EventLogThrottle.isEnrolmentStatus(null));
    }

    @Test
    public void customIntervalIsRespected() {
        EventLogThrottle throttle = new EventLogThrottle(100L);
        assertTrue(throttle.shouldLog("a", 0L));
        assertFalse(throttle.shouldLog("b", 99L));
        assertTrue(throttle.shouldLog("b", 100L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeIntervalIsRejected() {
        new EventLogThrottle(-1L);
    }
}
