/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the log cap (fix 2): the on-screen log must keep only the newest
 * entries instead of growing an unbounded string.
 */
public class LogHistoryTest {

    @Test
    public void appendRendersASingleLine() {
        LogHistory history = new LogHistory(5);
        assertEquals("camera ready", history.append("camera ready"));
        assertEquals(1, history.size());
    }

    @Test
    public void linesAreJoinedWithNewlinesInInsertionOrder() {
        LogHistory history = new LogHistory(5);
        history.append("first");
        assertEquals("first\nsecond", history.append("second"));
        assertEquals(2, history.size());
    }

    @Test
    public void oldestLinesAreDroppedAtCapacity() {
        LogHistory history = new LogHistory(3);
        history.append("one");
        history.append("two");
        history.append("three");
        String rendered = history.append("four");
        assertEquals(3, history.size());
        assertEquals("two\nthree\nfour", rendered);
    }

    @Test
    public void defaultCapacityIsFiftyLines() {
        LogHistory history = new LogHistory();
        assertEquals(50, history.maxLines());
        for (int i = 1; i <= 60; i++) {
            history.append("line " + i);
        }
        assertEquals(50, history.size());
        String[] lines = history.render().split("\n");
        assertEquals(50, lines.length);
        // The 10 oldest lines are gone; the view starts at line 11.
        assertEquals("line 11", lines[0]);
        assertEquals("line 60", lines[lines.length - 1]);
    }

    @Test
    public void nullAppendLeavesHistoryUnchanged() {
        LogHistory history = new LogHistory(5);
        history.append("kept");
        String before = history.render();
        assertEquals(before, history.append(null));
        assertEquals(1, history.size());
    }

    @Test
    public void clearEmptiesTheHistory() {
        LogHistory history = new LogHistory(5);
        history.append("a");
        history.clear();
        assertEquals(0, history.size());
        assertEquals("", history.render());
    }

    @Test
    public void emptyHistoryRendersEmpty() {
        assertEquals("", new LogHistory().render());
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroCapacityIsRejected() {
        new LogHistory(0);
    }
}
