/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Bounded scrollback for the on-screen event log. Pure JVM code — no android.*
 * imports — so the cap behaviour is unit-tested on the host.
 *
 * The old log appended every line forever (logView.setText(old + "\n" + new)),
 * so a long camera session grew an unbounded string: main-thread churn plus a
 * full ScrollView relayout per line. This keeps only the newest
 * {@link #DEFAULT_MAX_LINES} lines and renders them on demand.
 *
 * Single-threaded by construction (UI thread only).
 */
public final class LogHistory {

    /** Newest lines kept for display ("~50 lines" from the fix list). */
    public static final int DEFAULT_MAX_LINES = 50;

    private final int maxLines;
    private final ArrayDeque<String> lines = new ArrayDeque<String>();

    public LogHistory() {
        this(DEFAULT_MAX_LINES);
    }

    public LogHistory(int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be >= 1");
        }
        this.maxLines = maxLines;
    }

    /**
     * Appends one line, dropping the oldest when over capacity.
     *
     * @return the complete log text (lines joined with {@code \n}) — ready for
     *         {@code TextView.setText}. A null line leaves the history unchanged.
     */
    public String append(String line) {
        if (line != null) {
            lines.addLast(line);
            while (lines.size() > maxLines) {
                lines.removeFirst();
            }
        }
        return render();
    }

    /** Newest-first? No — insertion (oldest → newest) join with {@code \n}. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = lines.iterator();
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(it.next());
        }
        return sb.toString();
    }

    public int size() {
        return lines.size();
    }

    public int maxLines() {
        return maxLines;
    }

    public void clear() {
        lines.clear();
    }
}
