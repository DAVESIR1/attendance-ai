/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.pipeline;

import java.util.HashMap;
import java.util.Map;

import org.attendanceai.store.AttendanceStore;

/**
 * Encodes the check-in semantics: a recognised person may be punched once
 * per cooldown window. Pure JVM code, unit-tested.
 */
public final class AttendanceService {

    private final long cooldownMs;
    private final Map<String, Long> lastPunchByPerson = new HashMap<String, Long>();

    public AttendanceService(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /**
     * Attempts to record attendance for {@code personId}. Returns a new
     * {@link org.attendanceai.store.AttendanceStore.Record} when the person
     * has not been punched within the cooldown, otherwise null.
     */
    public AttendanceStore.Record maybePunch(String personId, String name,
            float score, long nowMs) {
        if (personId == null || personId.length() == 0) {
            return null;
        }
        Long last = lastPunchByPerson.get(personId);
        if (last != null && nowMs - last < cooldownMs) {
            return null;
        }
        lastPunchByPerson.put(personId, Long.valueOf(nowMs));
        return AttendanceStore.Record.create(personId, name, score, nowMs);
    }

    /** Resets cooldowns (e.g. new work day / demo restart). */
    public void reset() {
        lastPunchByPerson.clear();
    }
}