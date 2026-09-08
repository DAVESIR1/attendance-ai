/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.store;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsTest {

    @Test
    public void defaultsAreSane() {
        Settings s = new Settings();
        assertEquals(112, s.modelWidth);
        assertEquals(112, s.modelHeight);
        assertEquals(3, s.stableFrames);
        assertEquals(Settings.EMBEDDER_TFLITE, s.embedder);
    }

    @Test
    public void toMapFromMapRoundTrips() {
        Settings s = new Settings();
        s.similarityThreshold = 0.66f;
        s.stableFrames = 4;
        s.embedder = Settings.EMBEDDER_SIGNATURE;

        Settings copy = new Settings();
        copy.fromMap(s.toMap());
        assertEquals(0.66f, copy.similarityThreshold, 1e-6f);
        assertEquals(4, copy.stableFrames);
        assertEquals(Settings.EMBEDDER_SIGNATURE, copy.embedder);
    }

    @Test
    public void invalidEmbedderIsIgnored() {
        Settings s = new Settings();
        s.fromMap(java.util.Collections.singletonMap("embedder", (Object) "garbage"));
        assertEquals(Settings.EMBEDDER_TFLITE, s.embedder);
    }
}