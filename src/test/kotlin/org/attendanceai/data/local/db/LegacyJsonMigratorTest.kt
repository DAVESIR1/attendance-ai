/*
 * Attendance AI — legacy JSON migration tests (host JVM).
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import java.io.File
import java.nio.file.Files
import org.attendanceai.data.local.db.EmbeddingCodec
import org.attendanceai.store.AttendanceStore
import org.attendanceai.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder as TempFolderRule

/**
 * Covers the pure-JVM parts of the legacy → encrypted migration: snapshot
 * parsing, id mapping, record remapping, and best-effort secure deletion.
 * Database application is covered by the instrumented suite.
 */
class LegacyJsonMigratorTest {

    private lateinit var dir: File

    @Rule
    @JvmField
    val tmp = TempFolderRule()

    @Test
    fun snapshotReadsPeopleRecordsAndSettingsFromLegacyFiles() {
        dir = tmp.newFolder("legacy")
        val store = AttendanceStore(dir)
        val roster = LinkedHashMap<String, AttendanceStore.Person>()
        roster["person-1700000000000"] = AttendanceStore.Person.create(
            "person-1700000000000", "Test Person", floatArrayOf(0.25f, -0.5f, 0.75f),
        )
        store.saveRoster(roster)
        store.appendRecord(
            AttendanceStore.Record.create("person-1700000000000", "Test Person", 0.91f, 86_400_000L),
        )
        val settings = Settings()
        settings.similarityThreshold = 0.72f
        store.saveSettings(settings)

        val snapshot = LegacyJsonMigrator.readLegacySnapshot(dir)

        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.people.size)
        val person = snapshot.people.single()
        assertEquals("Test Person", person.name)
        assertEquals(3, person.template.size)
        assertEquals(1, snapshot.records.size)
        assertEquals("person-1700000000000", snapshot.records.single().personId)
        assertEquals(0.72f, snapshot.settings!!.similarityThreshold, 0.0001f)
    }

    @Test
    fun emptyDirectoryYieldsNoSnapshotSoMigrationIsNoOp() {
        dir = tmp.newFolder("empty")

        assertNull(LegacyJsonMigrator.readLegacySnapshot(dir))
    }

    @Test
    fun prepareKeepsNumericIdsAndDerivesConsentFromEnrolmentTime() {
        dir = tmp.newFolder("prepare")
        val store = AttendanceStore(dir)
        val roster = LinkedHashMap<String, AttendanceStore.Person>()
        val legacy = AttendanceStore.Person.create(
            "person-1700000000000", "Mapped Person", floatArrayOf(1.5f, 2.5f),
        )
        roster[legacy.id] = legacy
        store.saveRoster(roster)
        val snapshot = LegacyJsonMigrator.readLegacySnapshot(dir)!!

        val prepared = LegacyJsonMigrator.prepare(snapshot)

        val preparedPerson = prepared.people.single()
        assertEquals("person-1700000000000", preparedPerson.legacyId)
        assertEquals(1700000000000L, preparedPerson.entity.id)
        assertEquals("Mapped Person", preparedPerson.entity.name)
        assertEquals(legacy.enrolledAtMs, preparedPerson.entity.consentTimestamp)
        assertEquals(legacy.enrolledAtMs, preparedPerson.entity.createdAt)
        assertEquals(
            listOf(legacy.template).map { it.toList() },
            EmbeddingCodec.decode(preparedPerson.entity.faceEmbeddings).map { it.toList() },
        )
    }

    @Test
    fun prepareRemapsRecordPersonReferencesByLegacyId() {
        dir = tmp.newFolder("records")
        val store = AttendanceStore(dir)
        val roster = LinkedHashMap<String, AttendanceStore.Person>()
        roster["person-1700000000000"] = AttendanceStore.Person.create(
            "person-1700000000000", "Punched Person", floatArrayOf(0.1f),
        )
        store.saveRoster(roster)
        store.appendRecord(
            AttendanceStore.Record.create("person-1700000000000", "Punched Person", 0.8f, 172_800_000L),
        )
        store.appendRecord(
            AttendanceStore.Record.create("person-9999", "Ghost Person", 0.8f, 172_800_000L),
        )
        val snapshot = LegacyJsonMigrator.readLegacySnapshot(dir)!!

        val prepared = LegacyJsonMigrator.prepare(snapshot)

        assertEquals(2, prepared.records.size)
        assertTrue(prepared.records.all { it.legacyPersonId.startsWith("person-") })
        assertEquals(
            setOf("person-1700000000000", "person-9999"),
            prepared.records.map { it.legacyPersonId }.toSet(),
        )
    }

    @Test
    fun secureDeleteOverwritesAndRemovesTheFile() {
        dir = tmp.newFolder("delete")
        val file = File(dir, "secret.json")
        file.writeBytes(ByteArray(20_000) { (it % 251).toByte() })

        assertTrue(LegacyJsonMigrator.secureDelete(file))
        assertFalse(file.exists())
    }

    @Test
    fun secureDeleteOfMissingFileReportsSuccess() {
        dir = tmp.newFolder("missing")

        assertTrue(LegacyJsonMigrator.secureDelete(File(dir, "absent.json")))
    }
}
