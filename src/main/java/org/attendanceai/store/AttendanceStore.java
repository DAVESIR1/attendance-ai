/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.store;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.attendanceai.data.local.db.AttendanceDatabase;
import org.attendanceai.data.local.db.RoomAttendanceStore;

/**
 * Versioned JSON persistence for the attendance data model, stored in the
 * app's private directory. Every write is atomic (tmp + rename) so a crash
 * never leaves a torn file. Pure java.io — unit-testable on a host JVM.
 *
 * Schema:
 *   roster.json   { "version":1, "people": [ {id,name,enrolledAtMs,template:[...]} ] }
 *   records.json  { "version":1, "records": [ {personId,name,tsMs,score} ] }
 *   settings.json { "version":1, "settings": { ... } }
 */
public final class AttendanceStore {

    public static final int VERSION = 1;

    private final File dir;
    private final RoomAttendanceStore encrypted;

    /** Creates the legacy JSON store, retained for migration and JVM tests. */
    public AttendanceStore(File dir) {
        this(dir, null);
    }

    /** Creates a store backed by the already-unlocked SQLCipher database. */
    public AttendanceStore(File dir, AttendanceDatabase database) {
        this.dir = dir;
        this.encrypted = database == null ? null : new RoomAttendanceStore(database);
    }

    public File directory() {
        return dir;
    }

    // ------------------------------------------------------------ people

    /** Loads the roster; returns an empty map (never null) when absent. */
    public Map<String, Person> loadRoster() {
        if (encrypted != null) {
            return encrypted.loadRoster();
        }
        Map<String, Person> people = new LinkedHashMap<String, Person>();
        File file = rosterFile();
        if (!file.isFile()) {
            return people;
        }
        try {
            Map<String, Object> root = Json.asMap(Json.readFile(file));
            for (Object entryValue : Json.asList(root.get("people"))) {
                Map<String, Object> entry = Json.asMap(entryValue);
                Person person = new Person();
                person.id = Json.asString(entry.get("id"), "");
                person.name = Json.asString(entry.get("name"), "");
                person.enrolledAtMs = Json.asLong(entry.get("enrolledAtMs"), 0L);
                List<Object> template = Json.asList(entry.get("template"));
                if (person.id.length() > 0 && template.size() > 0) {
                    person.template = Json.floatArray(template);
                    people.put(person.id, person);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt roster must not crash the app: start empty.
            people.clear();
        }
        return people;
    }

    /** Persists the roster atomically. */
    public void saveRoster(Map<String, Person> people) throws IOException {
        if (encrypted != null) {
            encrypted.saveRoster(people);
            return;
        }
        List<Object> list = new ArrayList<Object>(people.size());
        for (Person person : people.values()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("id", person.id);
            entry.put("name", person.name);
            entry.put("enrolledAtMs", person.enrolledAtMs);
            entry.put("template", Json.doubleList(person.template == null
                    ? new float[0] : person.template));
            list.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", (long) VERSION);
        root.put("people", list);
        Json.writeFile(rosterFile(), root, true);
    }

    // ------------------------------------------------------------ records

    /** Loads attendance records, newest last. Never null. */
    public List<Record> loadRecords() {
        if (encrypted != null) {
            return encrypted.loadRecords();
        }
        List<Record> records = new ArrayList<Record>();
        File file = recordsFile();
        if (!file.isFile()) {
            return records;
        }
        try {
            Map<String, Object> root = Json.asMap(Json.readFile(file));
            for (Object entryValue : Json.asList(root.get("records"))) {
                Map<String, Object> entry = Json.asMap(entryValue);
                Record record = new Record();
                record.personId = Json.asString(entry.get("personId"), "");
                record.name = Json.asString(entry.get("name"), "");
                record.tsMs = Json.asLong(entry.get("tsMs"), 0L);
                record.score = Json.asFloat(entry.get("score"), 0f);
                records.add(record);
            }
        } catch (IOException | RuntimeException e) {
            records.clear();
        }
        return records;
    }

    /** Appends a record (read-modify-write, atomic). */
    public void appendRecord(Record record) throws IOException {
        if (encrypted != null) {
            encrypted.appendRecord(record);
            return;
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Record old : loadRecords()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("personId", old.personId);
            entry.put("name", old.name);
            entry.put("tsMs", old.tsMs);
            entry.put("score", (double) old.score);
            list.add(entry);
        }
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("personId", record.personId);
        entry.put("name", record.name);
        entry.put("tsMs", record.tsMs);
        entry.put("score", (double) record.score);
        list.add(entry);

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", (long) VERSION);
        root.put("records", list);
        Json.writeFile(recordsFile(), root, true);
    }

    // ------------------------------------------------------------ settings

    public Settings loadSettings() {
        if (encrypted != null) {
            return encrypted.loadSettings();
        }
        Settings settings = new Settings();
        File file = settingsFile();
        if (!file.isFile()) {
            return settings;
        }
        try {
            Map<String, Object> root = Json.asMap(Json.readFile(file));
            settings.fromMap(Json.asMap(root.get("settings")));
        } catch (IOException | RuntimeException e) {
            // defaults
        }
        return settings;
    }

    public void saveSettings(Settings settings) throws IOException {
        if (encrypted != null) {
            encrypted.saveSettings(settings);
            return;
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", (long) VERSION);
        root.put("settings", settings.toMap());
        Json.writeFile(settingsFile(), root, true);
    }

    // ------------------------------------------------------------ paths

    public File rosterFile() {
        return new File(dir, "roster.json");
    }

    public File recordsFile() {
        return new File(dir, "records.json");
    }

    public File settingsFile() {
        return new File(dir, "settings.json");
    }

    public File modelsDir() {
        return new File(dir, "models");
    }

    // ------------------------------------------------------------ value types

    /** A rostered person with their mean embedding template. */
    public static final class Person {
        public String id = "";
        public String name = "";
        public long enrolledAtMs = 0L;
        public float[] template = new float[0];

        public static Person create(String id, String name, float[] template) {
            Person person = new Person();
            person.id = id;
            person.name = name;
            person.enrolledAtMs = System.currentTimeMillis();
            person.template = template == null ? new float[0] : template;
            return person;
        }
    }

    /** One punched attendance record. */
    public static final class Record {
        public String personId = "";
        public String name = "";
        public long tsMs = 0L;
        public float score = 0f;

        public static Record create(String personId, String name, float score, long tsMs) {
            Record record = new Record();
            record.personId = personId;
            record.name = name;
            record.score = score;
            record.tsMs = tsMs;
            return record;
        }
    }
}