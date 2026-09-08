/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Map;

public class AttendanceStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void rosterRoundTrips() throws Exception {
        AttendanceStore store = new AttendanceStore(folder.getRoot());
        Map<String, AttendanceStore.Person> people = store.loadRoster();
        people.put("p1", AttendanceStore.Person.create("p1", "Alice",
                new float[]{0.1f, 0.2f, 0.3f}));
        people.put("p2", AttendanceStore.Person.create("p2", "Bob",
                new float[]{1f, 1f, 1f}));
        store.saveRoster(people);

        Map<String, AttendanceStore.Person> loaded = store.loadRoster();
        assertEquals(2, loaded.size());
        AttendanceStore.Person alice = loaded.get("p1");
        assertEquals("Alice", alice.name);
        assertEquals(3, alice.template.length);
        assertEquals(0.2f, alice.template[1], 1e-6f);
    }

    @Test
    public void recordsAppendAndReload() throws Exception {
        AttendanceStore store = new AttendanceStore(folder.getRoot());
        store.appendRecord(AttendanceStore.Record.create("p1", "Alice", 0.95f, 1000L));
        store.appendRecord(AttendanceStore.Record.create("p2", "Bob", 0.87f, 2000L));

        java.util.List<AttendanceStore.Record> records = store.loadRecords();
        assertEquals(2, records.size());
        assertEquals("Alice", records.get(0).name);
        assertEquals(0.87f, records.get(1).score, 1e-6f);
    }

    @Test
    public void settingsRoundTrips() throws Exception {
        AttendanceStore store = new AttendanceStore(folder.getRoot());
        Settings settings = new Settings();
        settings.similarityThreshold = 0.71f;
        settings.stableFrames = 5;
        settings.embedder = Settings.EMBEDDER_SIGNATURE;
        store.saveSettings(settings);

        Settings loaded = store.loadSettings();
        assertEquals(0.71f, loaded.similarityThreshold, 1e-6f);
        assertEquals(5, loaded.stableFrames);
        assertEquals(Settings.EMBEDDER_SIGNATURE, loaded.embedder);
    }

    @Test
    public void missingFilesYieldEmptyState() throws Exception {
        AttendanceStore store = new AttendanceStore(folder.newFolder("empty"));
        assertTrue(store.loadRoster().isEmpty());
        assertTrue(store.loadRecords().isEmpty());
        assertEquals(new Settings().similarityThreshold,
                store.loadSettings().similarityThreshold, 1e-6f);
    }

    @Test
    public void corruptRosterDoesNotCrash() throws Exception {
        File dir = folder.newFolder("corrupt");
        java.io.FileWriter w = new java.io.FileWriter(new File(dir, "roster.json"));
        w.write("{ this is not valid json");
        w.close();
        AttendanceStore store = new AttendanceStore(dir);
        assertTrue(store.loadRoster().isEmpty());
    }
}