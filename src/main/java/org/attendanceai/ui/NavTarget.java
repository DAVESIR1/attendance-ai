/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

/**
 * The three destinations of the navigation shell (item 2). Kept as a plain
 * Java enum with no Android types so the menu-id mapping is unit-testable on
 * a host JVM, while the activities do the actual navigation.
 *
 * Home is the current main screen (camera + enrol + roster) and stays the
 * default destination.
 */
public enum NavTarget {
    HOME(1, "Home"),
    REPORTS(2, "Reports"),
    SETTINGS(3, "Settings");

    private final int menuId;
    private final String label;

    NavTarget(int menuId, String label) {
        this.menuId = menuId;
        this.label = label;
    }

    /** Stable id used as the menu item id (never 0 = {@code Menu.NONE}). */
    public int getMenuId() {
        return menuId;
    }

    /** Text shown in the navigation menu. */
    public String getLabel() {
        return label;
    }

    /**
     * Resolves a clicked menu id. Unknown ids fall back to {@link #HOME} so a
     * stale id can never crash the menu handler.
     */
    public static NavTarget fromMenuId(int menuId) {
        for (NavTarget target : values()) {
            if (target.menuId == menuId) {
                return target;
            }
        }
        return HOME;
    }
}